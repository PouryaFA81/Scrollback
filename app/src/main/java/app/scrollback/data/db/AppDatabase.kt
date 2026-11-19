package app.scrollback.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        SourceEntity::class,
        MediaEntity::class,
        TagEntity::class,
        MediaTagRef::class,
        PlaylistEntity::class,
        PlaylistEntry::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sources(): SourceDao
    abstract fun media(): MediaDao
    abstract fun tags(): TagDao
    abstract fun playlists(): PlaylistDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "scrollback.db").build()
    }
}
