package com.getvideo.app

import android.app.Instrumentation
import android.content.ContextWrapper
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.getvideo.app.data.localextract.DirectExtractor
import com.getvideo.app.data.remote.*
import com.getvideo.app.data.repo.*
import kotlinx.coroutines.*
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

/** Opt-in network acceptance with an isolated fixture account; never replaces the user's session. */
class PublicPathNetworkInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        waitForIdleSync()
        val report = Bundle()
        val lines = mutableListOf<String>()
        val scratch = File(targetContext.cacheDir, "https-path-network-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            runBlocking(Dispatchers.IO) {
                val base = Settings(targetContext).serverUrlNow()
                check(base == "https://43.142.37.199/getvideo/")
                val original = AccountStore.current.value
                val credentials = AccountCredentials("199" + SecureRandom().nextInt(100_000_000).toString().padStart(8, '0'), UUID.randomUUID().toString())
                val registered = ApiProvider.api(base).accountRegister(credentials)
                // Only this disposable ID is reported, for scoped server-side cleanup. No credentials.
                lines += "fixture_id=${registered.userId}"
                val session = ApiProvider.api(base).accountLogin(credentials)
                check(session.userId == registered.userId)
                val fixture = AccountStore.Account(session.userId, session.phone, session.sessionToken, base)
                val api = ApiProvider.api(base, fixture)
                try {
                    check(api.accountMe().userId == fixture.userId)
                    lines += "phone registration and login: passed; original account unchanged"
                    val extracted = api.extract(ExtractRequest("https://www.bilibili.com/video/BV1ujNV6qEXg")).track
                    lines += "phone request for server extraction: passed"
                    val url = serverResourceUrl(base, extracted.downloadUrl)
                    val downloaded = File(scratch, "server.mp4")
                    ApiProvider.okHttp(fixture).newCall(Request.Builder().url(url).build()).execute().use {
                        check(it.isSuccessful)
                        downloaded.outputStream().use { out -> checkNotNull(it.body).byteStream().use { input -> input.copyTo(out) } }
                    }
                    val hash = MessageDigest.getInstance("SHA-256").digest(downloaded.readBytes()).joinToString("") { "%02x".format(it) }
                    check(hash == extracted.audioSha256 && downloaded.length() == extracted.audioBytes)
                    ApiProvider.okHttp(fixture).newCall(Request.Builder().url(url).header("Range", "bytes=0-1023").build()).execute().use {
                        check(it.code == 206 && checkNotNull(it.body).bytes().size == 1024)
                    }
                    lines += "phone authenticated video download, SHA-256 and Range: passed"
                    checkPlayback(downloaded)
                    lines += "downloaded server video playback on phone: passed"
                    val isolated = object : ContextWrapper(targetContext) {
                        override fun getFilesDir() = File(scratch, "files").apply { mkdirs() }
                        override fun getCacheDir() = File(scratch, "cache").apply { mkdirs() }
                    }
                    val direct = DirectExtractor(isolated).extract("https://www.bilibili.com/video/BV1ujNV6qEXg")
                    check(direct.durationSec!! > 0 && File(direct.localPath!!).length() > 1024)
                    lines += "phone direct Bilibili extraction: passed"
                    checkPlayback(File(direct.localPath!!))
                    lines += "phone direct extracted video playback: passed"
                } finally { runCatching { api.accountLogout() } }
                check(AccountStore.current.value == original) { "User account changed during fixture test" }
                lines += "original user session preserved"
            }
            report.putString("stream", lines.joinToString("\n") + "\nPublicPathNetwork PASS\n")
            finish(-1, report)
        } catch (error: Throwable) {
            report.putString("stream", lines.joinToString("\n") + "\nPublicPathNetwork FAIL: ${ApiProvider.humanMessage(error)}\n")
            finish(0, report)
        } finally { scratch.deleteRecursively() }
    }

    private suspend fun checkPlayback(file: File) {
        var player: ExoPlayer? = null
        try {
            withContext(Dispatchers.Main) {
                player = ExoPlayer.Builder(targetContext).build().apply {
                    volume = 0f
                    setMediaItem(MediaItem.fromUri(file.toURI().toString()))
                    prepare(); play()
                }
            }
            withTimeout(20_000) {
                while (true) {
                    val advanced = withContext(Dispatchers.Main) {
                        player!!.playerError?.let { throw it }
                        player!!.currentPosition > 500
                    }
                    if (advanced) break
                    delay(100)
                }
            }
        } finally { withContext(Dispatchers.Main) { player?.release() } }
    }
}
