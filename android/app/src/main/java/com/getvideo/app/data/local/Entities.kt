package com.getvideo.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo

/**
 * 本地视频库。
 *
 * 本地状态与剪辑版本在服务器同步时保留。
 */
@Entity(tableName = "tracks")
data class TrackEntity(
    @PrimaryKey val id: Long,
    val platform: String,
    val videoId: String,
    val title: String,
    val uploader: String?,
    val durationSec: Double?,
    val audioBytes: Long?,
    val audioFormat: String,
    val sourceUrl: String,
    val downloadUrl: String,
    val updatedSeq: Long,
    val audioSha256: String? = null,
    val categories: String = "",
    val hasVocal: Boolean? = null,
    val classifyStage: String? = null,

    /** 本地文件路径；null 表示还没下载 */
    val localPath: String? = null,
    @ColumnInfo(defaultValue = "0") val locallyEdited: Boolean = false,
    val sourceTrackId: Long? = null,
    val favorite: Boolean = false,
    @ColumnInfo(defaultValue = "1") val localCategoryId: Long = 1,
    @ColumnInfo(defaultValue = "0") val updatedAtMs: Long = 0,
    /** 本机重命名后保留显示名，云端同步不覆盖。 */
    val localTitleOverride: String? = null,
    @ColumnInfo(defaultValue = "0") val addedAtMs: Long = 0,
    @ColumnInfo(defaultValue = "'local'") val cloudScope: String = "local",
    val remoteTrackId: Long? = null,
)

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val parentId: Long? = null,
    /** null=空类别，CONTAINER=子类别，LEAF=视频。 */
    val kind: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val accessCount: Int = 0,
    val lastAccessedAt: Long = 0,
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val isDefault: Boolean = false,
)

@Entity(tableName = "playlist_items", primaryKeys = ["playlistId", "trackId"])
data class PlaylistItemEntity(
    val playlistId: Long,
    val trackId: Long,
    val addedAt: Long = System.currentTimeMillis(),
    val orderIndex: Int,
)

/** 本机删除的云端曲目，避免下次同步又出现。 */
@Entity(tableName = "deleted_tracks")
data class DeletedTrackEntity(@PrimaryKey val id: Long)

/** 同步游标等单例配置。只有一行，id 固定为 0。 */
@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val id: Int = 0,
    val cursor: Long = 0,
    val lastSyncAt: Long = 0,
)

@Entity(tableName = "active_scope")
data class ActiveScopeEntity(@PrimaryKey val id: Int = 0, val scope: String = "")

@Entity(tableName = "cloud_mapping", indices = [androidx.room.Index(value = ["scope", "remoteId"], unique = true)])
data class CloudMappingEntity(@PrimaryKey val localId: Long, val scope: String, val remoteId: Long)

@Entity(tableName = "cloud_sync")
data class CloudSyncEntity(@PrimaryKey val scope: String, val cursor: Long = 0)
