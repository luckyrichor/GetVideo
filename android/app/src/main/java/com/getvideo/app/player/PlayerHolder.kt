package com.getvideo.app.player

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.getvideo.app.data.local.TrackEntity
import com.getvideo.app.data.repo.PlaylistRepository
import com.getvideo.app.data.repo.TrackRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 连接到 [PlaybackService] 的控制器。
 *
 * 整个 App 共用一个 —— 多个控制器会各自持有播放状态，界面之间就对不上了。
 */
object PlayerHolder {

    enum class Mode(val label: String, val description: String) {
        LOOP("列表循环", "按列表顺序播放，结束后从头继续"),
        SINGLE("单视频循环", "重复播放当前视频"),
        ORDERED("顺序播放", "按列表顺序播放，最后一个结束后停止"),
        SHUFFLE("随机播放", "随机播放列表中的视频")
    }

    data class QueueItem(val track: TrackEntity, val path: String)
    private val queueState = MutableStateFlow<List<QueueItem>>(emptyList())
    val queue = queueState.asStateFlow()
    private val sourcePlaylistIdState = MutableStateFlow<Long?>(null)
    val sourcePlaylistId = sourcePlaylistIdState.asStateFlow()
    private val indexState = MutableStateFlow(0)
    val currentIndex = indexState.asStateFlow()
    private val playingState = MutableStateFlow(false)
    val isPlaying = playingState.asStateFlow()
    private val modeState = MutableStateFlow(Mode.ORDERED)
    val mode = modeState.asStateFlow()
    private val timerDeadlineState = MutableStateFlow<Long?>(null)
    val timerDeadline = timerDeadlineState.asStateFlow()
    private val waitingForTrackEndState = MutableStateFlow(false)
    val waitingForTrackEnd = waitingForTrackEndState.asStateFlow()
    private val timerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var timerJob: Job? = null
    private val appendMutex = Mutex()

    @Volatile
    private var controller: MediaController? = null

    suspend fun get(context: Context): MediaController {
        controller?.let { if (it.isConnected) return it }
        return connect(context).also { connected ->
            controller = connected
            connected.addListener(object : Player.Listener {
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    com.getvideo.app.data.repo.DiagnosticsStore.event("视频播放", "播放器错误：${error.errorCodeName}", false)
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    playingState.value = isPlaying
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    val previousIndex = indexState.value
                    if (waitingForTrackEndState.value &&
                        (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                            reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT)) {
                        waitingForTrackEndState.value = false
                        connected.pause()
                        if (previousIndex in queueState.value.indices) connected.seekTo(previousIndex, 0)
                        timerScope.launch { applyMode(context) }
                    }
                    indexState.value = connected.currentMediaItemIndex.coerceAtLeast(0)
                }

                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED && waitingForTrackEndState.value) {
                        connected.pause()
                        waitingForTrackEndState.value = false
                        timerScope.launch { applyMode(context) }
                    }
                }
            })
        }
    }

    private suspend fun connect(context: Context): MediaController =
        suspendCancellableCoroutine { cont ->
            val token = SessionToken(
                context.applicationContext,
                ComponentName(context.applicationContext, PlaybackService::class.java),
            )
            val future = MediaController.Builder(context.applicationContext, token).buildAsync()
            future.addListener(
                { cont.resume(future.get()) },
                MoreExecutors.directExecutor(),
            )
        }

    suspend fun play(context: Context, track: TrackEntity, localPath: String) {
        playAll(context, listOf(QueueItem(track, localPath)))
    }

    private fun mediaItem(item: QueueItem) = MediaItem.Builder()
        .setUri(item.path)
        .setMediaId(item.track.id.toString())
        .setMediaMetadata(MediaMetadata.Builder().setTitle(item.track.title)
            .setArtist(item.track.uploader ?: item.track.platform).build())
        .build()

    suspend fun playAll(context: Context, items: List<QueueItem>, playlistId: Long? = null) = appendMutex.withLock {
        val existingIds = TrackRepository(context).allNow().map { it.id }.toSet()
        val ids = newQueueTracks(emptyList(), items.map { it.track }).map { it.id }.toSet()
        val unique = items.distinctBy { it.track.id }.filter { it.track.id in ids && it.track.id in existingIds }
        if (unique.isEmpty()) return@withLock
        val lists = PlaylistRepository(context)
        val sourceId = playlistId ?: lists.ensureDefault()
        if (playlistId == null) lists.replaceTracks(sourceId, unique.map { it.track.id })
        sourcePlaylistIdState.value = sourceId
        queueState.value = unique
        indexState.value = 0
        get(context).run {
            setMediaItems(unique.map(::mediaItem), 0, 0L)
            prepare()
            play()
        }
        applyMode(context)
    }

    suspend fun append(context: Context, items: List<QueueItem>): Int = appendMutex.withLock {
        appendLocked(context, items)
    }

    private suspend fun appendLocked(context: Context, items: List<QueueItem>): Int {
        if (items.isEmpty()) return 0
        val repository = TrackRepository(context)
        check(items.all { repository.byId(it.track.id) != null }) { "视频已移除或账号已切换" }
        val player = get(context)
        val lists = PlaylistRepository(context)
        val sourceId = lists.resolve(sourcePlaylistIdState.value)
        if (queueState.value.isEmpty()) {
            val saved = lists.tracks(sourceId).map { track ->
                val path = track.localPath?.takeIf { File(it).exists() }
                    ?: TrackRepository(context).download(track).getOrThrow().absolutePath
                QueueItem(track, path)
            }
            val restoredIds = newQueueTracks(emptyList(), saved.map { it.track }).map { it.id }.toSet()
            val restored = saved.filter { it.track.id in restoredIds }.distinctBy { it.track.id }
            queueState.value = restored
            sourcePlaylistIdState.value = sourceId
            indexState.value = 0
            if (restored.isNotEmpty()) {
                player.setMediaItems(restored.map(::mediaItem), 0, 0L)
                player.prepare()
            } else player.clearMediaItems()
        }
        val acceptedIds = newQueueTracks(queueState.value.map { it.track }, items.map { it.track }).map { it.id }.toSet()
        val additions = items.distinctBy { it.track.id }.filter { it.track.id in acceptedIds }
        if (additions.isEmpty()) return 0
        lists.addTracks(sourceId, additions.map { it.track.id })
        // Recheck after database suspension so rapid repeated taps cannot enqueue duplicates.
        val finalIds = newQueueTracks(queueState.value.map { it.track }, additions.map { it.track }).map { it.id }.toSet()
        val finalItems = additions.filter { it.track.id in finalIds }
        if (finalItems.isEmpty()) return 0
        val wasEmpty = queueState.value.isEmpty()
        sourcePlaylistIdState.value = sourceId
        if (wasEmpty) {
            // Media3 1.3.1 addMediaItems after an empty timeline can retain INDEX_UNSET (-1).
            // Explicitly start the first item at zero; append preserves an existing queue's position.
            player.setMediaItems(finalItems.map(::mediaItem), 0, 0L)
            player.prepare()
            indexState.value = 0
        } else player.addMediaItems(finalItems.map(::mediaItem))
        queueState.value = queueState.value + finalItems
        return finalItems.size
    }

    /** Play a library selection inside the current list, keeping all its existing entries. */
    suspend fun playSelection(context: Context, items: List<QueueItem>) {
        if (items.isEmpty()) return
        append(context, items)
        val selected = items.first().track
        val position = queueState.value.indexOfFirst { item ->
            newQueueTracks(listOf(item.track), listOf(selected)).isEmpty()
        }
        if (position >= 0) jump(context, position)
    }

    suspend fun jump(context: Context, index: Int) {
        if (index !in queueState.value.indices) return
        get(context).run { seekTo(index, 0); play() }
        indexState.value = index
    }

    /**
     * 手动切下一个，遵循当前的播放方式：
     * 随机 → 随机挑一个别的；单曲循环 → 也换（用户主动点了「下一个」就是要换）；
     * 列表循环 / 顺序播放 → 顺次下一个，到末尾回到第一个。
     */
    suspend fun next(context: Context) {
        val list = queueState.value
        if (list.isEmpty()) return
        if (list.size == 1) { jump(context, 0); return }
        val current = indexState.value.coerceIn(0, list.lastIndex)
        val target = if (modeState.value == Mode.SHUFFLE) {
            list.indices.filter { it != current }.random()
        } else {
            (current + 1) % list.size
        }
        jump(context, target)
    }

    /** Move existing Media3 items: the current video, position and paused/playing state survive. */
    suspend fun reorder(context: Context, playlistId: Long?, trackIds: List<Long>): Boolean = appendMutex.withLock {
        if (playlistId == null || playlistId != sourcePlaylistIdState.value) return@withLock false
        val original = queueState.value
        val moves = queueReorderMoves(original.map { it.track.id }, trackIds) ?: return@withLock false
        val player = get(context)
        if (player.mediaItemCount != original.size || original.indices.any {
            player.getMediaItemAt(it).mediaId != original[it].track.id.toString()
        }) return@withLock false
        // Once the transaction starts, finish applying it even if the dialog is dismissed.
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            if (!PlaylistRepository(context).reorder(playlistId, trackIds)) return@withContext false
            moves.forEach { (from, to) -> player.moveMediaItem(from, to) }
            // A concurrent rename may have refreshed metadata while Room was saving.
            val byId = queueState.value.associateBy { it.track.id }
            queueState.value = trackIds.map { byId.getValue(it) }
            indexState.value = player.currentMediaItemIndex.coerceAtLeast(0)
            true
        }
    }

    suspend fun remove(context: Context, index: Int) {
        if (index !in queueState.value.indices) return
        val removed = queueState.value[index]
        sourcePlaylistIdState.value?.let { PlaylistRepository(context).removeTrack(it, removed.track.id) }
        get(context).removeMediaItem(index)
        queueState.value = queueState.value.toMutableList().also { it.removeAt(index) }
        indexState.value = get(context).currentMediaItemIndex.coerceAtLeast(0)
    }

    /** Deleting a saved list removes references, never library audio files. */
    suspend fun deletePlaylist(context: Context, id: Long) = appendMutex.withLock {
        val lists = PlaylistRepository(context)
        val current = lists.resolve(sourcePlaylistIdState.value)
        lists.delete(id)
        if (current == id) {
            get(context).run { stop(); clearMediaItems() }
            queueState.value = emptyList()
            indexState.value = 0
            playingState.value = false
            sourcePlaylistIdState.value = lists.ensureDefault()
            cancelTimer()
        }
    }

    /** Library deletion removes every occurrence without restarting surviving audio. */
    suspend fun removeTracks(context: Context, deletedIds: Set<Long>) = appendMutex.withLock {
        val remaining = queueState.value.toMutableList()
        val indices = queueRemovalIndices(remaining.map { it.track.id }, deletedIds)
        if (indices.isEmpty()) return@withLock
        val player = get(context)
        indices.forEach { index ->
            player.removeMediaItem(index)
            remaining.removeAt(index)
        }
        queueState.value = remaining
        indexState.value = player.currentMediaItemIndex.coerceAtLeast(0)
        if (remaining.isEmpty()) {
            player.pause()
            playingState.value = false
            indexState.value = 0
            cancelTimer()
        }
    }

    /** 更新队列及系统媒体信息；仅替换元数据，保留音源和播放进度。 */
    suspend fun rename(context: Context, id: Long, title: String) {
        val oldQueue = queueState.value
        if (oldQueue.none { it.track.id == id }) return
        val updated = oldQueue.map { item ->
            if (item.track.id == id) item.copy(track = item.track.copy(title = title)) else item
        }
        val player = get(context)
        updated.forEachIndexed { index, item ->
            if (item.track.id == id && index < player.mediaItemCount) {
                val original = player.getMediaItemAt(index)
                player.replaceMediaItem(index, original.buildUpon().setMediaMetadata(
                    original.mediaMetadata.buildUpon().setTitle(title).build()
                ).build())
            }
        }
        queueState.value = updated
    }

    suspend fun edited(context: Context, track: TrackEntity) = appendMutex.withLock {
        val old = queueState.value
        if (old.none { it.track.id == track.id }) return@withLock
        val player = get(context)
        val current = player.currentMediaItemIndex
        val updated = old.map { if (it.track.id == track.id) QueueItem(track, track.localPath!!) else it }
        if (old.getOrNull(current)?.track?.id == track.id) player.pause()
        updated.forEachIndexed { index, item ->
            if (item.track.id == track.id) player.replaceMediaItem(index, mediaItem(item))
        }
        if (old.getOrNull(current)?.track?.id == track.id) player.seekTo(current, 0)
        queueState.value = updated
    }

    suspend fun toggle(context: Context) {
        get(context).run { if (isPlaying) pause() else play() }
    }

    suspend fun setMode(context: Context, value: Mode) {
        modeState.value = value
        applyMode(context)
    }

    private suspend fun applyMode(context: Context) {
        get(context).run {
            shuffleModeEnabled = modeState.value == Mode.SHUFFLE
            repeatMode = playbackRepeatMode(modeState.value, waitingForTrackEndState.value)
        }
    }

    fun setTimer(context: Context, minutes: Long, finishTrack: Boolean) {
        timerJob?.cancel()
        val deadline = android.os.SystemClock.elapsedRealtime() + minutes * 60_000L
        timerDeadlineState.value = deadline
        waitingForTrackEndState.value = false
        timerJob = timerScope.launch {
            applyMode(context)
            delay(minutes * 60_000L)
            if (timerDeadlineState.value == deadline) {
                timerDeadlineState.value = null
                if (finishTrack && get(context).isPlaying) {
                    waitingForTrackEndState.value = true
                    applyMode(context)
                } else get(context).pause()
            }
        }
    }

    fun cancelTimer() {
        timerJob?.cancel()
        timerDeadlineState.value = null
        waitingForTrackEndState.value = false
        controller?.let { it.repeatMode = playbackRepeatMode(modeState.value, false) }
    }

    fun release() {
        controller?.release()
        controller = null
    }
}
