package `in`.jphe.storyvox.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import `in`.jphe.storyvox.data.db.entity.AuthCookie
import kotlinx.coroutines.flow.Flow

@Dao
interface AuthDao {

    @Query("SELECT * FROM auth_cookie WHERE sourceId = :sourceId")
    fun observe(sourceId: String): Flow<AuthCookie?>

    @Query("SELECT * FROM auth_cookie WHERE sourceId = :sourceId")
    suspend fun get(sourceId: String): AuthCookie?

    @Upsert
    suspend fun upsert(cookie: AuthCookie)

    @Query("UPDATE auth_cookie SET lastVerifiedAt = :now WHERE sourceId = :sourceId")
    suspend fun touchVerified(sourceId: String, now: Long)

    @Query("DELETE FROM auth_cookie WHERE sourceId = :sourceId")
    suspend fun clear(sourceId: String)
}
