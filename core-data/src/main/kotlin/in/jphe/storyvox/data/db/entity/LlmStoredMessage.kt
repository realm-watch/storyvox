package `in`.jphe.storyvox.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One persisted chat turn within an [LlmSession]. Mirrors
 * cloud-chat-assistant's `messages` table.
 *
 * The wire-layer `LlmMessage` (in `:core-llm`) and this entity are
 * intentionally distinct types — wire messages don't carry an id or
 * timestamp; stored messages do. The repository converts.
 *
 * Role is stored as a string ("user" / "assistant") rather than as
 * an enum FK so the schema is forward-compatible if we add a
 * "system" role for chat surfaces (which Anthropic doesn't use but
 * OpenAI does).
 */
@Entity(
    tableName = "llm_message",
    foreignKeys = [
        ForeignKey(
            entity = LlmSession::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["sessionId"])],
)
data class LlmStoredMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val role: String,        // "user" / "assistant"
    val content: String,
    val createdAt: Long,
)
