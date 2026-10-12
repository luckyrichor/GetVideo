package com.getvideo.app

import android.app.Instrumentation
import android.os.Bundle
import com.getvideo.app.data.local.*
import com.getvideo.app.data.repo.TrackRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin

/** Uses only synthetic negative IDs and fixture files; never deletes a user's tracks. */
class BatchDeleteInstrumentation : Instrumentation() {
    private var mode = "test"
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); mode = arguments?.getString("mode") ?: "test"; start() }
    override fun onStart() {
        val report = Bundle()
        try {
            runBlocking {
                val context = targetContext
                if (mode == "diagnostics") {
                    val vm = withContext(Dispatchers.Main) { com.getvideo.app.ui.screen.ExtractViewModel(TrackRepository(context), BuildConfig.VERSION_NAME).apply { input = "invalid"; submit() } }
                    while (withContext(Dispatchers.Main) { vm.busy }) delay(50)
                    withContext(Dispatchers.Main) { vm.submitViaServer() }
                    while (withContext(Dispatchers.Main) { vm.busy }) delay(50)
                    val text = com.getvideo.app.data.repo.DiagnosticsStore.exportFailures()
                    check(text.contains("手机直提取") && text.contains("服务器提取")) { "Failure tasks missing" }
                    check(text.contains("提取失败") && text.contains("服务器提取失败")) { "Failure reasons missing" }
                    report.putString("stream", "Diagnostic tasks PASS: local and server failures persisted with reasons\n")
                    return@runBlocking
                }
                val db = AppDatabase.get(context)
                val ids = setOf(-90000001L, -90000002L)
                val playlistDao = db.playlistDao()
                if (mode != "check") {
                    withContext(Dispatchers.IO) {
                        ids.forEachIndexed { index, id ->
                            check(db.trackDao().byId(id) == null) { "Synthetic ID already exists; abort to preserve existing audio" }
                            val file = File(context.filesDir, "audio/batchcheck${index + 1}.wav")
                            writeTone(file, index + 1)
                            db.trackDao().upsertLocal(TrackEntity(id, "local", "batchcheck${index + 1}",
                                "BatchCheck ${if (index == 0) "A" else "B"}", "验证视频", (index + 1).toDouble(),
                                file.length(), "wav", "", "", 0, audioSha256 = "fixture-$index", localPath = file.path))
                        }
                        val list = playlistDao.insert(PlaylistEntity(name = "BatchCheck"))
                        playlistDao.add(ids.mapIndexed { index, id -> PlaylistItemEntity(list, id, orderIndex = index) })
                        playlistDao.firstId()?.let { playlistDao.add(listOf(PlaylistItemEntity(it, ids.first(), orderIndex = 999))) }
                    }
                    if (mode == "test") {
                        // Cancellation while awaiting the file lock must still complete the confirmed deletion.
                        val field = TrackRepository::class.java.getDeclaredField("fileMutex").apply { isAccessible = true }
                        val lock = field.get(null) as Mutex
                        lock.lock()
                        val deleting = launch { TrackRepository(context).removeAll(ids) }
                        delay(100)
                        deleting.cancel()
                        lock.unlock()
                        deleting.join()
                    }
                }
                if (mode != "create") withContext(Dispatchers.IO) {
                    check(ids.all { db.trackDao().byId(it) == null }) { "Metadata remains" }
                    val references = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM playlist_items WHERE trackId IN (-90000001,-90000002)").use { it.moveToFirst(); it.getInt(0) }
                    check(references == 0) { "Playlist references remain" }
                    check((1..2).none { File(context.filesDir, "audio/batchcheck$it.wav").exists() }) { "Files remain" }
                    db.openHelper.writableDatabase.execSQL("DELETE FROM playlists WHERE name='BatchCheck'")
                }
            }
            if (mode != "diagnostics") report.putString("stream", "BatchDelete $mode PASS: metadata, references and files verified\n")
            finish(RESULT_OK, report)
        } catch (error: Throwable) {
            report.putString("stream", "BatchDelete $mode FAIL: ${error.message}\n")
            finish(RESULT_CANCELED, report)
        }
    }
    private fun writeTone(file: File, seconds: Int) {
        file.parentFile?.mkdirs()
        val samples = 44100 * seconds
        val buffer = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(44100).putInt(88200).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(samples * 2)
        repeat(samples) { buffer.putShort((sin(it * 2 * Math.PI * (660 + seconds * 110) / 44100) * 2000).toInt().toShort()) }
        file.writeBytes(buffer.array())
    }
    companion object { private const val RESULT_OK = -1; private const val RESULT_CANCELED = 0 }
}
