package `in`.jphe.storyvox.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One AI chat session. Mirrors cloud-chat-assistant's `sessions`
 * table (`name PK, created_at`) extended with the session's bound
 * provider, model, and optional per-session system prompt.
 *
 * Sessions can be free-form ([featureKind] == null) or attached to
 * a feature ([featureKind] == ChapterRecap, etc.). Feature sessions
 * are hidden from the main session list by default — they're more
 * "history of what the user asked the librarian" than "ongoing
 * conversations".
 *
 * See `2026-05-08-ai-integration-design.md` "Multi-session storage"
 * section for the design rationale.
 */
@Entity(tableName = "llm_session")
data class LlmSession(
    /** UUID for free-form sessions; deterministic for feature
     *  sessions (e.g. "recap:$fictionId:$chapterId") so a duplicate
     *  recap of the same chapter overwrites the prior session
     *  instead of accumulating clutter. */
    @PrimaryKey val id: String,
    /** User-visible label. For feature sessions, auto-generated
     *  ("Recap of Sky Pride · ch.8"). For free-form, user-editable
     *  in the chat UI. */
    val name: String,
    /** Session is bound to a specific provider. Stored as the
     *  ProviderId enum's `name` to keep the column schema-stable
     *  across enum reorderings. */
    val provider: String,
    /** Bound model id, in canonical form ("claude-haiku-4.5",
     *  "gpt-4o-mini", "llama3.3"). */
    val model: String,
    /** Optional per-session system prompt, overriding any global
     *  default. Feature sessions set this (the librarian recap
     *  prompt). */
    val systemPrompt: String? = null,
    val createdAt: Long,
    val lastUsedAt: Long,
    /** When non-null, this session was auto-created for a feature
     *  rather than by the user — UI hides it from the main list by
     *  default. Stored as the FeatureKind enum's `name` for
     *  schema-stability. Null = free-form session. */
    val featureKind: String? = null,
    /** For feature sessions: the fiction this session was anchored
     *  to, so a returning user can see "the recap I asked for on
     *  Sky Pride". */
    val anchorFictionId: String? = null,
    val anchorChapterId: String? = null,
)
