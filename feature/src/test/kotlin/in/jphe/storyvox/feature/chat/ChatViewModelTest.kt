package `in`.jphe.storyvox.feature.chat

import androidx.lifecycle.SavedStateHandle
import `in`.jphe.storyvox.data.db.dao.LlmMessageDao
import `in`.jphe.storyvox.data.db.dao.LlmSessionDao
import `in`.jphe.storyvox.data.db.entity.LlmSession
import `in`.jphe.storyvox.data.db.entity.LlmStoredMessage
import `in`.jphe.storyvox.feature.api.DownloadMode
import `in`.jphe.storyvox.feature.api.FictionRepositoryUi
import `in`.jphe.storyvox.feature.api.PlaybackControllerUi
import `in`.jphe.storyvox.feature.api.UiAddByUrlResult
import `in`.jphe.storyvox.feature.api.UiChapter
import `in`.jphe.storyvox.feature.api.UiFiction
import `in`.jphe.storyvox.feature.api.UiFollow
import `in`.jphe.storyvox.feature.api.UiPlaybackState
import `in`.jphe.storyvox.feature.api.UiSleepTimerMode
import `in`.jphe.storyvox.llm.FeatureKind
import `in`.jphe.storyvox.llm.LlmConfig
import `in`.jphe.storyvox.llm.LlmError
import `in`.jphe.storyvox.llm.LlmMessage
import `in`.jphe.storyvox.llm.LlmRepository
import `in`.jphe.storyvox.llm.LlmSessionRepository
import `in`.jphe.storyvox.llm.ProviderId
import `in`.jphe.storyvox.llm.provider.ClaudeApiProvider
import `in`.jphe.storyvox.llm.provider.OllamaProvider
import `in`.jphe.storyvox.llm.provider.OpenAiApiProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() { Dispatchers.setMain(dispatcher) }

    @After
    fun tearDown() { Dispatchers.resetMain() }

    // ── Helpers ────────────────────────────────────────────────────

    private fun makeViewModel(
        fictionId: String = "f1",
        fictionTitle: String? = "Sky Pride",
        playbackState: UiPlaybackState = playbackOf(),
        config: LlmConfig = LlmConfig(provider = ProviderId.Claude),
        session: FakeSessionRepo = FakeSessionRepo(),
    ): Pair<ChatViewModel, FakeSessionRepo> {
        val vm = ChatViewModel(
            sessionRepo = session,
            fictionRepo = FakeFictionRepo(fictionId, fictionTitle),
            playback = FakePlayback(playbackState),
            configFlow = flowOf(config),
            savedState = SavedStateHandle(mapOf("fictionId" to fictionId)),
        )
        return vm to session
    }

    /** Subscribe to [vm.uiState] in the test scope so the
     *  WhileSubscribed StateFlow actually starts emitting. Returns
     *  the collector job — caller cancels in the test cleanup. */
    private fun TestScope.collectUiState(vm: ChatViewModel): Job =
        backgroundScope.launch { vm.uiState.collect { /* drain */ } }

    @Test
    fun `empty state when no provider configured`() = runTest(dispatcher) {
        val (vm, _) = makeViewModel(config = LlmConfig(provider = null))
        collectUiState(vm)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.noProvider)
        assertTrue(vm.uiState.value.turns.isEmpty())
        assertNull(vm.uiState.value.streaming)
    }

    @Test
    fun `send streams tokens into uiState then finalises into history`() = runTest(dispatcher) {
        val session = FakeSessionRepo(tokens = listOf("Hello, ", "reader."))
        val (vm, _) = makeViewModel(session = session)
        collectUiState(vm)

        vm.send("What just happened?")
        advanceUntilIdle()

        // Stream finished — _streaming cleared, history contains both
        // the user turn and the assistant reply.
        assertNull(vm.uiState.value.streaming)
        val turns = vm.uiState.value.turns
        assertEquals(2, turns.size)
        assertEquals(ChatTurn.Role.User, turns[0].role)
        assertEquals("What just happened?", turns[0].text)
        assertEquals(ChatTurn.Role.Assistant, turns[1].role)
        assertEquals("Hello, reader.", turns[1].text)
    }

    @Test
    fun `send creates session on first call and reuses it on second`() = runTest(dispatcher) {
        val session = FakeSessionRepo(tokens = listOf("ok"))
        val (vm, _) = makeViewModel(session = session, fictionId = "f1")

        vm.send("first")
        advanceUntilIdle()
        vm.send("second")
        advanceUntilIdle()

        // createSession invoked exactly once, with the deterministic id.
        assertEquals(1, session.createSessionCount)
        assertEquals("chat:f1", session.createdSessionId)
        // chat() called twice on the same session id.
        assertEquals(2, session.chatCalls.size)
        assertTrue(session.chatCalls.all { it.first == "chat:f1" })
    }

    @Test
    fun `send surfaces LlmError as ChatError without crashing`() = runTest(dispatcher) {
        val session = FakeSessionRepo(throwOnChat = LlmError.AuthFailed(ProviderId.Claude, "bad key"))
        val (vm, _) = makeViewModel(session = session)
        collectUiState(vm)

        vm.send("hi")
        advanceUntilIdle()

        val err = vm.uiState.value.error
        assertNotNull(err)
        assertTrue(err is ChatError.AuthFailed)
        // streaming cleared even on error.
        assertNull(vm.uiState.value.streaming)
    }

    @Test
    fun `dismissError clears the banner`() = runTest(dispatcher) {
        val session = FakeSessionRepo(throwOnChat = LlmError.Transport(ProviderId.Claude, IllegalStateException("eof")))
        val (vm, _) = makeViewModel(session = session)
        collectUiState(vm)
        vm.send("hi")
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.error)

        vm.dismissError()
        advanceUntilIdle()
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun `whitespace-only send is a no-op`() = runTest(dispatcher) {
        val session = FakeSessionRepo(tokens = listOf("ok"))
        val (vm, _) = makeViewModel(session = session)
        collectUiState(vm)

        vm.send("   ")
        advanceUntilIdle()

        assertEquals(0, session.chatCalls.size)
        assertTrue(vm.uiState.value.turns.isEmpty())
    }

    @Test
    fun `system prompt grounds in fiction title and current chapter`() = runTest(dispatcher) {
        val session = FakeSessionRepo(tokens = listOf("ok"))
        val (vm, _) = makeViewModel(
            session = session,
            fictionId = "f1",
            fictionTitle = "Sky Pride",
            playbackState = playbackOf(fictionId = "f1", chapterTitle = "Chapter 7"),
        )

        vm.send("ok")
        advanceUntilIdle()

        val sp = session.lastSystemPrompt
        assertNotNull(sp)
        assertTrue(sp!!.contains("Sky Pride"))
        assertTrue(sp.contains("Chapter 7"))
        assertTrue(sp.contains("Don't spoil"))
    }

    @Test
    fun `system prompt omits chapter clause when listening to a different fiction`() = runTest(dispatcher) {
        val session = FakeSessionRepo(tokens = listOf("ok"))
        val (vm, _) = makeViewModel(
            session = session,
            fictionId = "f1",
            fictionTitle = "Sky Pride",
            playbackState = playbackOf(fictionId = "other", chapterTitle = "Some other chapter"),
        )

        vm.send("ok")
        advanceUntilIdle()

        val sp = session.lastSystemPrompt!!
        assertTrue(sp.contains("Sky Pride"))
        assertFalse(sp.contains("Some other chapter"))
    }
}

// ── Fakes ──────────────────────────────────────────────────────────

/**
 * Stand-in for [LlmSessionRepository]. Overrides all three methods
 * the ViewModel touches — `observeMessages`, `createSession`, `chat`.
 * Backed by an in-memory list so observeMessages reflects what
 * createSession + chat append.
 */
private class FakeSessionRepo(
    var tokens: List<String> = emptyList(),
    var throwOnChat: Throwable? = null,
) : LlmSessionRepository(
    sessionDao = ThrowingSessionDao,
    messageDao = ThrowingMessageDao,
    llm = unreachableLlmRepository(),
) {
    var createSessionCount: Int = 0
    var createdSessionId: String? = null
    var lastSystemPrompt: String? = null
    val chatCalls: MutableList<Pair<String, String>> = mutableListOf()
    private val messages = MutableStateFlow<List<LlmMessage>>(emptyList())

    override fun observeMessages(sessionId: String): Flow<List<LlmMessage>> =
        messages.asStateFlow()

    override suspend fun createSession(
        name: String,
        provider: ProviderId,
        model: String,
        systemPrompt: String?,
        featureKind: FeatureKind?,
        anchorFictionId: String?,
        anchorChapterId: String?,
        explicitId: String?,
    ): String {
        createSessionCount++
        createdSessionId = explicitId
        lastSystemPrompt = systemPrompt
        return explicitId ?: "fake-session"
    }

    override fun chat(sessionId: String, userMessage: String): Flow<String> = flow {
        chatCalls += sessionId to userMessage
        // Persist the user turn synchronously, mirroring real repo
        // behaviour ("user msg saved before stream begins").
        messages.update { it + LlmMessage(LlmMessage.Role.user, userMessage) }
        throwOnChat?.let { throw it }
        val buf = StringBuilder()
        for (t in tokens) {
            buf.append(t)
            emit(t)
        }
        // On clean completion, the real repo persists the assistant
        // turn — replicate so observeMessages reflects history.
        messages.update { it + LlmMessage(LlmMessage.Role.assistant, buf.toString()) }
    }
}

private object ThrowingSessionDao : LlmSessionDao {
    override suspend fun upsert(session: LlmSession) = error("not used in fake")
    override fun observeAll(): Flow<List<LlmSession>> = error("not used in fake")
    override suspend fun get(id: String): LlmSession? = error("not used in fake")
    override suspend fun touchLastUsed(id: String, ts: Long) = error("not used in fake")
    override suspend fun delete(id: String) = error("not used in fake")
    override suspend fun deleteAll() = error("not used in fake")
}

private object ThrowingMessageDao : LlmMessageDao {
    override suspend fun insert(message: LlmStoredMessage): Long = error("not used in fake")
    override fun observeBySession(sessionId: String): Flow<List<LlmStoredMessage>> =
        error("not used in fake")
    override suspend fun getBySession(sessionId: String): List<LlmStoredMessage> =
        error("not used in fake")
    override suspend fun deleteBySession(sessionId: String) = error("not used in fake")
}

/** LlmRepository is non-open — but our [FakeSessionRepo] overrides
 *  every method that would otherwise touch it, so this instance is
 *  never reached. We pass a syntactically-valid one to satisfy the
 *  base ctor. */
private fun unreachableLlmRepository(): LlmRepository {
    val cfg = flowOf(LlmConfig())
    return LlmRepository(
        configFlow = cfg,
        claude = unreachableClaude(),
        openAi = unreachableOpenAi(),
        ollama = unreachableOllama(),
    )
}

private fun unreachableClaude(): ClaudeApiProvider =
    object : ClaudeApiProvider(
        http = okhttp3.OkHttpClient(),
        store = NullStore,
        configFlow = flowOf(LlmConfig()),
        json = kotlinx.serialization.json.Json,
    ) {
        override fun stream(messages: List<LlmMessage>, systemPrompt: String?, model: String?) =
            error("unreachable")
    }

private fun unreachableOpenAi(): OpenAiApiProvider =
    object : OpenAiApiProvider(
        http = okhttp3.OkHttpClient(),
        store = NullStore,
        configFlow = flowOf(LlmConfig()),
        json = kotlinx.serialization.json.Json,
    ) {
        override fun stream(messages: List<LlmMessage>, systemPrompt: String?, model: String?) =
            error("unreachable")
    }

private fun unreachableOllama(): OllamaProvider =
    object : OllamaProvider(
        http = okhttp3.OkHttpClient(),
        configFlow = flowOf(LlmConfig()),
        json = kotlinx.serialization.json.Json,
    ) {
        override fun stream(messages: List<LlmMessage>, systemPrompt: String?, model: String?) =
            error("unreachable")
    }

private object NullStore : `in`.jphe.storyvox.llm.LlmCredentialsStore() {
    override fun claudeApiKey(): String? = null
    override fun openAiApiKey(): String? = null
}

// ── Feature-API fakes ──────────────────────────────────────────────

private fun playbackOf(
    fictionId: String? = null,
    chapterId: String? = null,
    chapterTitle: String = "",
    fictionTitle: String = "",
): UiPlaybackState = UiPlaybackState(
    fictionId = fictionId,
    chapterId = chapterId,
    chapterTitle = chapterTitle,
    fictionTitle = fictionTitle,
    coverUrl = null,
    isPlaying = false,
    positionMs = 0L,
    durationMs = 0L,
    sentenceStart = 0,
    sentenceEnd = 0,
    speed = 1f,
    pitch = 1f,
    voiceId = null,
    voiceLabel = "",
)

private class FakeFictionRepo(
    private val fictionId: String,
    private val title: String?,
) : FictionRepositoryUi {
    override val library: Flow<List<UiFiction>> = flowOf(emptyList())
    override val follows: Flow<List<UiFollow>> = flowOf(emptyList())
    override fun fictionById(id: String): Flow<UiFiction?> =
        flowOf(if (id == fictionId && title != null) uiFictionOf(id, title) else null)
    override fun fictionLoadError(id: String): Flow<String?> = flowOf(null)
    override fun chaptersFor(fictionId: String): Flow<List<UiChapter>> = flowOf(emptyList())
    override suspend fun setDownloadMode(fictionId: String, mode: DownloadMode) = Unit
    override suspend fun follow(fictionId: String, follow: Boolean) = Unit
    override suspend fun markAllCaughtUp() = Unit
    override suspend fun refreshFollows() = Unit
    override suspend fun addByUrl(url: String): UiAddByUrlResult = UiAddByUrlResult.UnrecognizedUrl
}

private fun uiFictionOf(id: String, title: String) = UiFiction(
    id = id,
    title = title,
    author = "",
    coverUrl = null,
    rating = 0f,
    chapterCount = 0,
    isOngoing = true,
    synopsis = "",
)

private class FakePlayback(initial: UiPlaybackState) : PlaybackControllerUi {
    override val state: StateFlow<UiPlaybackState> = MutableStateFlow(initial).asStateFlow()
    override val chapterText: Flow<String> = flowOf("")
    override fun play() = Unit
    override fun pause() = Unit
    override fun seekTo(ms: Long) = Unit
    override fun seekToChar(charOffset: Int) = Unit
    override fun skipForward() = Unit
    override fun skipBack() = Unit
    override fun nextChapter() = Unit
    override fun previousChapter() = Unit
    override fun setSpeed(speed: Float) = Unit
    override fun setPitch(pitch: Float) = Unit
    override fun setVoice(voiceId: String) = Unit
    override fun setPunctuationPauseMultiplier(multiplier: Float) = Unit
    override fun startListening(fictionId: String, chapterId: String, charOffset: Int) = Unit
    override fun startSleepTimer(mode: UiSleepTimerMode) = Unit
    override fun cancelSleepTimer() = Unit
}
