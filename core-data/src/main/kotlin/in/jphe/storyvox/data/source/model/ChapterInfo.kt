package `in`.jphe.storyvox.data.source.model

/**
 * Table-of-contents entry. Body bytes are NOT included — see [ChapterContent].
 */
data class ChapterInfo(
    val id: String,
    val sourceChapterId: String,
    val index: Int,
    val title: String,
    val publishedAt: Long? = null,
    val wordCount: Int? = null,
)
