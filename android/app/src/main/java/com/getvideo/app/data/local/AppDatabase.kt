package com.getvideo.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [TrackEntity::class, SyncStateEntity::class, CategoryEntity::class,
        PlaylistEntity::class, PlaylistItemEntity::class, DeletedTrackEntity::class,
        ActiveScopeEntity::class, CloudMappingEntity::class, CloudSyncEntity::class],
    version = 8,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun syncStateDao(): SyncStateDao
    abstract fun categoryDao(): CategoryDao
    abstract fun playlistDao(): PlaylistDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tracks ADD COLUMN categories TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE tracks ADD COLUMN hasVocal INTEGER")
                db.execSQL("ALTER TABLE tracks ADD COLUMN audioSha256 TEXT")
                db.execSQL("ALTER TABLE tracks ADD COLUMN classifyStage TEXT")
            }
        }


        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS categories (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, parentId INTEGER, kind TEXT, createdAt INTEGER NOT NULL, accessCount INTEGER NOT NULL, lastAccessedAt INTEGER NOT NULL)")
                db.execSQL("INSERT OR IGNORE INTO categories (id,name,parentId,kind,createdAt,accessCount,lastAccessedAt) VALUES (1,'未分类',NULL,'LEAF',0,0,0)")
                db.execSQL("ALTER TABLE tracks ADD COLUMN localCategoryId INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE tracks ADD COLUMN updatedAtMs INTEGER NOT NULL DEFAULT 0")
                // 旧版手机直提取的 updatedSeq 是毫秒时间戳；云端曲目的序号不是时间。
                db.execSQL("UPDATE tracks SET updatedAtMs = updatedSeq WHERE id < 0")
                db.execSQL("CREATE TABLE IF NOT EXISTS playlists (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, createdAt INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS playlist_items (playlistId INTEGER NOT NULL, trackId INTEGER NOT NULL, addedAt INTEGER NOT NULL, orderIndex INTEGER NOT NULL, PRIMARY KEY(playlistId,trackId))")
                db.execSQL("CREATE TABLE IF NOT EXISTS deleted_tracks (id INTEGER NOT NULL PRIMARY KEY)")
                // 云端旧记录缺少真实时间，下次同步需重新拉取元数据补齐。
                db.execSQL("UPDATE sync_state SET cursor = 0")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tracks ADD COLUMN localTitleOverride TEXT")
                db.execSQL("UPDATE tracks SET updatedAtMs = 0 WHERE id > 0")
                db.execSQL("UPDATE sync_state SET cursor = 0")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tracks ADD COLUMN addedAtMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE playlists ADD COLUMN isDefault INTEGER NOT NULL DEFAULT 0")
                // Legacy records only have these timestamps; never manufacture a historical date.
                db.execSQL("UPDATE tracks SET addedAtMs = CASE WHEN id < 0 AND updatedSeq > 1000000000000 THEN updatedSeq ELSE updatedAtMs END")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tracks ADD COLUMN locallyEdited INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tracks ADD COLUMN sourceTrackId INTEGER")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tracks ADD COLUMN cloudScope TEXT NOT NULL DEFAULT 'local'")
                db.execSQL("ALTER TABLE tracks ADD COLUMN remoteTrackId INTEGER")
                db.execSQL("UPDATE tracks SET cloudScope = 'legacy_device', remoteTrackId = id WHERE id > 0")
                db.execSQL("CREATE TABLE active_scope (id INTEGER NOT NULL PRIMARY KEY, scope TEXT NOT NULL)")
                db.execSQL("INSERT INTO active_scope VALUES (0,'')")
                db.execSQL("CREATE TABLE cloud_mapping (localId INTEGER NOT NULL PRIMARY KEY, scope TEXT NOT NULL, remoteId INTEGER NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX index_cloud_mapping_scope_remoteId ON cloud_mapping(scope,remoteId)")
                db.execSQL("CREATE TABLE cloud_sync (scope TEXT NOT NULL PRIMARY KEY, cursor INTEGER NOT NULL)")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tracks ADD COLUMN coverUrl TEXT")
            }
        }

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "getvideo.db",
            )
                .addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        super.onCreate(db)
                        db.execSQL("INSERT OR IGNORE INTO categories (id,name,parentId,kind,createdAt,accessCount,lastAccessedAt) VALUES (1,'未分类',NULL,'LEAF',0,0,0)")
                    }
                })
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
                .build()
                .also { instance = it }
        }
    }
}
