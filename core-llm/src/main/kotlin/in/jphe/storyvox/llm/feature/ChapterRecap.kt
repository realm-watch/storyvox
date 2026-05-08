package `in`.jphe.storyvox.llm.feature

import `in`.jphe.storyvox.data.db.dao.ChapterDao
import `in`.jphe.storyvox.data.db.dao.FictionDao
import `in`.jphe.storyvox.llm.LlmConfig
import `in`.jphe.storyvox.llm.LlmError
import `in`.jphe.storyvox.llm.LlmMessage
import `in`.jphe.storyvox.llm.LlmRepository
import `in`.jphe.storyvox.llm.ProviderId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow

/**
 * "What just happened?" — recap the last [DEFAULT_WINDOW] chapters
 * for a returning listener. Pulls plain-text from Room, builds the
 * librarian prompt, streams the response.
 *
 * The librarian system prompt is the personality knob. Short and
 * explicit ("don't invent details", "end with a period") gives both
 * Claude and Llama-3.x a stable shape; we can refine as we listen
 * to real recaps.
 */
@Singleton
class ChapterRecap @Inject constructor(
    private val chapterDao: ChapterDao,
    private val fictionDao: FictionDao,
    private val llm: LlmRepository,
    private val configFlow: Flow<LlmConfig>,
) {
    /**
     * Stream a 150–220 word recap of [recapWindow] chapters preceding
     * (and including) [currentChapterId]. Emits text deltas in
     * arrival order. Caller collects on a coroutine bound to a
     * Cancel button — cancellation propagates through the Flow into
     * the OkHttp Call.
     *
     * @throws LlmError.NotConfigured when AI is disabled or the
     *   "Send chapter text to AI" toggle is off.
     */
    fun recap(
        fictionId: String,
        currentChapterId: String,
        recapWindow: Int = DEFAULT_WINDOW,
    ): Flow<String> = flow {
        val cfg = configFlow.first()
        if (cfg.provider == null) {
            throw LlmError.NotConfigured(ProviderId.Claude)
        }
        if (!cfg.sendChapterTextEnabled) {
            // Keep the same exception type — the UI's NotConfigured
            // handler routes to Settings, which is exactly where we
            // want a user who's disabled the toggle to land.
            throw LlmError.NotConfigured(cfg.provider)
        }

        val fiction = fictionDao.get(fictionId)
        val fictionTitle = fiction?.title ?: "this fiction"

        val infos = chapterDao.observeChapterInfosByFiction(fictionId).first()
        val currentIdx = infos.indexOfFirst { it.id == currentChapterId }
        if (currentIdx < 0) return@flow
        val start = (currentIdx - recapWindow + 1).coerceAtLeast(0)
        val window = infos.subList(start, currentIdx + 1)

        // Pull each chapter's plain body. Skip ones that haven't been
        // downloaded yet — recap of "the chapters I've read" is more
        // honest than recap of "what I'm guessing chapters say".
        val pieces = window.mapNotNull { info ->
            val ch = chapterDao.get(info.id) ?: return@mapNotNull null
            val text = ch.plainBody ?: return@mapNotNull null
            ChapterPiece(
                title = info.title,
                text = text.truncateForBudget(MAX_PER_CHAPTER_CHARS),
            )
        }
        if (pieces.isEmpty()) return@flow

        val joined = pieces.joinToString("\n\n") {
            "## ${it.title}\n${it.text}"
        }

        val systemPrompt = SYSTEM_PROMPT
        val userPrompt = buildString {
            append("Recap the following chapters of \"")
            append(fictionTitle)
            append("\":\n\n")
            append(joined)
        }

        emitAll(
            llm.stream(
                messages = listOf(
                    LlmMessage(LlmMessage.Role.user, userPrompt),
                ),
                systemPrompt = systemPrompt,
            ),
        )
    }

    private data class ChapterPiece(val title: String, val text: String)

    companion object {
        const val DEFAULT_WINDOW: Int = 3

        /**
         * Per-chapter truncation budget. Claude has ~200k tokens of
         * context, plenty; Ollama's default is ~8k tokens, of which
         * we want to leave ~2k for the response and prompt
         * scaffolding. 5000 chars/chapter × 3 chapters ≈ 15k chars
         * ≈ 4k input tokens — fits both.
         */
        const val MAX_PER_CHAPTER_CHARS: Int = 5_000

        /** Librarian persona — careful, literate, won't invent. */
        const val SYSTEM_PROMPT: String = """
            You are a careful, literate librarian recapping a serial web
            fiction for a returning listener. Output a single paragraph
            of 150–220 words. Cover: who the major characters are, what
            just happened, and what unresolved tension drives the next
            chapter. Quote sparingly. Do not invent details not present
            in the text. Do not editorialize or rate the writing. End
            with a period.
        """
    }
}

private fun String.truncateForBudget(maxChars: Int): String =
    if (length <= maxChars) this
    else take(maxChars) + "\n[…truncated for AI context budget…]"
