package com.getvideo.app

import android.app.Instrumentation
import android.os.Bundle
import android.util.AtomicFile
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.getvideo.app.data.local.AppDatabase
import com.getvideo.app.data.repo.*
import kotlinx.coroutines.*
import java.io.File

/** Explicit opt-in check on the real release: never prints tokens, phone numbers or passwords. */
class PublicPathInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        // Instrumentation.start() races Application.onCreate(); wait until initialization completes.
        waitForIdleSync()
        val report = Bundle()
        val lines = mutableListOf<String>()
        try {
            runBlocking {
                val context = targetContext
                val base = Settings(context).serverUrlNow()
                check(base == "https://43.142.37.199/getvideo/") { "Unexpected service base" }
                lines += "service base: new HTTPS project path"
                val account = AccountStore.forServer(base) ?: error("Login required on this phone")
                probeServerHealth(base, ApiProvider.okHttp())
                val api = ApiProvider.api(base, account)
                check(api.accountMe().userId == account.userId) { "Session identity changed" }
                lines += "existing account session: authenticated on new entry"
                val dao = AppDatabase.get(context).trackDao()
                val before = dao.allNow()
                val files = before.mapNotNull { it.localPath }.associateWith { File(it).length() }
                TrackRepository(context).sync()
                check(files.all { (path, size) -> File(path).isFile && File(path).length() == size }) { "Existing media changed" }
                lines += "sync: completed; ${files.size} existing media files preserved"
                val platforms = api.platformLoginCapabilities()
                lines += platforms.joinToString("; ") { "${it.platform} authorization available=${it.available}" }
                api.platformAccountBindings()
                lines += "platform bindings: readable"
                // Test Android 8's interrupted AtomicFile state using a disposable fixture only.
                val fixture = File(context.cacheDir, "public-path-atomic-check")
                val atomic = AtomicFile(fixture)
                try {
                    fixture.delete()
                    File(fixture.path + ".bak").writeText("preserved-session-fixture")
                    check(atomic.openRead().use { it.readBytes().toString(Charsets.UTF_8) } == "preserved-session-fixture")
                    lines += "AtomicFile backup-only recovery: passed"
                } finally { atomic.delete(); File(fixture.path + ".bak").delete() }
                val local = before.firstOrNull { it.localPath?.let { p -> File(p).isFile } == true }
                    ?: error("No existing local video for playback check")
                var player: ExoPlayer? = null
                try {
                    withContext(Dispatchers.Main) {
                        player = ExoPlayer.Builder(context).build().apply {
                            volume = 0f
                            setMediaItem(MediaItem.fromUri(File(local.localPath!!).toURI().toString()))
                            prepare(); play()
                        }
                    }
                    withTimeout(15_000) {
                        while (true) {
                            val advanced = withContext(Dispatchers.Main) {
                                player!!.playerError?.let { throw it }
                                player!!.currentPosition > 500
                            }
                            if (advanced) break
                            delay(100)
                        }
                    }
                    lines += "existing local video playback: position advanced"
                } finally { withContext(Dispatchers.Main) { player?.release() } }
            }
            report.putString("stream", lines.joinToString("\n") + "\nPublicPath PASS\n")
            finish(-1, report)
        } catch (error: Throwable) {
            report.putString("stream", lines.joinToString("\n") + "\nPublicPath FAIL: ${ApiProvider.humanMessage(error)}\n")
            finish(0, report)
        }
    }
}
