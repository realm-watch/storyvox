package `in`.jphe.storyvox.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Metadata about a captured WebView session — NOT the cookie itself. The raw
 * cookie header lives in `EncryptedSharedPreferences` (key `cookie:$sourceId`)
 * so the secret never sits in a plaintext SQLite file.
 *
 * This row exists so the rest of the app can observe session state via Flow
 * without touching the encrypted store on the main thread.
 */
@Entity(tableName = "auth_cookie")
data class AuthCookie(
    @PrimaryKey val sourceId: String,
    val userDisplayName: String? = null,
    val userId: String? = null,
    val capturedAt: Long,
    val expiresAt: Long? = null,
    val lastVerifiedAt: Long,
)
