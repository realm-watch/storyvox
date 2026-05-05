package `in`.jphe.storyvox.data.source.model

/**
 * The body of a chapter as returned by the source.
 *
 * Implementations should hand back BOTH a sanitized HTML body (for the reader view)
 * and a plaintext rendering (for TTS). It is acceptable for `plainBody` to be a
 * naive strip of `htmlBody` — the playback layer applies further normalization
 * (sentence segmentation, abbreviation expansion) downstream.
 */
data class ChapterContent(
    val info: ChapterInfo,
    val htmlBody: String,
    val plainBody: String,
    val notesAuthor: String? = null,
    val notesAuthorPosition: NotePosition? = null,
)

/** Whether an author's-note block sits before or after the main body. */
enum class NotePosition { BEFORE, AFTER }

/** Lifecycle state of a fiction on its source site. */
enum class FictionStatus { ONGOING, COMPLETED, HIATUS, STUB, DROPPED }
