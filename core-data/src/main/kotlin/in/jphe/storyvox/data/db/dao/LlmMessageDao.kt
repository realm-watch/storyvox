package `in`.jphe.storyvox.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import `in`.jphe.storyvox.data.db.entity.LlmStoredMessage
import kotlinx.coroutines.flow.Flow

/**
 * CRUD for individual chat turns within an [LlmSession]. The cascade
 * delete on `LlmStoredMessage.sessionId` means deleting a session
 * automatically deletes its messages — no explicit cleanup required.
 */
@Dao
interface LlmMessageDao {

    @Insert
    suspend fun insert(message: LlmStoredMessage): Long

    /** Live stream — chat UI subscribes to this so the message list
     *  updates as both user input and streamed assistant replies
     *  land. */
    @Query("SELECT * FROM llm_message WHERE sessionId = :sessionId ORDER BY id ASC")
    fun observeBySession(sessionId: String): Flow<List<LlmStoredMessage>>

    /** One-shot snapshot for prompt-building. Used by the session
     *  repository when constructing the messages array to send to
     *  the LLM. */
    @Query("SELECT * FROM llm_message WHERE sessionId = :sessionId ORDER BY id ASC")
    suspend fun getBySession(sessionId: String): List<LlmStoredMessage>

    @Query("DELETE FROM llm_message WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: String)
}
