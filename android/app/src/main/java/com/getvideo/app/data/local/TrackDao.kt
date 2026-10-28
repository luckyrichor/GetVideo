package com.getvideo.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {

    @Query("SELECT * FROM tracks WHERE (cloudScope IN ('local','legacy_device') OR cloudScope = COALESCE((SELECT scope FROM active_scope WHERE id=0),'')) AND (title LIKE '%' || :q || '%' AND (:startMs IS NULL OR addedAtMs >= :startMs) AND (:endMs IS NULL OR addedAtMs <= :endMs)) ORDER BY sortIndex ASC, addedAtMs DESC, id DESC")
    fun allWithTimeRange(q: String, startMs: Long?, endMs: Long?): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE (cloudScope IN ('local','legacy_device') OR cloudScope = COALESCE((SELECT scope FROM active_scope WHERE id=0),'')) AND (localCategoryId = :categoryId) ORDER BY sortIndex ASC, addedAtMs DESC, id DESC")
    fun inCategory(categoryId: Long): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE (cloudScope IN ('local','legacy_device') OR cloudScope = COALESCE((SELECT scope FROM active_scope WHERE id=0),''))")
    suspend fun allNow(): List<TrackEntity>

    @Query("UPDATE tracks SET sortIndex = :index WHERE id = :id")
    suspend fun setSortIndex(id: Long, index: Long)

    @Query("SELECT * FROM cloud_mapping WHERE scope=:scope AND remoteId=:remoteId")
    suspend fun mapping(scope: String, remoteId: Long): CloudMappingEntity?
    @Query("SELECT localId FROM cloud_mapping UNION SELECT id FROM tracks")
    suspend fun usedIds(): List<Long>
    @Insert suspend fun addMapping(item: CloudMappingEntity)
    @Query("SELECT localId FROM cloud_mapping WHERE scope=:scope AND remoteId IN (:remoteIds)")
    suspend fun mappedIds(scope: String, remoteIds: List<Long>): List<Long>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun setActive(item: ActiveScopeEntity)
    @Query("SELECT cursor FROM cloud_sync WHERE scope=:scope") suspend fun cloudCursor(scope: String): Long?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveCursor(item: CloudSyncEntity)

    @Query("UPDATE tracks SET title = :title, localTitleOverride = :title, updatedAtMs = :now WHERE id = :id")
    suspend fun rename(id: Long, title: String, now: Long)

    @Query("UPDATE tracks SET localCategoryId = :categoryId, updatedAtMs = :now WHERE id = :id")
    suspend fun move(id: Long, categoryId: Long, now: Long)

    @Query("DELETE FROM tracks WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("INSERT OR IGNORE INTO deleted_tracks (id) VALUES (:id)")
    suspend fun suppress(id: Long)

    @Query("DELETE FROM deleted_tracks WHERE id = :id")
    suspend fun unsuppress(id: Long)

    /** Explicit extraction is a user request to restore a previously removed cloud track. */
    @Transaction
    suspend fun restoreExtracted(track: TrackEntity) {
        unsuppress(track.id)
        applySync(listOf(track), emptyList())
    }

    @Query("SELECT id FROM deleted_tracks")
    suspend fun suppressedIds(): List<Long>

    @Query("SELECT * FROM tracks WHERE (cloudScope IN ('local','legacy_device') OR cloudScope = COALESCE((SELECT scope FROM active_scope WHERE id=0),'')) AND ((:platform IS NULL OR platform = :platform) AND (:favoritesOnly = 0 OR favorite = 1) AND (title LIKE '%' || :q || '%' OR uploader LIKE '%' || :q || '%' OR categories LIKE '%' || :q || '%')) ORDER BY updatedSeq DESC")
    fun filtered(q: String, platform: String?, favoritesOnly: Boolean): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE (cloudScope IN ('local','legacy_device') OR cloudScope = COALESCE((SELECT scope FROM active_scope WHERE id=0),'')) ORDER BY updatedSeq DESC")
    fun observeAll(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE (cloudScope IN ('local','legacy_device') OR cloudScope = COALESCE((SELECT scope FROM active_scope WHERE id=0),'')) AND (title LIKE '%' || :q || '%' OR uploader LIKE '%' || :q || '%') ORDER BY updatedSeq DESC")
    fun search(q: String): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE (cloudScope IN ('local','legacy_device') OR cloudScope = COALESCE((SELECT scope FROM active_scope WHERE id=0),'')) AND (platform = :platform) ORDER BY updatedSeq DESC")
    fun byPlatform(platform: String): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE (cloudScope IN ('local','legacy_device') OR cloudScope = COALESCE((SELECT scope FROM active_scope WHERE id=0),'')) AND (favorite = 1) ORDER BY updatedSeq DESC")
    fun favorites(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE (cloudScope IN ('local','legacy_device') OR cloudScope = COALESCE((SELECT scope FROM active_scope WHERE id=0),'')) AND (id = :id)")
    suspend fun byId(id: Long): TrackEntity?

    @Query("SELECT * FROM tracks WHERE (cloudScope IN ('local','legacy_device') OR cloudScope = COALESCE((SELECT scope FROM active_scope WHERE id=0),'')) AND (localPath IS NULL) ORDER BY updatedSeq DESC LIMIT :limit")
    suspend fun notDownloaded(limit: Int): List<TrackEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<TrackEntity>)

    @Query("DELETE FROM tracks WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("DELETE FROM playlist_items WHERE trackId IN (:ids)")
    suspend fun removePlaylistReferences(ids: List<Long>)

    @Query("UPDATE tracks SET localPath = :path WHERE id = :id")
    suspend fun setLocalPath(id: Long, path: String?)

    @Query("UPDATE tracks SET favorite = :fav WHERE id = :id")
    suspend fun setFavorite(id: Long, fav: Boolean)

    /**
     * 同步落库。
     *
     * 服务器发来的记录不带本地状态，直接 REPLACE 会把已下载的路径和
     * 收藏标记抹掉 —— 所以先读出本地状态，合并后再写。
     */
    @Transaction
    suspend fun applySync(incoming: List<TrackEntity>, deletedIds: List<Long>) {
        if (deletedIds.isNotEmpty()) {
            val removable = deletedIds.filter { byId(it)?.locallyEdited != true }
            removePlaylistReferences(removable)
            deleteByIds(removable)
        }
        if (incoming.isEmpty()) return
        val suppressed = suppressedIds().toSet()
        val merged = incoming.filter { it.id !in suppressed }.map { fresh ->
            val existing = byId(fresh.id)
            mergeServerTrack(fresh, existing, System.currentTimeMillis())
        }
        upsertAll(merged)
    }

    @Transaction
    suspend fun upsertLocal(track: TrackEntity) {
        val existing = byId(track.id)
        upsertAll(listOf(track.copy(favorite = existing?.favorite ?: false,
            localCategoryId = existing?.localCategoryId ?: track.localCategoryId,
            title = existing?.localTitleOverride ?: track.title,
            localTitleOverride = existing?.localTitleOverride,
            addedAtMs = existing?.addedAtMs?.takeIf { it > 0 } ?: System.currentTimeMillis(),
            updatedAtMs = System.currentTimeMillis())))
    }
}

@Dao
interface SyncStateDao {
    @Query("SELECT * FROM sync_state WHERE id = 0")
    suspend fun get(): SyncStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(state: SyncStateEntity)

    suspend fun cursor(): Long = get()?.cursor ?: 0
}
