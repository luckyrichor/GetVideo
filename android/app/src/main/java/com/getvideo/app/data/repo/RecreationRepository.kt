package com.getvideo.app.data.repo

import android.content.Context
import android.net.Uri
import com.getvideo.app.data.local.TrackEntity
import com.getvideo.app.data.remote.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Calls only GetVideo; Ark keys never enter this process or device preferences. */
class RecreationRepository(private val context: Context, val account: AccountStore.Account) {
    private val api = ApiProvider.api(account.serverUrl, account)
    private val scope = cloudScopeKey(account.serverUrl, account.userId)
    private val prefs = context.getSharedPreferences("ai-recreation-$scope", Context.MODE_PRIVATE)
    private val cache = File(context.cacheDir, "ai-recreation/$scope").apply { mkdirs() }
    private suspend fun checkAccount() {
        check(AccountStore.forServer(Settings(context).serverUrlNow()) == account) { "账号或服务器已切换，请重新打开 AI 页面" }
    }
    suspend fun capabilities() = api.recreationCapabilities().also { checkAccount() }
    suspend fun projects() = api.recreationProjects().also { checkAccount() }
    fun lastId(): String? = prefs.getString("last_project", null)
    suspend fun project(id: String) = api.recreationProject(id).also { checkAccount(); prefs.edit().putString("last_project", id).apply() }
    suspend fun analyze(p: RecreationProjectDto) = api.recreationAnalyze(p.id, RecreationAnalyzeBody(p.status == "failed")).also { checkAccount() }
    suspend fun edit(p: RecreationProjectDto, guidance: String, drafts: Map<String, String>) =
        api.recreationEdit(p.id, RecreationDescriptionEdit(p.revision, guidance, p.clips.map { RecreationClipEdit(it.id, drafts[it.id] ?: it.description) })).also { checkAccount() }
    suspend fun generate(p: RecreationProjectDto, mode: String, resolution: String, keepAudio: Boolean, preview: Boolean, acknowledge: Boolean): RecreationProjectDto {
        checkAccount()
        val key = prefs.getString("pending-${p.id}", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("pending-${p.id}", it).commit() }
        val result = api.recreationGenerate(p.id, RecreationGenerateBody(key, p.revision, mode, resolution, keepAudio, preview, acknowledge))
        checkAccount(); prefs.edit().remove("pending-${p.id}").apply()
        return result
    }
    suspend fun cancel(p: RecreationProjectDto, run: RecreationRunDto) = api.recreationCancel(p.id, run.id).also { checkAccount() }
    suspend fun uploadTrack(track: TrackEntity, analyzeAudio: Boolean): RecreationProjectDto {
        checkAccount()
        // Snapshot the actual local/edited file, never silently re-fetch its original share URL.
        val file = TrackRepository(context).editSource(track.id)
        return try { upload(file, track.title, analyzeAudio) } finally { file.delete() }
    }
    suspend fun uploadUri(uri: Uri, analyzeAudio: Boolean, maxBytes: Long): RecreationProjectDto = withContext(Dispatchers.IO) {
        val file = File.createTempFile("ai-upload-", ".mp4", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { out ->
                val buffer = ByteArray(65536); var total = 0L
                while (true) {
                    val size = input.read(buffer); if (size < 0) break
                    total += size; require(total <= maxBytes) { "视频超过上传上限，请先剪辑" }; out.write(buffer, 0, size)
                }
            } } ?: error("无法读取所选视频")
            upload(file, "手机视频", analyzeAudio)
        } finally { file.delete() }
    }
    private suspend fun upload(file: File, title: String, audio: Boolean): RecreationProjectDto = withContext(Dispatchers.IO) {
        checkAccount()
        val digest = sha(file)
        // Same file retry reuses its upload key, including after the page/process restarts.
        val pref = "upload-$digest-$audio"
        val key = prefs.getString(pref, null) ?: UUID.randomUUID().toString().also { prefs.edit().putString(pref, it).commit() }
        api.recreationUpload(key, title.take(120), audio, file.asRequestBody("video/mp4".toMediaType())).also {
            checkAccount(); prefs.edit().putString("last_project", it.id).apply()
        }
    }
    suspend fun download(p: RecreationProjectDto, run: RecreationRunDto? = null): File = withContext(Dispatchers.IO) {
        checkAccount()
        val relative = run?.downloadUrl ?: p.sourceUrl
        require(relative.startsWith("/v2/recreations/") && !relative.contains("..")) { "无效下载地址" }
        val hash = if (run == null) p.sourceSha256 else requireNotNull(run.resultSha256) { "结果校验信息未返回" }
        val dest = File(cache, "${p.id}-${run?.id ?: "source"}.mp4")
        if (dest.isFile && sha(dest) == hash) return@withContext dest
        val temp = File.createTempFile("ai-result-", ".part", cache)
        try {
            val url = account.serverUrl.toHttpUrl().resolve(relative) ?: error("无效视频地址")
            ApiProvider.okHttp(account).newCall(Request.Builder().url(url).build()).execute().use { response ->
                check(response.isSuccessful) { "视频下载失败（HTTP ${response.code}）" }
                response.body?.byteStream()?.use { input -> temp.outputStream().use { out ->
                    val buffer = ByteArray(65536); var total = 0L
                    while (true) {
                        val n = input.read(buffer); if (n < 0) break
                        total += n; check(total <= 256L*1024*1024) { "视频超过大小限制" }; out.write(buffer, 0, n)
                    }
                } } ?: error("视频内容为空")
            }
            check(sha(temp) == hash) { "视频下载不完整或已变更，请重试" }
            checkAccount(); check(temp.renameTo(dest)) { "无法保存预览视频" }
            dest
        } finally { temp.delete() }
    }
    suspend fun save(p: RecreationProjectDto, run: RecreationRunDto) {
        checkAccount()
        val saved = api.recreationSave(p.id, run.id)
        checkAccount()
        val repo = TrackRepository(context); repo.sync()
        checkAccount()
        val track = repo.allNow().firstOrNull { it.remoteTrackId == saved.id && it.cloudScope == scope }
            ?: error("服务器已保存，请到视频库同步后查看")
        repo.download(track).getOrThrow()
    }
    private fun sha(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(65536); while (true) {
            val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n)
        } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
