package `in`.jphe.storyvox.llm

import `in`.jphe.storyvox.data.db.dao.LlmMessageDao
import `in`.jphe.storyvox.data.db.dao.LlmSessionDao
import `in`.jphe.storyvox.data.db.entity.LlmSession
import `in`.jphe.storyvox.data.db.entity.LlmStoredMessage
import `in`.jphe.storyvox.llm.tools.ChatStreamEvent
import `in`.jphe.storyvox.llm.tools.ToolRegistry
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.map

/** Top-level kinds of feature-attached sessions. Stored as the
 *  enum's name on [LlmSession.featureKind]. */
enum class FeatureKind { ChapterRecap, CharacterLookup }

/**
 * Multi-session CRUD + chat plumbing. Mirrors cloud-chat-assistant's
 * `create_session` / `switch_session` / `delete_session` /
 * `list_sessions` shape.
 *
 * Sessions are bound to a specific provider + model — switching the
 * global active provider in Settings does NOT change a session's
 * provider. This lets a user have one session on Claude (cost-aware
 * deep recap), one on Ollama (privacy-preserving casual Q&A), etc.,
 * without globally toggling.
 *
 * The introducing PR (#81) ships the schema + this repository +
 * read-only access from Settings; the chat-UI surface is a
 * follow-up. Feature sessions (Chapter Recap) use this storage
 * indirectly via [ChapterRecap], which auto-creates a session per
 * recap so the user can review past recaps in Settings → AI →
 * Sessions.
 */
@Singleton
open class LlmSessionRepository @Inject constructor(
    private val sessionDao: LlmSessionDao,
    private val messageDao: LlmMessageDao,
    private val llm: LlmRepository,
) {

    /** All sessions, newest-used first. UI should filter on
     *  `featureKind != null` to split free-form vs. feature views. */
    open fun observeSessions(): Flow<List<SessionView>> =
        sessionDao.observeAll().map { rows -> rows.map { it.toView() } }

    /** Live stream of messages in a session. */
    open fun observeMessages(sessionId: String): Flow<List<LlmMessage>> =
        messageDao.observeBySession(sessionId).map { rows ->
            rows.mapNotNull { it.toWire() }
        }

    /**
     * Create a new session. For free-form sessions, [id] is
     * auto-generated; for feature sessions, callers pass a
     * deterministic id (e.g. "recap:fictionId:chapterId") so a
     * second recap on the same chapter overwrites the first record.
     */
    open suspend fun createSession(
        name: String,
        provider: ProviderId,
        model: String,
        systemPrompt: String? = null,
        featureKind: FeatureKind? = null,
        anchorFictionId: String? = null,
        anchorChapterId: String? = null,
        explicitId: String? = null,
    ): String {
        val id = explicitId ?: UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        sessionDao.upsert(
            LlmSession(
                id = id,
                name = name,
                provider = provider.name,
                model = model,
                systemPrompt = systemPrompt,
                createdAt = now,
                lastUsedAt = now,
                featureKind = featureKind?.name,
                anchorFictionId = anchorFictionId,
                anchorChapterId = anchorChapterId,
            ),
        )
        return id
    }

    /**
     * Send [userMessage] in [sessionId], stream the reply, persist
     * both turns. The user message is persisted before the stream
     * starts so a process death mid-stream doesn't lose what the
     * user said. The assistant message is persisted on stream
     * completion (success path only — partial replies on cancel are
     * NOT saved, consistent with how chat surfaces typically behave).
     */
    open fun chat(sessionId: String, userMessage: String): Flow<String> = flow {
        val session = sessionDao.get(sessionId)
            ?: throw IllegalStateException("Session $sessionId not found")
        val provider = ProviderId.valueOf(session.provider)

        // Persist user turn now.
        messageDao.insert(
            LlmStoredMessage(
                sessionId = sessionId,
                role = "user",
                content = userMessage,
                createdAt = System.currentTimeMillis(),
            ),
        )
        val history = messageDao.getBySession(sessionId).mapNotNull { it.toWire() }

        val replyBuf = StringBuilder()
        val replyFlow = llm.streamWith(
            provider = provider,
            messages = history,
            systemPrompt = session.systemPrompt,
            model = session.model,
        )
            .onEach { replyBuf.append(it) }
            .onCompletion { cause ->
                if (cause == null) {
                    messageDao.insert(
                        LlmStoredMessage(
                            sessionId = sessionId,
                            role = "assistant",
                            content = replyBuf.toString(),
                            createdAt = System.currentTimeMillis(),
                        ),
                    )
                    sessionDao.touchLastUsed(
                        sessionId,
                        System.currentTimeMillis(),
                    )
                }
            }
        emitAll(replyFlow)
    }

    /**
     * Issue #216 — tool-aware variant of [chat]. Same persistence
     * shape (user turn before stream, assistant turn on completion),
     * but emits [ChatStreamEvent] instead of plain strings so the
     * chat ViewModel can render tool-call cards in the timeline.
     *
     * When [tools] is empty the underlying provider falls through to
     * plain streaming and the only events emitted are
     * [ChatStreamEvent.TextDelta]. The persistence side concatenates
     * every [ChatStreamEvent.TextDelta] into the saved assistant
     * message; tool-call events are NOT persisted in v1 (the chat
     * timeline shows them live and they disappear on rehydration).
     * A future PR can add a `LlmToolCall` table when we want them to
     * survive process death.
     */
    open fun chatWithTools(
        sessionId: String,
        userMessage: String,
        tools: ToolRegistry,
    ): Flow<ChatStreamEvent> = flow {
        val session = sessionDao.get(sessionId)
            ?: throw IllegalStateException("Session $sessionId not found")
        val provider = ProviderId.valueOf(session.provider)

        messageDao.insert(
            LlmStoredMessage(
                sessionId = sessionId,
                role = "user",
                content = userMessage,
                createdAt = System.currentTimeMillis(),
            ),
        )
        val history = messageDao.getBySession(sessionId).mapNotNull { it.toWire() }

        val replyBuf = StringBuilder()
        val replyFlow = llm.chatWithToolsOn(
            provider = provider,
            messages = history,
            systemPrompt = session.systemPrompt,
            model = session.model,
            tools = tools,
        )
            .onEach { event ->
                if (event is ChatStreamEvent.TextDelta) {
                    replyBuf.append(event.text)
                }
            }
            .onCompletion { cause ->
                if (cause == null) {
                    messageDao.insert(
                        LlmStoredMessage(
                            sessionId = sessionId,
                            role = "assistant",
                            content = replyBuf.toString(),
                            createdAt = System.currentTimeMillis(),
                        ),
                    )
                    sessionDao.touchLastUsed(
                        sessionId,
                        System.currentTimeMillis(),
                    )
                }
            }
        emitAll(replyFlow)
    }

    open suspend fun deleteSession(sessionId: String) {
        sessionDao.delete(sessionId)   // cascades to messages
    }

    /**
     * Issue #212 — replace [sessionId]'s persisted system prompt
     * in-place. Used by the chat ViewModel to swap in a freshly
     * built prompt before each send when grounding settings have
     * changed. No-op (silent) if the session doesn't exist yet —
     * the next [createSession] will set the prompt.
     */
    open suspend fun updateSystemPrompt(sessionId: String, systemPrompt: String?) {
        sessionDao.updateSystemPrompt(sessionId, systemPrompt)
    }
}

/** UI-facing projection of [LlmSession] with strongly-typed
 *  enum fields. Settings UI consumes this. */
data class SessionView(
    val id: String,
    val name: String,
    val provider: ProviderId,
    val model: String,
    val systemPrompt: String?,
    val createdAt: Long,
    val lastUsedAt: Long,
    val featureKind: FeatureKind?,
    val anchorFictionId: String?,
    val anchorChapterId: String?,
)

private fun LlmSession.toView(): SessionView = SessionView(
    id = id,
    name = name,
    // Defensive — if the DB has a value that doesn't parse, surface
    // it as Claude (the safest default). Realistically this only
    // happens if a future build downgrades, which we don't support.
    provider = runCatching { ProviderId.valueOf(provider) }
        .getOrDefault(ProviderId.Claude),
    model = model,
    systemPrompt = systemPrompt,
    createdAt = createdAt,
    lastUsedAt = lastUsedAt,
    featureKind = featureKind?.let {
        runCatching { FeatureKind.valueOf(it) }.getOrNull()
    },
    anchorFictionId = anchorFictionId,
    anchorChapterId = anchorChapterId,
)

private fun LlmStoredMessage.toWire(): LlmMessage? {
    val role = when (role) {
        "user" -> LlmMessage.Role.user
        "assistant" -> LlmMessage.Role.assistant
        else -> return null
    }
    return LlmMessage(role, content)
}
