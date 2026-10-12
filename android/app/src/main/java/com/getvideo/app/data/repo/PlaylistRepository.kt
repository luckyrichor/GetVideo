package com.getvideo.app.data.repo

import android.content.Context
import androidx.room.withTransaction
import com.getvideo.app.data.local.AppDatabase
import com.getvideo.app.data.local.PlaylistEntity
import com.getvideo.app.data.local.PlaylistItemEntity
import com.getvideo.app.player.newQueueTracks

class PlaylistRepository(context: Context) {
    private val db = AppDatabase.get(context)
    private val dao = db.playlistDao()
    private val categoryRepo = CategoryRepository(context)

    fun observeAll() = dao.observeAll()
    fun items(id: Long) = dao.items(id)

    suspend fun create(name: String): Long = db.withTransaction {
        dao.insert(PlaylistEntity(name = playlistName(name, dao.names())))
    }

    suspend fun ensureDefault(): Long = db.withTransaction {
        dao.firstId() ?: dao.insert(PlaylistEntity(name = playlistName("", dao.names()), isDefault = true))
    }

    suspend fun resolve(id: Long?): Long = id?.takeIf { dao.byId(it) != null } ?: ensureDefault()

    suspend fun tracks(id: Long) = dao.itemsNow(id).mapNotNull { db.trackDao().byId(it.trackId) }

    suspend fun replaceTracks(id: Long, trackIds: List<Long>) = db.withTransaction {
        dao.clear(id)
        addTracks(id, trackIds)
    }

    suspend fun removeTrack(id: Long, trackId: Long) = dao.removeItem(id, trackId)

    suspend fun rename(id: Long, name: String) {
        require(name.isNotBlank()) { "列表名称不能为空" }
        require(dao.byId(id) != null) { "播放列表不存在" }
        dao.rename(id, name.trim())
    }

    suspend fun delete(id: Long) = db.withTransaction {
        val wasDefault = dao.byId(id)?.isDefault == true
        dao.clear(id)
        dao.delete(id)
        if (wasDefault) {
            val replacement = dao.oldestId() ?: dao.insert(PlaylistEntity(name = playlistName("", dao.names()), isDefault = true))
            dao.markDefault(replacement)
        }
    }

    suspend fun addTracks(playlistId: Long, trackIds: List<Long>) = db.withTransaction {
        require(dao.byId(playlistId) != null) { "播放列表不存在" }
        val tracks = db.trackDao().allNow().associateBy { it.id }
        val existing = dao.itemsNow(playlistId).mapNotNull { tracks[it.trackId] }
        val additions = newQueueTracks(existing, trackIds.mapNotNull { tracks[it] })
        val start = dao.lastOrder(playlistId) + 1
        dao.add(additions.mapIndexed { index, track ->
            PlaylistItemEntity(playlistId, track.id, orderIndex = start + index)
        })
    }

    suspend fun addCategory(playlistId: Long, categoryId: Long) =
        addTracks(playlistId, categoryRepo.recursiveTracks(categoryId).map { it.id })
}
