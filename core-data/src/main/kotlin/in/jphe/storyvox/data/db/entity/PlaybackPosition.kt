package `in`.jphe.storyvox.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * "Where I was" — one row per fiction, holds latest position. Bookmarks /
 * history are deferred (YAGNI v1). Cascade-deletes when the parent fiction
 * leaves the library.
 */
@Entity(
    tableName = "playback_position",
    foreignKeys = [
        ForeignKey(
            entity = Fiction::class,
            parentColumns = ["id"],
            childColumns = ["fictionId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = Chapter::class,
            parentColumns = ["id"],
            childColumns = ["chapterId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["chapterId"]),
        Index(value = ["updatedAt"]),
    ],
)
data class PlaybackPosition(
    @PrimaryKey val fictionId: String,
    val chapterId: String,
    val charOffset: Int = 0,
    val paragraphIndex: Int = 0,
    val playbackSpeed: Float = 1.0f,
    /** Player's last-known estimated total duration of this chapter, in millis. */
    val durationEstimateMs: Long = 0L,
    val updatedAt: Long,
)
