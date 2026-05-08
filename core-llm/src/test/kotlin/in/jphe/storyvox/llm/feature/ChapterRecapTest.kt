package `in`.jphe.storyvox.llm.feature

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import `in`.jphe.storyvox.data.db.StoryvoxDatabase
import `in`.jphe.storyvox.data.db.entity.Chapter
import `in`.jphe.storyvox.data.db.entity.ChapterDownloadState
import `in`.jphe.storyvox.data.db.entity.Fiction
import `in`.jphe.storyvox.llm.LlmConfig
import `in`.jphe.storyvox.llm.LlmError
import `in`.jphe.storyvox.llm.LlmMessage
import `in`.jphe.storyvox.llm.LlmProvider
import `in`.jphe.storyvox.llm.LlmRepository
import `in`.jphe.storyvox.llm.ProbeResult
import `in`.jphe.storyvox.llm.ProviderId
import `in`.jphe.storyvox.llm.provider.ClaudeApiProvider
import `in`.jphe.storyvox.llm.provider.FakeStore
import `in`.jphe.storyvox.llm.provider.OllamaProvider
import `in`.jphe.storyvox.llm.provider.OpenAiApiProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ChapterRecapTest {

    private lateinit var db: StoryvoxDatabase
    private lateinit var recap: ChapterRecap
    private lateinit var fakeProvider: FakeProvider
    private lateinit var llm: LlmRepository

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, StoryvoxDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        // Seed a fiction + 5 chapters with plain text bodies.
        runBlocking {
            db.fictionDao().upsert(
                Fiction(
                    id = "f1",
                    title = "Sky Pride",
                    sourceId = "royalroad",
                    author = "Anonymous",
                    inLibrary = true,
                    firstSeenAt = 0L,
                    metadataFetchedAt = 0L,
                ),
            )
            (1..5).forEach { i ->
                db.chapterDao().upsert(
                    Chapter(
                        id = "f1:$i",
                        fictionId = "f1",
                        sourceChapterId = "$i",
                        index = i,
                        title = "Chapter $i",
                        plainBody = "Body of chapter $i. ".repeat(50),
                        downloadState = ChapterDownloadState.DOWNLOADED,
                    ),
                )
            }
        }

        fakeProvider = FakeProvider()
        // Stub out the real provider classes — LlmRepository takes
        // them as constructor params; pass the fake for all three.
        llm = LlmRepository(
            configFlow = flowOf(LlmConfig(provider = ProviderId.Claude)),
            claude = fakeProvider.asClaude(),
            openAi = fakeProvider.asOpenAi(),
            ollama = fakeProvider.asOllama(),
        )
        recap = ChapterRecap(
            chapterDao = db.chapterDao(),
            fictionDao = db.fictionDao(),
            llm = llm,
            configFlow = flowOf(
                LlmConfig(provider = ProviderId.Claude, sendChapterTextEnabled = true),
            ),
        )
    }

    @After fun tearDown() { db.close() }

    @Test
    fun `recap emits provider tokens`() = runTest {
        fakeProvider.tokens = listOf("In ", "Sky Pride, ", "things ", "happened.")
        val out = recap.recap("f1", "f1:3").toList().joinToString("")
        assertEquals("In Sky Pride, things happened.", out)
    }

    @Test
    fun `recap pulls last 3 chapters by default`() = runTest {
        fakeProvider.tokens = listOf("ok")
        recap.recap("f1", "f1:5").toList()
        val sent = fakeProvider.lastMessages
        assertNotNull(sent)
        // Single user message; check it includes chapter 3, 4, 5
        // titles + bodies but NOT chapter 2 (out of window).
        val userMsg = sent!!.first { it.role == LlmMessage.Role.user }.content
        assertTrue(userMsg.contains("Chapter 3"))
        assertTrue(userMsg.contains("Chapter 4"))
        assertTrue(userMsg.contains("Chapter 5"))
        assertTrue(!userMsg.contains("Chapter 2"))
    }

    @Test
    fun `recap respects window of 1`() = runTest {
        fakeProvider.tokens = listOf("ok")
        recap.recap("f1", "f1:5", recapWindow = 1).toList()
        val userMsg = fakeProvider.lastMessages!!
            .first { it.role == LlmMessage.Role.user }.content
        assertTrue(userMsg.contains("Chapter 5"))
        assertTrue(!userMsg.contains("Chapter 4"))
    }

    @Test
    fun `recap clamps window at start of fiction`() = runTest {
        fakeProvider.tokens = listOf("ok")
        // currentChapter = 2, window = 5 → expect chapters 1, 2.
        recap.recap("f1", "f1:2", recapWindow = 5).toList()
        val userMsg = fakeProvider.lastMessages!!
            .first { it.role == LlmMessage.Role.user }.content
        assertTrue(userMsg.contains("Chapter 1"))
        assertTrue(userMsg.contains("Chapter 2"))
        assertTrue(!userMsg.contains("Chapter 3"))
    }

    @Test
    fun `recap throws NotConfigured when toggle is off`() = runTest {
        val r = ChapterRecap(
            chapterDao = db.chapterDao(),
            fictionDao = db.fictionDao(),
            llm = llm,
            configFlow = flowOf(
                LlmConfig(
                    provider = ProviderId.Claude,
                    sendChapterTextEnabled = false,
                ),
            ),
        )
        assertThrows(LlmError.NotConfigured::class.java) {
            runBlocking { r.recap("f1", "f1:3").toList() }
        }
    }

    @Test
    fun `recap throws NotConfigured when AI is off`() = runTest {
        val r = ChapterRecap(
            chapterDao = db.chapterDao(),
            fictionDao = db.fictionDao(),
            llm = llm,
            configFlow = flowOf(LlmConfig(provider = null)),
        )
        assertThrows(LlmError.NotConfigured::class.java) {
            runBlocking { r.recap("f1", "f1:3").toList() }
        }
    }

    @Test
    fun `recap returns empty when chapter is unknown`() = runTest {
        fakeProvider.tokens = listOf("never reached")
        val out = recap.recap("f1", "f1:nonexistent").toList()
        assertTrue(out.isEmpty())
    }
}

/** Single-shape fake provider used for all three id slots. */
private class FakeProvider {
    var tokens: List<String> = emptyList()
    var lastMessages: List<LlmMessage>? = null
    var lastSystemPrompt: String? = null

    fun asClaude(): ClaudeApiProvider = object : ClaudeApiProvider(
        http = OkHttpClient(),
        store = FakeStore(),
        configFlow = flowOf(LlmConfig()),
        json = Json,
    ) {
        override fun stream(
            messages: List<LlmMessage>,
            systemPrompt: String?,
            model: String?,
        ): Flow<String> {
            lastMessages = messages
            lastSystemPrompt = systemPrompt
            return flowOf(*tokens.toTypedArray())
        }
        override suspend fun probe(): ProbeResult = ProbeResult.Ok
    }

    fun asOpenAi(): OpenAiApiProvider = object : OpenAiApiProvider(
        http = OkHttpClient(),
        store = FakeStore(),
        configFlow = flowOf(LlmConfig()),
        json = Json,
    ) {
        override fun stream(
            messages: List<LlmMessage>,
            systemPrompt: String?,
            model: String?,
        ): Flow<String> {
            lastMessages = messages
            lastSystemPrompt = systemPrompt
            return flowOf(*tokens.toTypedArray())
        }
        override suspend fun probe(): ProbeResult = ProbeResult.Ok
    }

    fun asOllama(): OllamaProvider = object : OllamaProvider(
        http = OkHttpClient(),
        configFlow = flowOf(LlmConfig()),
        json = Json,
    ) {
        override fun stream(
            messages: List<LlmMessage>,
            systemPrompt: String?,
            model: String?,
        ): Flow<String> {
            lastMessages = messages
            lastSystemPrompt = systemPrompt
            return flowOf(*tokens.toTypedArray())
        }
        override suspend fun probe(): ProbeResult = ProbeResult.Ok
    }
}
