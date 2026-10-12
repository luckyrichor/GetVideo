package com.getvideo.app

import android.app.Instrumentation
import android.os.Bundle
import com.getvideo.app.data.local.*
import com.getvideo.app.data.repo.TrackRepository
import com.getvideo.app.player.PlayerHolder
import kotlinx.coroutines.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin

/** Reproduces the first library play against an empty saved playlist and empty player timeline. */
class FirstPlaybackInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val report = Bundle()
        try {
            runBlocking {
                val context = targetContext
                val db = AppDatabase.get(context)
                val id = -90000003L
                val previousDefault = db.playlistDao().firstId()
                check(db.trackDao().byId(id) == null) { "Fixture ID exists" }
                val file = File(context.filesDir, "audio/first-playback-check.wav")
                file.parentFile?.mkdirs()
                val size = 44100 * 20
                val tone = ByteBuffer.allocate(44 + size * 2).order(ByteOrder.LITTLE_ENDIAN)
                tone.put("RIFF".toByteArray()).putInt(36 + size * 2).put("WAVEfmt ".toByteArray())
                    .putInt(16).putShort(1).putShort(1).putInt(44100).putInt(88200).putShort(2).putShort(16)
                    .put("data".toByteArray()).putInt(size * 2)
                repeat(size) { tone.putShort((sin(it * 2 * Math.PI * 660 / 44100) * 1000).toInt().toShort()) }
                file.writeBytes(tone.array())
                val track = TrackEntity(id, "local", "first-playback-check", "First playback check", "验证", 20.0,
                    file.length(), "wav", "", "", 0, localPath = file.path)
                val listId = db.playlistDao().insert(PlaylistEntity(name = "First playback check"))
                db.playlistDao().markDefault(listId)
                db.trackDao().upsertLocal(track)
                try {
                    withContext(Dispatchers.Main) {
                        val player = PlayerHolder.get(context)
                        player.volume = 0f
                        player.stop(); player.clearMediaItems()
                        PlayerHolder.playSelection(context, listOf(PlayerHolder.QueueItem(track, file.path)))
                    }
                    delay(1000)
                    withContext(Dispatchers.Main) {
                        val player = PlayerHolder.get(context)
                        check(player.mediaItemCount == 1 && player.currentMediaItemIndex == 0) { "Invalid first selection" }
                        check(player.isPlaying) { "First selection did not start playing" }
                    }
                    report.putString("stream", "FirstPlayback PASS: first selection immediately plays at index 0\n")
                } finally {
                    withContext(Dispatchers.Main) { PlayerHolder.get(context).run { pause(); clearMediaItems(); volume = 1f } }
                    TrackRepository(context).removeAll(setOf(id))
                    db.playlistDao().delete(listId)
                    previousDefault?.let { db.playlistDao().markDefault(it) }
                    file.delete()
                }
            }
            finish(-1, report)
        } catch (error: Throwable) {
            report.putString("stream", "FirstPlayback FAIL:\n${error.stackTraceToString()}\n")
            finish(0, report)
        }
    }
}
