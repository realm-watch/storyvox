package `in`.jphe.storyvox.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import `in`.jphe.storyvox.data.source.model.FictionStatus

/**
 * Row representing a fiction we've ever interacted with — library, follows, or
 * just visited. Library rows are persisted indefinitely; non-library rows are
 * effectively a metadata cache.
 */
@Entity(
    tableName = "fiction",
    indices = [
        Index(value = ["inLibrary"]),
        Index(value = ["followedRemotely"]),
        Index(value = ["sourceId", "lastUpdatedAt"]),
    ],
)
data class Fiction(
    @PrimaryKey val id: String,
    val sourceId: String,
    val title: String,
    val author: String,
    val authorId: String? = null,
    val coverUrl: String? = null,
    val description: String? = null,
    val genres: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val status: FictionStatus = FictionStatus.ONGOING,
    val chapterCount: Int = 0,
    val wordCount: Long? = null,
    val rating: Float? = null,
    val views: Long? = null,
    val followers: Int? = null,
    val lastUpdatedAt: Long? = null,
    val firstSeenAt: Long,
    val metadataFetchedAt: Long,
    val inLibrary: Boolean = false,
    val addedToLibraryAt: Long? = null,
    val followedRemotely: Boolean = false,
    val downloadMode: DownloadMode? = null,
    val pinnedVoiceId: String? = null,
    val pinnedVoiceLocale: String? = null,
    val notesEverSeen: Boolean = false,
)

/** Per-book download policy override. Null = inherit global setting. */
enum class DownloadMode { LAZY, EAGER, SUBSCRIBE }
