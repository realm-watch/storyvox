package `in`.jphe.storyvox.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import `in`.jphe.storyvox.data.db.entity.LlmSession
import kotlinx.coroutines.flow.Flow

/**
 * CRUD for AI chat sessions. See `LlmSession` entity for the schema
 * and `2026-05-08-ai-integration-design.md` "Multi-session storage"
 * section for the design rationale.
 */
@Dao
interface LlmSessionDao {

    @Upsert
    suspend fun upsert(session: LlmSession)

    /** All sessions, newest-used first. Free-form + feature sessions
     *  are returned together; the UI filters by [LlmSession.featureKind]
     *  to split the views. */
    @Query("SELECT * FROM llm_session ORDER BY lastUsedAt DESC")
    fun observeAll(): Flow<List<LlmSession>>

    @Query("SELECT * FROM llm_session WHERE id = :id")
    suspend fun get(id: String): LlmSession?

    @Query("UPDATE llm_session SET lastUsedAt = :ts WHERE id = :id")
    suspend fun touchLastUsed(id: String, ts: Long)

    @Query("DELETE FROM llm_session WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM llm_session")
    suspend fun deleteAll()
}
