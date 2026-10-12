package com.getvideo.app.data.repo

import android.content.Context
import androidx.room.withTransaction
import com.getvideo.app.data.local.AppDatabase
import com.getvideo.app.data.local.CategoryEntity
import com.getvideo.app.data.local.TrackEntity
import java.io.File
import com.getvideo.app.player.PlayerHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

class CategoryRepository(private val context: Context) {
    private val db = AppDatabase.get(context)
    private val categories = db.categoryDao()
    private val tracks = db.trackDao()
    private val playlists = db.playlistDao()

    fun observeAll() = categories.observeAll()
    fun frequent() = categories.frequent()
    suspend fun allNow() = categories.allNow()

    suspend fun create(name: String, parentId: Long? = null): Long = db.withTransaction {
        require(name.isNotBlank()) { "类别名称不能为空" }
        if (parentId != null) {
            val parent = categories.byId(parentId) ?: error("上级类别不存在")
            require(parent.kind != "LEAF") { "该类别已装有视频，不能再建子类别" }
            if (parent.kind == null) categories.update(parent.copy(kind = "CONTAINER"))
        }
        categories.insert(CategoryEntity(name = name.trim(), parentId = parentId))
    }

    suspend fun rename(id: Long, name: String) = db.withTransaction {
        require(id != 1L) { "不能重命名系统类别" }
        require(name.isNotBlank()) { "类别名称不能为空" }
        val category = categories.byId(id) ?: error("类别不存在")
        categories.update(category.copy(name = name.trim()))
    }

    suspend fun moveCategory(id: Long, parentId: Long?) = db.withTransaction {
        require(id != 1L) { "不能移动系统类别" }
        val category = categories.byId(id) ?: error("类别不存在")
        if (parentId != null) {
            var current: Long? = parentId
            while (current != null) {
                require(current != id) { "不能把类别移到自身或子类别下" }
                current = (categories.byId(current) ?: error("上级类别不存在")).parentId
            }
            val parent = categories.byId(parentId) ?: error("上级类别不存在")
            require(parent.kind != "LEAF") { "目标类别已装有视频" }
            if (parent.kind == null) categories.update(parent.copy(kind = "CONTAINER"))
        }
        categories.update(category.copy(parentId = parentId))
    }

    suspend fun moveTrack(trackId: Long, categoryId: Long) = db.withTransaction {
        require(tracks.byId(trackId) != null) { "视频不存在" }
        val target = categories.byId(categoryId) ?: error("目标类别不存在")
        require(target.kind != "CONTAINER") { "目标类别装有子类别，不能放视频" }
        if (target.kind == null) categories.update(target.copy(kind = "LEAF"))
        tracks.move(trackId, categoryId, System.currentTimeMillis())
    }

    suspend fun recordAccess(id: Long) = categories.recordAccess(id, System.currentTimeMillis())

    suspend fun recursiveTracks(id: Long): List<TrackEntity> {
        val ids = descendants(id, categories.allNow())
        return tracks.allNow().filter { it.localCategoryId in ids }
    }

    suspend fun delete(id: Long): Int {
        require(id != 1L) { "不能删除系统类别" }
        val files = mutableListOf<File>()
        val deletedIds = mutableSetOf<Long>()
        val count = db.withTransaction {
            val all = categories.allNow()
            require(all.any { it.id == id }) { "类别不存在" }
            val ids = descendants(id, all)
            val affected = tracks.allNow().filter { it.localCategoryId in ids }
            deletedIds.addAll(affected.map { it.id })
            affected.forEach { track ->
                if (track.id > 0) tracks.suppress(track.id)
                playlists.removeTrack(track.id)
                tracks.delete(track.id)
                track.localPath?.let { files.add(File(it)) }
            }
            // 子类别先删，避免以后加入外键时出现父子顺序问题。
            ids.reversed().forEach { categories.delete(it) }
            affected.size
        }
        withContext(NonCancellable + Dispatchers.Main) { PlayerHolder.removeTracks(context, deletedIds) }
        files.forEach(File::delete)
        return count
    }

    private fun descendants(id: Long, all: List<CategoryEntity>): List<Long> {
        val result = mutableListOf<Long>()
        fun walk(current: Long) {
            result.add(current)
            all.filter { it.parentId == current }.forEach { walk(it.id) }
        }
        walk(id)
        return result
    }
}
