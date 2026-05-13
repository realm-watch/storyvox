package `in`.jphe.storyvox.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import `in`.jphe.storyvox.data.db.converter.Converters
import `in`.jphe.storyvox.data.db.dao.AuthDao
import `in`.jphe.storyvox.data.db.dao.ChapterDao
import `in`.jphe.storyvox.data.db.dao.ChapterHistoryDao
import `in`.jphe.storyvox.data.db.dao.FictionDao
import `in`.jphe.storyvox.data.db.dao.FictionShelfDao
import `in`.jphe.storyvox.data.db.dao.LlmMessageDao
import `in`.jphe.storyvox.data.db.dao.LlmSessionDao
import `in`.jphe.storyvox.data.db.dao.PlaybackDao
import `in`.jphe.storyvox.data.db.entity.AuthCookie
import `in`.jphe.storyvox.data.db.entity.Chapter
import `in`.jphe.storyvox.data.db.entity.ChapterHistory
import `in`.jphe.storyvox.data.db.entity.Fiction
import `in`.jphe.storyvox.data.db.entity.FictionShelf
import `in`.jphe.storyvox.data.db.entity.LlmSession
import `in`.jphe.storyvox.data.db.entity.LlmStoredMessage
import `in`.jphe.storyvox.data.db.entity.PlaybackPosition

@Database(
    entities = [
        Fiction::class,
        Chapter::class,
        PlaybackPosition::class,
        AuthCookie::class,
        // v3 (#81 AI integration) — multi-session chat tables.
        LlmSession::class,
        LlmStoredMessage::class,
        // v5 (#116 library shelves) — many-to-many junction.
        FictionShelf::class,
        // v6 (#158 reading history) — one row per (fiction, chapter)
        // pair, upserted on every open. Forever retention.
        ChapterHistory::class,
    ],
    version = 6,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class StoryvoxDatabase : RoomDatabase() {
    abstract fun fictionDao(): FictionDao
    abstract fun chapterDao(): ChapterDao
    abstract fun chapterHistoryDao(): ChapterHistoryDao
    abstract fun playbackDao(): PlaybackDao
    abstract fun authDao(): AuthDao
    abstract fun llmSessionDao(): LlmSessionDao
    abstract fun llmMessageDao(): LlmMessageDao
    abstract fun fictionShelfDao(): FictionShelfDao

    companion object {
        const val NAME: String = "storyvox.db"
    }
}
