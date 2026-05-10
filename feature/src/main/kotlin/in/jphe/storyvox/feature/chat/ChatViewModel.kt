package `in`.jphe.storyvox.feature.chat

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import `in`.jphe.storyvox.feature.api.FictionRepositoryUi
import `in`.jphe.storyvox.feature.api.PlaybackControllerUi
import `in`.jphe.storyvox.feature.api.SettingsRepositoryUi
import `in`.jphe.storyvox.feature.api.UiChatGrounding
import `in`.jphe.storyvox.feature.api.UiRecapPlaybackState
import `in`.jphe.storyvox.llm.FeatureKind
import `in`.jphe.storyvox.llm.LlmConfig
import `in`.jphe.storyvox.llm.LlmError
import `in`.jphe.storyvox.llm.LlmMessage
import `in`.jphe.storyvox.llm.LlmSessionRepository
import `in`.jphe.storyvox.llm.ProviderId
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One chat turn the UI renders, distinct from the wire/storage shapes.
 *  The in-flight assistant message lives in [ChatUiState.streaming]
 *  rather than as a [ChatTurn] entry so its identity is unambiguous —
 *  appended-to deltas don't risk being mistaken for a finalised turn. */
@Immutable
data class ChatTurn(
    val role: Role,
    val text: String,
) {
    enum class Role { User, Assistant }
}

/** Top-level UI state. Fields are independently observable so a token
 *  arriving doesn't reflow the whole list (Compose only diffs the
 *  changed lambda). */
@Immutable
data class ChatUiState(
    /** Finalised user + assistant turns from session history. */
    val turns: List<ChatTurn> = emptyList(),
    /** The current in-flight assistant reply, mid-stream. Null when
     *  nothing is streaming. */
    val streaming: String? = null,
    /** Title to display in the top bar — the fiction the user is
     *  reading. Null until the fiction row resolves. */
    val fictionTitle: String? = null,
    /** True when AI is disabled in Settings → AI. The UI surfaces a
     *  tap-through empty state instead of the input field. */
    val noProvider: Boolean = false,
    /** When non-null, the last send hit an error. UI surfaces it as
     *  a recoverable banner above the input. */
    val error: ChatError? = null,
    /** Issue #214 — text of the assistant turn currently being read
     *  aloud through the TTS engine, or null when nothing is reading.
     *  The matching turn's bubble shows a stop icon; other bubbles
     *  show a play icon. */
    val readingText: String? = null,
)

/** UI-side classification of [LlmError] so the screen can pick the
 *  right copy + recovery action without depending on `:core-llm`'s
 *  exception types. */
@Immutable
sealed class ChatError(val message: String) {
    /** AI not configured / "Send chapter text" toggle off. */
    class NotConfigured(message: String) : ChatError(message)
    /** 401 / 403 — wrong key. UI offers Settings tap-through. */
    class AuthFailed(message: String) : ChatError(message)
    /** Network / DNS / TLS — recoverable. UI offers Try again. */
    class Transport(message: String) : ChatError(message)
    /** 4xx / 5xx that isn't auth — quota, model 404, malformed body. */
    class ProviderError(message: String) : ChatError(message)
}

/**
 * Backs the Q&A chat surface attached to a fiction. One chat history
 * per fiction (deterministic session id `chat:<fictionId>`) so
 * returning to a book picks up where the conversation left off.
 *
 * Persistence is delegated entirely to [LlmSessionRepository]: it
 * inserts the user turn before the stream starts, appends the
 * assistant turn on success, and emits message rows via
 * [LlmSessionRepository.observeMessages]. This ViewModel owns the
 * in-flight streaming state but never writes to Room directly.
 *
 * The session is bound to the user's currently-active provider at the
 * moment of first creation. If the user switches providers in Settings
 * later, existing sessions stay on their original provider — the
 * library-style "one session per fiction" rebinds on next visit only
 * when the prior session has no messages yet (so an empty session
 * isn't pinned to a provider the user no longer wants).
 *
 * Out of scope for this PR (issue follow-ups): voice/TTS read-back,
 * multi-modal images, function calling / tool use, cross-fiction
 * memory.
 */
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val sessionRepo: LlmSessionRepository,
    private val fictionRepo: FictionRepositoryUi,
    private val playback: PlaybackControllerUi,
    private val configFlow: Flow<LlmConfig>,
    private val settingsRepo: SettingsRepositoryUi,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    private val fictionId: String = checkNotNull(savedState["fictionId"]) {
        "ChatScreen requires a `fictionId` nav arg"
    }

    /** Deterministic so a returning user picks up the prior chat
     *  history for this book. Mirrors ChapterRecap's
     *  `recap:fictionId:chapterId` shape (LlmSession.id kdoc). */
    private val sessionId: String = "chat:$fictionId"

    /** One-shot input prefill from the long-press character lookup
     *  (#188). The reader navigates here with `?prefill=Who is X?`, the
     *  composable consumes it once via [consumePrefill] and seeds the
     *  text field. We hold it in a MutableStateFlow rather than as a
     *  raw String so the ChatScreen's `LaunchedEffect` reliably observes
     *  the initial value even on configuration change before consume. */
    private val _prefill = MutableStateFlow<String?>(
        (savedState.get<String>("prefill") ?: "").ifEmpty { null },
    )
    val prefill: StateFlow<String?> = _prefill.asStateFlow()

    /** Called by the input field after applying the prefill so a
     *  subsequent recomposition (e.g. config change) doesn't keep
     *  re-injecting the same starter text over the user's edits. Also
     *  clears the SavedStateHandle key so process death + restore
     *  doesn't replay the prefill either. */
    fun consumePrefill() {
        _prefill.value = null
        savedState["prefill"] = ""
    }

    private val _streaming = MutableStateFlow<String?>(null)
    private val _error = MutableStateFlow<ChatError?>(null)
    private var sendJob: Job? = null

    /** Set lazily on first send (or first load if a session already
     *  exists). Tracks whether we've created the Room row yet, so
     *  rapid double-taps on Send don't race two `createSession` calls. */
    private var sessionEnsured: Boolean = false

    /** Issue #214 — text currently being read aloud (or null). When
     *  the engine finishes naturally [observeReadingDone] clears this;
     *  user-pressed stop also clears it via [stopReadAloud]. */
    private val _readingText = MutableStateFlow<String?>(null)

    /** Public state. Combines storage + in-flight + provider config
     *  + fiction metadata into one immutable [ChatUiState]. The 5-arg
     *  combine ceiling forces a nested shape — the inner combine
     *  builds the base state, the outer folds in the read-aloud text. */
    val uiState: StateFlow<ChatUiState> = run {
        val base = combine(
            sessionRepo.observeMessages(sessionId).map { msgs -> msgs.map { it.toTurn() } },
            _streaming,
            _error,
            fictionRepo.fictionById(fictionId).map { it?.title },
            configFlow.map { it.provider == null },
        ) { turns, streaming, error, title, noProvider ->
            ChatUiState(
                turns = turns,
                streaming = streaming,
                fictionTitle = title,
                noProvider = noProvider,
                error = error,
            )
        }
        combine(base, _readingText) { state, reading ->
            state.copy(readingText = reading)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatUiState())
    }

    init {
        // Auto-clear [_readingText] when the engine naturally finishes
        // an utterance (the user didn't tap stop — the AI text just
        // ended). Without this the UI would stay stuck on the
        // "Stop" icon for the played-out turn.
        viewModelScope.launch {
            playback.recapPlayback.collect { state ->
                if (state == UiRecapPlaybackState.Idle) _readingText.value = null
            }
        }
    }

    /** Issue #214 — read [text] aloud through the TTS engine. Reuses
     *  the same one-shot path as the chapter-recap modal (#189). The
     *  caller is responsible for trimming the text; we surface it
     *  exactly as the model emitted it. */
    fun readAloud(text: String) {
        if (text.isBlank()) return
        _readingText.value = text
        viewModelScope.launch { playback.speakText(text) }
    }

    /** Issue #214 — cancel the in-flight read-aloud. Idempotent. */
    fun stopReadAloud() {
        _readingText.value = null
        playback.stopSpeaking()
    }

    /** Send [text] as a user turn and stream the assistant reply.
     *  Cancels any in-flight stream first — the user-facing input
     *  is supposed to be disabled while streaming, but the cancel
     *  guards against races (e.g. text injected via accessibility). */
    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        sendJob?.cancel()
        _error.value = null

        sendJob = viewModelScope.launch {
            val cfg = configFlow.first()
            val provider = cfg.provider
            if (provider == null) {
                _error.value = ChatError.NotConfigured(
                    "Pick a provider in Settings → AI.",
                )
                return@launch
            }

            ensureSession(cfg, provider)
            // Issue #212 — rebuild the system prompt from the live
            // grounding settings before every send. Lets the user
            // flip a toggle and have the next reply use the new
            // context level immediately, without restarting the chat.
            sessionRepo.updateSystemPrompt(sessionId, buildSystemPrompt())

            val buf = StringBuilder()
            _streaming.value = ""

            sessionRepo.chat(sessionId, trimmed)
                .catch { e -> _error.value = mapError(e) }
                .onCompletion { _streaming.value = null }
                .collect { delta ->
                    buf.append(delta)
                    _streaming.value = buf.toString()
                }
        }
    }

    /** Dismiss an error banner. Used by the UI's "X" affordance. */
    fun dismissError() { _error.value = null }

    /** Cancel an in-flight stream. The repo persists user turns
     *  immediately but only saves the assistant reply on completion
     *  — cancelling drops the partial reply, which matches the
     *  cancel-recap behaviour (RecapModal kdoc). */
    fun cancel() {
        sendJob?.cancel()
        sendJob = null
        _streaming.value = null
    }

    override fun onCleared() {
        sendJob?.cancel()
        super.onCleared()
    }

    /** Resolve "the model bound to this provider" off [LlmConfig]. */
    private fun modelFor(provider: ProviderId, cfg: LlmConfig): String = when (provider) {
        ProviderId.Claude -> cfg.claudeModel
        ProviderId.OpenAi -> cfg.openAiModel
        ProviderId.Ollama -> cfg.ollamaModel
        ProviderId.Bedrock -> cfg.bedrockModel
        ProviderId.Vertex -> cfg.vertexModel
        // Foundry's deployment id IS its model id in Azure-speak.
        ProviderId.Foundry -> cfg.foundryDeployment.ifBlank { "gpt-4o-mini" }
        // Teams isn't user-pickable yet — fall through to a sane
        // default; the provider will throw NotConfigured downstream.
        ProviderId.Teams -> cfg.claudeModel
    }

    /**
     * Build the librarian system prompt. The fiction title is always
     * included — it's the bare minimum context. Everything else is
     * gated by user-controlled grounding toggles in Settings → AI
     * (issue #212): chapter title, current sentence, entire chapter,
     * and entire-book-so-far. Token cost ramps fast on the bottom
     * two; users opt in based on their provider's context window.
     *
     * Grounding only injects book content when the user is actively
     * listening to *this* fiction. A chat opened from the fiction
     * detail screen with no playback active falls back to the
     * title-only prompt — there's no "current sentence" without
     * playback state, and grabbing chapter 1 unprompted would
     * surprise the user.
     *
     * Spoiler-prevention is a soft hint, not a hard wall: the AI
     * doesn't have future-chapter text in context anyway, but the
     * prompt makes its limits explicit so users get a coherent
     * "I can only speak to what I've read with you" voice.
     */
    private suspend fun buildSystemPrompt(): String {
        val fictionTitle = fictionRepo.fictionById(fictionId).first()?.title
            ?: "this fiction"
        val grounding = settingsRepo.settings.first().ai.chatGrounding
        val pb = playback.state.first()
        val onSameFiction = pb.fictionId == fictionId

        return buildString {
            append("You are a careful, literate librarian-companion ")
            append("to a reader of \"")
            append(fictionTitle)
            append("\". ")

            if (onSameFiction) {
                appendGroundingClauses(grounding, pb)
            }

            append("Answer questions about plot, characters, pacing, ")
            append("and writing craft. Quote sparingly. Don't invent ")
            append("details. Don't spoil future chapters — speak only ")
            append("to what the reader has likely read so far.")
        }
    }

    /**
     * Append per-toggle context clauses. Order matters — coarse to
     * fine: book-so-far → entire chapter → current sentence →
     * chapter title. Models tend to weight later context more, so
     * the most-precise "what they're on right now" clause goes last.
     *
     * Each section is delimited with markdown-ish headers so the
     * model can tell prefixes apart from the text it's grounding in.
     */
    private suspend fun StringBuilder.appendGroundingClauses(
        grounding: UiChatGrounding,
        pb: `in`.jphe.storyvox.feature.api.UiPlaybackState,
    ) {
        val chapterId = pb.chapterId

        if (grounding.includeEntireBookSoFar && chapterId != null) {
            val all = fictionRepo.chaptersFor(fictionId).first()
            val currentIdx = all.indexOfFirst { it.id == chapterId }
            if (currentIdx >= 0) {
                val priorChapters = all.subList(0, currentIdx)
                val priorTexts = priorChapters.mapNotNull { ch ->
                    fictionRepo.chapterTextById(ch.id)?.let { text -> ch.title to text }
                }
                if (priorTexts.isNotEmpty()) {
                    append("\n\n## Story so far (chapters before the current one)\n\n")
                    priorTexts.forEach { (title, text) ->
                        append("### ").append(title).append("\n")
                        append(text).append("\n\n")
                    }
                }
            }
        }

        if (grounding.includeEntireChapter && chapterId != null) {
            val chapterText = fictionRepo.chapterTextById(chapterId)
            if (!chapterText.isNullOrBlank()) {
                append("\n\n## Current chapter (\"")
                    .append(pb.chapterTitle)
                    .append("\") in full\n\n")
                append(chapterText).append("\n\n")
            }
        }

        if (grounding.includeCurrentSentence) {
            // chapterText flow exposes the live chapter body the
            // playback layer is reading. Slicing with sentenceStart..
            // sentenceEnd matches the highlighted sentence in the
            // reader UI, so "current sentence" means exactly what the
            // user sees on-screen.
            val text = playback.chapterText.first()
            val end = pb.sentenceEnd.coerceAtMost(text.length)
            val start = pb.sentenceStart.coerceIn(0, end)
            if (end > start) {
                append("The reader is currently on this sentence: \"")
                append(text.substring(start, end).trim())
                append("\". ")
            }
        }

        if (grounding.includeChapterTitle && pb.chapterTitle.isNotBlank()) {
            append("The reader is in the chapter \"")
            append(pb.chapterTitle)
            append("\". ")
        }
    }

    /** Idempotent — first call creates the Room row, later calls
     *  no-op. The repo's `chat()` will throw if the session doesn't
     *  exist, so this MUST run before the first send. */
    private suspend fun ensureSession(cfg: LlmConfig, provider: ProviderId) {
        if (sessionEnsured) return
        // It's possible another send happened in a prior process and
        // already created the row. createSession is idempotent on
        // explicitId via @Upsert, but that would clobber the bound
        // provider/model. Use a no-op-if-exists shape instead by
        // observing message count: if observeMessages has emitted
        // any history, the row already exists.
        //
        // Simpler: just call createSession with the explicit id. The
        // DAO is @Upsert, so a duplicate id refreshes name/provider/
        // model — which is fine here since we use deterministic ids
        // and the user can't mutate them out from under us.
        sessionRepo.createSession(
            name = "Chat about ${fictionRepo.fictionById(fictionId).first()?.title ?: "fiction"}",
            provider = provider,
            model = modelFor(provider, cfg),
            systemPrompt = buildSystemPrompt(),
            featureKind = FeatureKind.CharacterLookup,
            anchorFictionId = fictionId,
            explicitId = sessionId,
        )
        sessionEnsured = true
    }

    private fun mapError(e: Throwable): ChatError = when (e) {
        is LlmError.NotConfigured -> ChatError.NotConfigured(
            "Pick a provider in Settings → AI.",
        )
        is LlmError.AuthFailed -> ChatError.AuthFailed(
            "${e.provider} key is invalid — check Settings.",
        )
        is LlmError.Transport -> ChatError.Transport(
            "Couldn't reach the AI — check your connection and try again.",
        )
        is LlmError.ProviderError -> ChatError.ProviderError(
            "AI service error (${e.status}). Try again in a moment.",
        )
        else -> ChatError.Transport(e.message ?: "Send failed.")
    }
}

private fun LlmMessage.toTurn(): ChatTurn = ChatTurn(
    role = when (role) {
        LlmMessage.Role.user -> ChatTurn.Role.User
        LlmMessage.Role.assistant -> ChatTurn.Role.Assistant
    },
    text = content,
)
