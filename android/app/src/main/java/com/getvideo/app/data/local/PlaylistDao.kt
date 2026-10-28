package com.getvideo.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun byId(id: Long): PlaylistEntity?

    @Query("SELECT id FROM playlists WHERE isDefault = 1 LIMIT 1")
    suspend fun firstId(): Long?

    @Query("SELECT id FROM playlists ORDER BY createdAt, id LIMIT 1")
    suspend fun oldestId(): Long?

    @Query("UPDATE playlists SET isDefault = CASE WHEN id = :id THEN 1 ELSE 0 END")
    suspend fun markDefault(id: Long)

    @Query("SELECT * FROM playlist_items WHERE playlistId = :id ORDER BY orderIndex")
    suspend fun itemsNow(id: Long): List<PlaylistItemEntity>

    @Query("UPDATE playlist_items SET orderIndex = :position WHERE playlistId = :id AND trackId = :trackId")
    suspend fun setOrder(id: Long, trackId: Long, position: Int)

    @Query("DELETE FROM playlist_items WHERE playlistId = :id AND trackId = :trackId")
    suspend fun removeItem(id: Long, trackId: Long)

    @Query("SELECT name FROM playlists")
    suspend fun names(): List<String>

    @Insert
    suspend fun insert(playlist: PlaylistEntity): Long

    @Query("UPDATE playlists SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM playlist_items WHERE playlistId = :playlistId ORDER BY orderIndex")
    fun items(playlistId: Long): Flow<List<PlaylistItemEntity>>

    @Query("SELECT COALESCE(MAX(orderIndex), -1) FROM playlist_items WHERE playlistId = :playlistId")
    suspend fun lastOrder(playlistId: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun add(items: List<PlaylistItemEntity>)

    @Query("DELETE FROM playlist_items WHERE playlistId = :playlistId")
    suspend fun clear(playlistId: Long)

    @Query("DELETE FROM playlist_items WHERE trackId = :trackId")
    suspend fun removeTrack(trackId: Long)
}
