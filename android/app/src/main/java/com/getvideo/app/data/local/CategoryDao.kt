package com.getvideo.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories WHERE id != 1 ORDER BY name")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories ORDER BY name")
    suspend fun allNow(): List<CategoryEntity>

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun byId(id: Long): CategoryEntity?

    @Query("SELECT * FROM categories WHERE parentId = :parentId ORDER BY name")
    fun children(parentId: Long): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE id != 1 AND parentId IS NULL ORDER BY name")
    fun roots(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE id != 1 ORDER BY accessCount DESC, lastAccessedAt DESC LIMIT 5")
    fun frequent(): Flow<List<CategoryEntity>>

    @Insert
    suspend fun insert(category: CategoryEntity): Long

    @Update
    suspend fun update(category: CategoryEntity)

    @Query("UPDATE categories SET accessCount = accessCount + 1, lastAccessedAt = :now WHERE id = :id")
    suspend fun recordAccess(id: Long, now: Long)

    @Query("DELETE FROM categories WHERE id = :id")
    suspend fun delete(id: Long)
}
