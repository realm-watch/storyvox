package `in`.jphe.storyvox.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import `in`.jphe.storyvox.data.source.model.NotePosition

/**
 * One chapter row. Composite PK uses `"$fictionId:$index"` so re-numbering on
 * the source doesn't orphan our position records — the source's own numeric
 * id is also retained as `sourceChapterId` for the fetcher's URL building.
 */
@Entity(
    tableName = "chapter",
    foreignKeys = [
        ForeignKey(
            entity = Fiction::class,
            parentColumns = ["id"],
            childColumns = ["fictionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["fictionId"]),
        Index(value = ["fictionId", "index"], unique = true),
        Index(value = ["downloadState"]),
    ],
)
data class Chapter(
    @PrimaryKey val id: String,
    val fictionId: String,
    val sourceChapterId: String,
    val index: Int,
    val title: String,
    val publishedAt: Long? = null,
    val wordCount: Int? = null,
    val htmlBody: String? = null,
    val plainBody: String? = null,
    val bodyFetchedAt: Long? = null,
    val bodyChecksum: String? = null,
    val downloadState: ChapterDownloadState = ChapterDownloadState.NOT_DOWNLOADED,
    val lastDownloadAttemptAt: Long? = null,
    val lastDownloadError: String? = null,
    val notesAuthor: String? = null,
    val notesAuthorPosition: NotePosition? = null,
    val userMarkedRead: Boolean = false,
    val firstReadAt: Long? = null,
)

enum class ChapterDownloadState {
    NOT_DOWNLOADED,
    QUEUED,
    DOWNLOADING,
    DOWNLOADED,
    FAILED,
}
