package `in`.jphe.storyvox.feature.chat.tools

import `in`.jphe.storyvox.data.db.entity.Shelf
import `in`.jphe.storyvox.data.repository.ChapterRepository
import `in`.jphe.storyvox.data.repository.ShelfRepository
import `in`.jphe.storyvox.feature.api.FictionRepositoryUi
import `in`.jphe.storyvox.feature.api.PlaybackControllerUi
import `in`.jphe.storyvox.feature.api.SettingsRepositoryUi
import `in`.jphe.storyvox.llm.tools.StoryvoxToolSpecs
import `in`.jphe.storyvox.llm.tools.ToolHandler
import `in`.jphe.storyvox.llm.tools.ToolRegistry
import `in`.jphe.storyvox.llm.tools.ToolResult
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Issue #216 — concrete [ToolHandler] implementations wiring the v1
 * tool catalog to storyvox's data + playback layers.
 *
 * Constructed per-chat-turn by the ViewModel (cheap — five lambdas
 * holding repo refs), passed into the provider's tool-aware chat
 * path via [ToolRegistry]. The chat's active fiction id is captured
 * at construction time; the AI's `fictionId` argument is honored
 * over the active fiction only when the user explicitly named a
 * different book — practical guard against the model "helpfully"
 * targeting the wrong book on a search-shaped prompt.
 */
class ChatToolHandlers(
    private val activeFictionId: String,
    private val shelfRepo: ShelfRepository,
    private val chapterRepo: ChapterRepository,
    private val fictionRepo: FictionRepositoryUi,
    private val playback: PlaybackControllerUi,
    private val settingsRepo: SettingsRepositoryUi,
    /** Fired on `open_voice_library` calls. The Chat ViewModel relays
     *  this to ChatScreen, which navigates via the nav controller. */
    private val onOpenVoiceLibrary: () -> Unit,
) {
    /** Build a [ToolRegistry] bound to this handler instance.
     *  Idempotent — same registry every call. */
    fun registry(): ToolRegistry = ToolRegistry.build(
        specs = StoryvoxToolSpecs.ALL,
        lookup = ::handlerFor,
    )

    private fun handlerFor(name: String): ToolHandler = when (name) {
        StoryvoxToolSpecs.ADD_TO_SHELF -> ToolHandler { addToShelf(it) }
        StoryvoxToolSpecs.QUEUE_CHAPTER -> ToolHandler { queueChapter(it) }
        StoryvoxToolSpecs.MARK_CHAPTER_READ -> ToolHandler { markChapterRead(it) }
        StoryvoxToolSpecs.SET_SPEED -> ToolHandler { setSpeed(it) }
        StoryvoxToolSpecs.OPEN_VOICE_LIBRARY -> ToolHandler { openVoiceLibrary() }
        else -> ToolHandler {
            ToolResult.Error("Unknown tool: $name")
        }
    }

    // ── Handler implementations ────────────────────────────────────

    /** `add_to_shelf(fictionId, shelf)` — move a fiction onto one of
     *  the three predefined shelves. Validates the shelf name against
     *  [Shelf.ALL] (Reading / Read / Wishlist); anything else returns
     *  an Error so the AI surfaces "I can only add to those three". */
    internal suspend fun addToShelf(args: JsonObject): ToolResult {
        val fictionId = args["fictionId"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() } ?: activeFictionId
        val shelfName = args["shelf"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.Error(
                "Tell me which shelf — Reading, Read, or Wishlist.",
            )
        val shelf = Shelf.fromName(shelfName)
            ?: return ToolResult.Error(
                "\"$shelfName\" isn't one of the shelves. Pick Reading, Read, or Wishlist.",
            )
        val fiction = runCatching {
            fictionRepo.fictionById(fictionId).first()
        }.getOrNull()
            ?: return ToolResult.Error(
                "I couldn't find a fiction with id \"$fictionId\".",
            )
        shelfRepo.add(fictionId, shelf)
        return ToolResult.Success(
            "Added \"${fiction.title}\" to your ${shelf.displayName} shelf.",
        )
    }

    /** `queue_chapter(fictionId, chapterIndex)` — start playing a
     *  specific chapter. Resolves the chapter id by listing chapters
     *  for the fiction and indexing into the result. */
    internal suspend fun queueChapter(args: JsonObject): ToolResult {
        val fictionId = args["fictionId"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() } ?: activeFictionId
        val index = args["chapterIndex"]?.jsonPrimitive?.intOrNull
            ?: return ToolResult.Error(
                "Tell me which chapter — the chapter index is zero-based.",
            )
        if (index < 0) {
            return ToolResult.Error(
                "Chapter index can't be negative (index 0 is chapter 1).",
            )
        }
        val chapters = runCatching {
            fictionRepo.chaptersFor(fictionId).first()
        }.getOrDefault(emptyList())
        if (chapters.isEmpty()) {
            return ToolResult.Error(
                "I couldn't find any chapters for that fiction yet.",
            )
        }
        val chapter = chapters.getOrNull(index)
            ?: return ToolResult.Error(
                "Chapter index $index is out of range — this book has ${chapters.size} chapter(s).",
            )
        playback.startListening(
            fictionId = fictionId,
            chapterId = chapter.id,
            charOffset = 0,
            autoPlay = true,
        )
        return ToolResult.Success(
            "Queued \"${chapter.title}\" (chapter ${index + 1}) for playback.",
        )
    }

    /** `mark_chapter_read(fictionId, chapterIndex)` — flip the
     *  chapter's read flag to true. Resolves chapter id by index
     *  same way as [queueChapter]. */
    internal suspend fun markChapterRead(args: JsonObject): ToolResult {
        val fictionId = args["fictionId"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() } ?: activeFictionId
        val index = args["chapterIndex"]?.jsonPrimitive?.intOrNull
            ?: return ToolResult.Error(
                "Tell me which chapter — the chapter index is zero-based.",
            )
        if (index < 0) {
            return ToolResult.Error(
                "Chapter index can't be negative.",
            )
        }
        val chapters = runCatching {
            fictionRepo.chaptersFor(fictionId).first()
        }.getOrDefault(emptyList())
        val chapter = chapters.getOrNull(index)
            ?: return ToolResult.Error(
                "Chapter index $index is out of range — this book has ${chapters.size} chapter(s).",
            )
        chapterRepo.markRead(chapter.id, read = true)
        return ToolResult.Success(
            "Marked \"${chapter.title}\" as read.",
        )
    }

    /** `set_speed(speed)` — set the playback speed slider. Clamps to
     *  [0.5..2.5] to match the slider's visible range; reports the
     *  clamped value in the success message so the AI can echo it
     *  accurately even when the user asked for something out-of-range. */
    internal suspend fun setSpeed(args: JsonObject): ToolResult {
        val raw = args["speed"]?.jsonPrimitive?.floatOrNull
            ?: return ToolResult.Error(
                "Tell me a speed — 1.0 is normal, 1.5 is faster, 0.8 is slower.",
            )
        val clamped = raw.coerceIn(
            StoryvoxToolSpecs.SPEED_MIN,
            StoryvoxToolSpecs.SPEED_MAX,
        )
        playback.setSpeed(clamped)
        // Persist the new default so it sticks across chapters —
        // matches what tapping the slider does (#312 made setSpeed
        // also persist), but we set both surfaces explicitly to be
        // safe across renderer changes.
        runCatching { settingsRepo.setDefaultSpeed(clamped) }
        val suffix = if (clamped != raw) " (clamped from ${"%.2f".format(raw)})" else ""
        return ToolResult.Success(
            "Set playback speed to ${"%.2f".format(clamped)}x$suffix.",
        )
    }

    /** `open_voice_library()` — navigate to Settings → Voices. No
     *  arguments. Fires the [onOpenVoiceLibrary] callback the
     *  ViewModel passed in; that hop into nav-land is the only
     *  place this handler talks to the UI. */
    internal suspend fun openVoiceLibrary(): ToolResult {
        onOpenVoiceLibrary()
        return ToolResult.Success("Opening the voice library.")
    }
}
