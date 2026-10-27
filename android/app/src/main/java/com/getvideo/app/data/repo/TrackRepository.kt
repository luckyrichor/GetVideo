package com.getvideo.app.data.repo

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.withTransaction
import com.getvideo.app.player.PlayerHolder
import com.getvideo.app.data.local.AppDatabase
import com.getvideo.app.data.local.SyncStateEntity
import com.getvideo.app.data.local.TrackEntity
import com.getvideo.app.data.remote.TrackDto
import com.getvideo.app.data.remote.ExtractRequest
import com.getvideo.app.data.localextract.DirectExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import okhttp3.Request
import java.io.File

class TrackRepository(private val context: Context) {

    companion object { internal val fileMutex = Mutex() }

    private val db = AppDatabase.get(context)
    private val trackDao = db.trackDao()
    private val syncDao = db.syncStateDao()
    private val settings = Settings(context)

    private val audioDir: File
        get() = File(context.filesDir, "video").apply { mkdirs() }

    // ---------- 读 ----------

    fun filtered(q: String, platform: String?, favoritesOnly: Boolean) = trackDao.filtered(q, platform, favoritesOnly)

    fun observeAll(): Flow<List<TrackEntity>> = trackDao.observeAll()
    fun search(q: String): Flow<List<TrackEntity>> = trackDao.search(q)
    fun byPlatform(p: String): Flow<List<TrackEntity>> = trackDao.byPlatform(p)
    fun favorites(): Flow<List<TrackEntity>> = trackDao.favorites()

    fun allWithTimeRange(q: String, startMs: Long?, endMs: Long?) =
        trackDao.allWithTimeRange(q, startMs, endMs)

    fun inCategory(categoryId: Long) = trackDao.inCategory(categoryId)

    suspend fun allNow(): List<TrackEntity> = trackDao.allNow()

    suspend fun byId(id: Long): TrackEntity? = trackDao.byId(id)

    suspend fun rename(id: Long, title: String) {
        require(title.isNotBlank()) { "视频名称不能为空" }
        trackDao.rename(id, title.trim(), System.currentTimeMillis())
        withContext(Dispatchers.Main) {
            com.getvideo.app.player.PlayerHolder.rename(context, id, title.trim())
        }
    }

    suspend fun remove(id: Long) { removeAll(setOf(id)) }

    /** Delete metadata and every playlist reference in one transaction, then reconcile playback. */
    suspend fun removeAll(ids: Set<Long>): Int {
        if (ids.isEmpty()) return 0
        return withContext(NonCancellable) {
            val removed = withContext(Dispatchers.IO) {
                fileMutex.withLock {
                    db.withTransaction {
                        val tracks = ids.mapNotNull { trackDao.byId(it) }
                        tracks.filter { it.id > 0 }.forEach { trackDao.suppress(it.id) }
                        // Bound query size so selecting a large library cannot exceed SQLite bind limits.
                        tracks.map { it.id }.chunked(500).forEach {
                            trackDao.removePlaylistReferences(it)
                            trackDao.deleteByIds(it)
                        }
                        tracks
                    }.also { removed -> removed.forEach { it.localPath?.let { path -> File(path).delete() } } }
                }
            }
            withContext(Dispatchers.Main) { PlayerHolder.removeTracks(context, removed.map { it.id }.toSet()) }
            removed.size
        }
    }

    suspend fun setFavorite(id: Long, fav: Boolean) = trackDao.setFavorite(id, fav)

    // ---------- 提取 ----------

    /** 分享文本由手机直接提取，保存到本地库，不依赖 GetVideo 服务器。 */
    suspend fun extract(text: String, onDiagnostic: (String) -> Unit = {}): Result<TrackEntity> = runCatching {
        withContext(Dispatchers.IO) {
            val entity = fileMutex.withLock {
                val extracted = DirectExtractor(context) { line ->
                    withContext(Dispatchers.Main) { onDiagnostic(line) }
                }.extract(text)
                trackDao.upsertLocal(extracted)
                extracted
            }
            withContext(Dispatchers.Main) { onDiagnostic("已加入本地视频库") }
            entity
        }
    }

    /** Explicit fallback: ask the configured GetVideo server to extract, then cache its audio on this phone. */
    suspend fun extractViaServer(
        text: String, onDiagnostic: (String) -> Unit = {},
        expectedAccount: AccountStore.Account? = null,
    ): Result<TrackEntity> = runCatching {
        val base = settings.serverUrlNow()
        require(base.isNotBlank()) { "请先到「我的 → 服务器设置」填写服务器地址" }
        onDiagnostic("服务器提取：已提交，等待处理")
        val account = expectedAccount ?: AccountStore.forServer(base) ?: error("请先登录 GetVideo 账号")
        checkAccount(account)
        val response = ApiProvider.api(base, account).extract(ExtractRequest(text = text))
        val entity = fileMutex.withLock {
            checkAccount(account)
            scopedEntity(response.track, account)
        }
        onDiagnostic("服务器提取：成功，开始下载视频到手机")
        fileMutex.withLock { checkAccount(account); trackDao.restoreExtracted(entity) }
        download(entity).getOrThrow()
        onDiagnostic("服务器视频：下载完成，已加入本地视频库")
        trackDao.byId(entity.id) ?: entity
    }

    // ---------- 同步 ----------

    /** 增量拉取，返回本次新增/更新的条数。 */
    suspend fun sync(): Int = withContext(Dispatchers.IO) {
        val deletedIds = mutableSetOf<Long>()
        try {
            fileMutex.withLock {
                val serverUrl = settings.serverUrlNow()
                if (serverUrl.isBlank()) return@withLock 0
                val account = AccountStore.forServer(serverUrl) ?: error("请先登录 GetVideo 账号")
                val scope = cloudScopeKey(serverUrl, account.userId)
                val api = ApiProvider.api(serverUrl, account)
                var cursor = trackDao.cloudCursor(scope) ?: 0
                var total = 0

                // has_more 时继续拉，直到追平
                while (true) {
                    val page = api.sync(cursor = cursor)
                    if (page.items.isEmpty()) break

                    checkAccount(account)
                    val (deleted, alive) = page.items.partition { it.deleted }
                    val removed = trackDao.mappedIds(scope, deleted.map { it.id })
                    trackDao.applySync(alive.map { scopedEntity(it, account) }, removed)
                    deletedIds.addAll(removed.filter { trackDao.byId(it) == null })

                    total += page.items.size
                    cursor = page.nextCursor
                    trackDao.saveCursor(com.getvideo.app.data.local.CloudSyncEntity(scope, cursor))
                    if (!page.hasMore) break
                }
                total
            }
        } finally {
            // Release the download mutex before waiting for a queue operation.
            if (deletedIds.isNotEmpty()) {
                withContext(NonCancellable + Dispatchers.Main) { PlayerHolder.removeTracks(context, deletedIds) }
                deletedIds.forEach { localFile(it).delete() }
            }
        }
    }

    // ---------- 下载 ----------

    fun localFile(id: Long): File = File(audioDir, "$id.mp4")

    /** 下载单条视频到本地。已经有了就直接返回。 */
    suspend fun download(track: TrackEntity): Result<File> = runCatching {
        withContext(Dispatchers.IO) { fileMutex.withLock {
            val current = trackDao.byId(track.id) ?: error("视频已被移除，请重新同步")
            val dest = current.localPath?.let(::File) ?: localFile(track.id)
            if (current.localPath == dest.absolutePath && dest.exists() && dest.length() > 0) {
                if (track.localPath == null) trackDao.setLocalPath(track.id, dest.absolutePath)
                return@withLock dest
            }
            if (current.locallyEdited) error("本地剪辑文件已丢失，请恢复原版或重新剪辑")
            if (track.id < 0) error("本机视频文件已丢失，请重新提取")

            val account = AccountStore.forServer(settings.serverUrlNow()) ?: error("请先登录 GetVideo 账号")
            check(current.cloudScope == cloudScopeKey(account.serverUrl, account.userId)) { "旧视频没有可确认的服务器归属，请重新提取" }
            val base = account.serverUrl.trimEnd('/')
            val url = base + current.downloadUrl
            val request = Request.Builder().url(url).build()

            // 先写临时文件，完整了再改名 —— 中断的下载不会留下半个能播的文件
            val tmp = File(dest.absolutePath + ".part")
            ApiProvider.okHttp(account).newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) error("下载失败：HTTP ${resp.code}")
                val body = resp.body ?: error("下载失败：响应为空")
                tmp.outputStream().use { out -> body.byteStream().copyTo(out) }
            }
            val digest = MessageDigest.getInstance("SHA-256")
            tmp.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
            if ((current.audioBytes != null && tmp.length() != current.audioBytes) ||
                (current.audioSha256 != null && actualHash != current.audioSha256)) {
                tmp.delete()
                error("视频内容已变更或下载不完整，请同步后重试")
            }
            checkAccount(account)
            if (!tmp.renameTo(dest)) {
                tmp.delete()
                error("保存失败")
            }
            trackDao.setLocalPath(track.id, dest.absolutePath)
            dest
        } }
    }

    /** A stable snapshot prevents background server downloads from changing an open edit. */
    suspend fun editSource(id: Long): File = withContext(Dispatchers.IO) {
        val track = trackDao.byId(id) ?: error("视频已被删除")
        download(track).getOrThrow()
        fileMutex.withLock {
            val current = trackDao.byId(id) ?: error("视频已被删除")
            val source = current.localPath?.let(::File)?.takeIf { it.exists() } ?: error("视频已更新，请重新打开剪辑")
            val snapshot = File.createTempFile("edit-source-", ".audio", context.cacheDir)
            try { source.copyTo(snapshot, overwrite = true); snapshot }
            catch (e: Exception) { snapshot.delete(); throw e }
        }
    }

    /** Commit a complete export under a unique path before touching the library record. */
    suspend fun saveEdit(id: Long, output: File, title: String, overwrite: Boolean): TrackEntity = withContext(Dispatchers.IO) {
        require(title.isNotBlank()) { "名称不能为空" }
        withContext(NonCancellable) {
            var previousPath: String? = null
            val saved = fileMutex.withLock {
                val original = trackDao.byId(id) ?: error("视频已被删除")
                val newId = if (overwrite) id else generateSequence { -java.util.concurrent.ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE) }
                    .first { trackDao.byId(it) == null }
                val target = File(audioDir, "$newId-edit-${java.util.UUID.randomUUID()}.mp4")
                val duration = com.getvideo.app.audioedit.AudioEditor.duration(output)
                require(duration > 0 && output.length() > 0) { "剪辑结果无效" }
                val digest = MessageDigest.getInstance("SHA-256")
                output.inputStream().use { input -> val buffer = ByteArray(65536); while (true) {
                    val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count)
                } }
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                try { output.copyTo(target) } catch (e: Exception) { target.delete(); throw e }
                if (overwrite) previousPath = original.localPath
                val now = System.currentTimeMillis()
                val record = original.copy(id = newId, title = title.trim(), localTitleOverride = title.trim(),
                    videoId = if (overwrite) original.videoId else "edit-${java.util.UUID.randomUUID()}",
                    localPath = target.absolutePath, locallyEdited = true,
                    sourceTrackId = original.sourceTrackId ?: original.id,
                    durationSec = duration / 1000.0, audioBytes = target.length(), audioFormat = "mp4", audioSha256 = hash,
                    downloadUrl = if (overwrite) original.downloadUrl else "",
                    updatedAtMs = now, addedAtMs = if (overwrite) original.addedAtMs else now,
                    updatedSeq = if (overwrite) original.updatedSeq else now,
                    favorite = if (overwrite) original.favorite else false)
                try { trackDao.upsertAll(listOf(record)) } catch (e: Exception) { target.delete(); throw e }
                record
            }
            withContext(Dispatchers.Main) { PlayerHolder.edited(context, saved) }
            previousPath?.takeIf { it != saved.localPath }?.let { File(it).delete() }
            saved
        }
    }

    /** Explicitly restore the latest server file; never download over an edited file implicitly. */
    suspend fun restoreOriginal(id: Long): TrackEntity = withContext(Dispatchers.IO) {
        require(id > 0) { "此视频没有可恢复的服务器原版" }
        val account = AccountStore.forServer(settings.serverUrlNow()) ?: error("请先登录 GetVideo 账号")
        val current = trackDao.byId(id) ?: error("视频不存在")
        check(current.cloudScope == cloudScopeKey(account.serverUrl, account.userId)) { "旧视频请重新提取" }
        val api = ApiProvider.api(account.serverUrl, account)
        var cursor = 0L
        var original: TrackEntity? = null
        while (original == null) {
            val page = api.sync(cursor)
            original = fileMutex.withLock {
                checkAccount(account)
                page.items.firstOrNull { it.id == current.remoteTrackId && !it.deleted }?.let { scopedEntity(it, account) }
            }
            if (!page.hasMore || page.items.isEmpty()) break
            check(page.nextCursor > cursor) { "同步游标无效" }
            cursor = page.nextCursor
        }
        val fresh = original ?: error("服务器原版已不存在")
        val staged = File.createTempFile("restore-", ".audio", audioDir)
        var committed = false
        try {
            ApiProvider.okHttp(account).newCall(Request.Builder().url(account.serverUrl.trimEnd('/') + fresh.downloadUrl).build()).execute().use { response ->
                check(response.isSuccessful) { "恢复失败：HTTP ${response.code}" }
                val body = response.body ?: error("响应为空")
                staged.outputStream().use { out -> body.byteStream().copyTo(out) }
            }
            val digest = MessageDigest.getInstance("SHA-256")
            staged.inputStream().use { input -> val buffer = ByteArray(65536); while (true) {
                val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count)
            } }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            check(staged.length() > 0 && (fresh.audioBytes == null || staged.length() == fresh.audioBytes) &&
                (fresh.audioSha256 == null || hash == fresh.audioSha256)) { "原版下载不完整，请重试" }
            withContext(NonCancellable) {
                var previousPath: String? = null
                val saved = fileMutex.withLock {
                    checkAccount(account)
                    val current = trackDao.byId(id) ?: error("视频已被删除")
                    previousPath = current.localPath
                    val record = fresh.copy(localPath = staged.absolutePath, favorite = current.favorite,
                        localCategoryId = current.localCategoryId, addedAtMs = current.addedAtMs,
                        title = current.localTitleOverride ?: fresh.title, localTitleOverride = current.localTitleOverride)
                    trackDao.upsertAll(listOf(record)); committed = true; record
                }
                withContext(Dispatchers.Main) {
                    if (accountResponseIsCurrent(account, AccountStore.forServer(settings.serverUrlNow()), settings.serverUrlNow()))
                        PlayerHolder.edited(context, saved)
                }
                previousPath?.takeIf { it != saved.localPath }?.let { File(it).delete() }
                saved
            }
        } catch (e: Exception) { if (!committed) staged.delete(); throw e }
    }

    // ---------- 导入本地视频 ----------

    /**
     * 把系统文件选择器选中的视频复制进应用私有目录并入库。
     *
     * 场景：重装/换机后把之前导出的视频搬回来，或把手里的本地文件加进库。
     * id 由文件内容的 sha256 派生（负数，与云端 id 不冲突），同一个文件重复导入不会产生副本。
     */
    suspend fun importVideo(uris: List<Uri>, onProgress: (String) -> Unit = {}): Result<Int> = runCatching {
        withContext(Dispatchers.IO) {
            var added = 0
            uris.forEachIndexed { index, uri ->
                val name = displayName(uri)?.takeIf { it.isNotBlank() } ?: "未命名视频"
                val title = titleOf(name)
                onProgress("正在导入 ${index + 1}/${uris.size}：$title")
                val staged = File.createTempFile("import-", ".part", context.cacheDir)
                try {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        staged.outputStream().use { out -> input.copyTo(out) }
                    } ?: error("无法读取：$title")
                    if (staged.length() <= 0L) error("文件为空：$title")
                    val hash = sha256(staged)
                    val id = importId(hash)
                    val dest = File(audioDir, "$id.mp4")
                    if (dest.exists() && dest.length() == staged.length()) {
                        onProgress("已在库中：$title")
                        return@forEachIndexed
                    }
                    fileMutex.withLock {
                        if (dest.exists() && !dest.delete()) error("无法覆盖：$title")
                        if (!staged.renameTo(dest)) {
                            staged.copyTo(dest, overwrite = true)
                            staged.delete()
                        }
                        trackDao.upsertLocal(TrackEntity(
                            id = id,
                            platform = "local",
                            videoId = title,
                            title = title,
                            uploader = null,
                            durationSec = durationOf(dest),
                            audioBytes = dest.length(),
                            audioFormat = name.substringAfterLast('.', "mp4").lowercase(),
                            sourceUrl = "",
                            downloadUrl = "",
                            updatedSeq = System.currentTimeMillis(),
                            audioSha256 = hash,
                            localPath = dest.absolutePath,
                            cloudScope = "local",
                        ))
                    }
                    added++
                } finally {
                    staged.delete()
                }
            }
            onProgress("导入完成：$added 个")
            added
        }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    private fun titleOf(name: String): String =
        name.substringAfterLast('/').substringBeforeLast('.').ifBlank { name }.trim()

    private fun durationOf(file: File): Double? = runCatching {
        val retriever = android.media.MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toDoubleOrNull()?.div(1000.0)
        } finally {
            retriever.release()
        }
    }.getOrNull()

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun importId(hash: String): Long {
        val bytes = MessageDigest.getInstance("SHA-256").digest("import:$hash".toByteArray())
        val positive = java.nio.ByteBuffer.wrap(bytes).long and Long.MAX_VALUE
        return -(positive.takeIf { it > 0 } ?: 1L)
    }

    /** 批量补齐未下载的视频，返回成功条数。给 WorkManager 用。 */
    suspend fun downloadPending(limit: Int = 20): Int {
        if (settings.serverUrlNow().isBlank()) return 0
        var ok = 0
        trackDao.notDownloaded(limit).forEach { track ->
            if (download(track).isSuccess) ok++
        }
        return ok
    }
    private suspend fun checkAccount(account: AccountStore.Account) {
        check(AccountStore.forServer(settings.serverUrlNow()) == account) { "账号或服务器已切换，请重试" }
    }

    private suspend fun scopedEntity(dto: TrackDto, account: AccountStore.Account): TrackEntity = db.withTransaction {
        val scope = cloudScopeKey(account.serverUrl, account.userId)
        val existing = trackDao.mapping(scope, dto.id)
        val id = existing?.localId ?: chooseCloudLocalId(dto.id, trackDao.usedIds().toSet()).also {
            trackDao.addMapping(com.getvideo.app.data.local.CloudMappingEntity(it, scope, dto.id))
        }
        dto.toEntity().copy(id = id, cloudScope = scope, remoteTrackId = dto.id)
    }

}

private fun TrackDto.toEntity() = TrackEntity(
    id = id,
    platform = platform,
    videoId = videoId,
    title = title,
    uploader = uploader,
    durationSec = durationSec,
    audioBytes = audioBytes,
    audioFormat = audioFormat,
    sourceUrl = sourceUrl,
    downloadUrl = downloadUrl,
    updatedSeq = updatedSeq,
    updatedAtMs = updatedAtMs.takeIf { it > 0 } ?: System.currentTimeMillis(),
    audioSha256 = audioSha256,
    categories = categories.joinToString(" · "),
    hasVocal = hasVocal,
    classifyStage = classifyStage,
)
