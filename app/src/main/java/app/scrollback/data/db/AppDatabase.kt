package app.scrollback.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        SourceEntity::class,
        MediaEntity::class,
        TagEntity::class,
        MediaTagRef::class,
        PlaylistEntity::class,
        PlaylistEntry::class,
        SyncTombstone::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sources(): SourceDao
    abstract fun media(): MediaDao
    abstract fun tags(): TagDao
    abstract fun playlists(): PlaylistDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "scrollback.db")
                .addMigrations(MIGRATION_1_2)
                .build()

        /** v0.3: remote sources (accounts, previews, offline copies), sync clocks, music tags. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_1_2_SQL.forEach(db::execSQL)
            }
        }

        val MIGRATION_1_2_SQL = listOf(
            "ALTER TABLE sources ADD COLUMN accountId TEXT",
            "ALTER TABLE media ADD COLUMN thumbUri TEXT",
            "ALTER TABLE media ADD COLUMN localUri TEXT",
            "ALTER TABLE media ADD COLUMN metaUpdatedAt INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE media ADD COLUMN artist TEXT",
            "ALTER TABLE media ADD COLUMN album TEXT",
            "ALTER TABLE media ADD COLUMN title TEXT",
            "ALTER TABLE media ADD COLUMN trackNo INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE media ADD COLUMN remoteId TEXT",
            "CREATE INDEX IF NOT EXISTS `index_media_artist` ON `media` (`artist`)",
            "CREATE INDEX IF NOT EXISTS `index_media_album` ON `media` (`album`)",
            "ALTER TABLE playlists ADD COLUMN uuid TEXT NOT NULL DEFAULT ''",
            "ALTER TABLE playlists ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0",
            "CREATE TABLE IF NOT EXISTS `sync_tombstones` (`key` TEXT NOT NULL, `deletedAt` INTEGER NOT NULL, PRIMARY KEY(`key`))",
            // Existing likes/notes count as edited when they were made (or at upgrade time).
            "UPDATE media SET metaUpdatedAt = MAX(likedAt, lastWatchedAt) WHERE liked = 1 OR note != '' OR lastWatchedAt > 0",
        )
    }
}
