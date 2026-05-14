package `in`.jphe.storyvox.llm.tools

/**
 * Issue #216 — the v1 catalog of AI-callable storyvox actions.
 *
 * Each [ToolSpec] is paired with a [ToolHandler] in
 * `:feature/chat/tools/ChatToolHandlers.kt` at the app DI layer. The
 * specs live here because (a) tests assert against them, (b) the
 * provider classes serialize them into the wire request, and (c)
 * `:feature/chat` already depends on `:core-llm` so the import path
 * is downstream-only.
 *
 * Adding a tool:
 *  1. Define a new [ToolSpec] here.
 *  2. Add the matching [ToolHandler] in `ChatToolHandlers`.
 *  3. Add a contract test in `:core-llm` and a behaviour test in
 *     `:feature/chat`.
 *
 * Descriptions are written in present-tense imperative ("Move a
 * fiction onto…") because that's the voice the model seems to weigh
 * most heavily when picking between tools — verified empirically
 * against Claude Haiku 4.5 + GPT-4o-mini.
 */
object StoryvoxToolSpecs {

    /** Tool name constants — re-exported so handler bindings and
     *  tests can refer to them symbolically rather than re-typing
     *  the snake_case strings. */
    const val ADD_TO_SHELF = "add_to_shelf"
    const val QUEUE_CHAPTER = "queue_chapter"
    const val MARK_CHAPTER_READ = "mark_chapter_read"
    const val SET_SPEED = "set_speed"
    const val OPEN_VOICE_LIBRARY = "open_voice_library"

    /** Allowed values for the `shelf` parameter of [ADD_TO_SHELF]. */
    val SHELVES: List<String> = listOf("Reading", "Read", "Wishlist")

    /** Minimum / maximum playback speed accepted by [SET_SPEED]. The
     *  player itself enforces the same range; this is the same
     *  range the user sees on the playback speed slider. */
    const val SPEED_MIN: Float = 0.5f
    const val SPEED_MAX: Float = 2.5f

    val addToShelf: ToolSpec = ToolSpec(
        name = ADD_TO_SHELF,
        description = "Move a fiction onto one of the user's three " +
            "library shelves: Reading, Read, or Wishlist. Use this " +
            "when the user asks to 'add this book to my Reading " +
            "shelf', 'mark this as read', or similar. The active " +
            "fiction's id is in the chat context — pass it unless " +
            "the user explicitly names a different book.",
        parameters = listOf(
            ToolParameter.StringParam(
                name = "fictionId",
                description = "The stable id of the fiction to shelve. " +
                    "Use the active fiction's id from the chat context.",
            ),
            ToolParameter.StringParam(
                name = "shelf",
                description = "Which shelf to move the fiction onto.",
                allowedValues = SHELVES,
            ),
        ),
    )

    val queueChapter: ToolSpec = ToolSpec(
        name = QUEUE_CHAPTER,
        description = "Start playback of a specific chapter. Use this " +
            "when the user asks to 'play chapter N', 'queue the next " +
            "chapter', or similar navigation. The chapter index is " +
            "zero-based — chapter 1 is index 0, chapter 5 is index 4.",
        parameters = listOf(
            ToolParameter.StringParam(
                name = "fictionId",
                description = "The stable id of the fiction whose " +
                    "chapter to play. Use the active fiction's id " +
                    "from the chat context.",
            ),
            ToolParameter.IntParam(
                name = "chapterIndex",
                description = "Zero-based chapter index in reading " +
                    "order. Chapter 1 is index 0.",
                min = 0,
            ),
        ),
    )

    val markChapterRead: ToolSpec = ToolSpec(
        name = MARK_CHAPTER_READ,
        description = "Mark a chapter as read (or unread). Use this " +
            "when the user says 'I finished chapter 3', 'mark this " +
            "as read', or 'I haven't actually read that chapter yet'.",
        parameters = listOf(
            ToolParameter.StringParam(
                name = "fictionId",
                description = "The stable id of the fiction. Use the " +
                    "active fiction's id from the chat context.",
            ),
            ToolParameter.IntParam(
                name = "chapterIndex",
                description = "Zero-based chapter index in reading " +
                    "order. Chapter 1 is index 0.",
                min = 0,
            ),
        ),
    )

    val setSpeed: ToolSpec = ToolSpec(
        name = SET_SPEED,
        description = "Set the playback speed slider. Use this when " +
            "the user asks to 'speed it up', 'slow down', 'set " +
            "playback to 1.5x', etc. The speed value is a " +
            "multiplier — 1.0 is normal speed, 0.5 is half speed, " +
            "2.0 is double speed.",
        parameters = listOf(
            ToolParameter.FloatParam(
                name = "speed",
                description = "Playback speed multiplier. 1.0 is " +
                    "normal speed.",
                min = SPEED_MIN,
                max = SPEED_MAX,
            ),
        ),
    )

    val openVoiceLibrary: ToolSpec = ToolSpec(
        name = OPEN_VOICE_LIBRARY,
        description = "Open the voice library screen so the user can " +
            "pick or download a different TTS voice. Use this when " +
            "the user asks to 'change the voice', 'pick a different " +
            "narrator', 'see what voices I have', or similar.",
        parameters = emptyList(),
    )

    /** Ordered list of all v1 tools. Iteration order is the order
     *  they appear in this file. */
    val ALL: List<ToolSpec> = listOf(
        addToShelf,
        queueChapter,
        markChapterRead,
        setSpeed,
        openVoiceLibrary,
    )
}
