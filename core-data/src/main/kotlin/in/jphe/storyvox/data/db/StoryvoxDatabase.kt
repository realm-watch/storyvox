package `in`.jphe.storyvox.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import `in`.jphe.storyvox.data.db.converter.Converters
import `in`.jphe.storyvox.data.db.dao.AuthDao
import `in`.jphe.storyvox.data.db.dao.ChapterDao
import `in`.jphe.storyvox.data.db.dao.FictionDao
import `in`.jphe.storyvox.data.db.dao.PlaybackDao
import `in`.jphe.storyvox.data.db.entity.AuthCookie
import `in`.jphe.storyvox.data.db.entity.Chapter
import `in`.jphe.storyvox.data.db.entity.Fiction
import `in`.jphe.storyvox.data.db.entity.PlaybackPosition

@Database(
    entities = [
        Fiction::class,
        Chapter::class,
        PlaybackPosition::class,
        AuthCookie::class,
    ],
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class StoryvoxDatabase : RoomDatabase() {
    abstract fun fictionDao(): FictionDao
    abstract fun chapterDao(): ChapterDao
    abstract fun playbackDao(): PlaybackDao
    abstract fun authDao(): AuthDao

    companion object {
        const val NAME: String = "storyvox.db"
    }
}
