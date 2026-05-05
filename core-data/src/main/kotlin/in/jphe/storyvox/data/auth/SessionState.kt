package `in`.jphe.storyvox.data.auth

/**
 * Tri-state session — observed as a [kotlinx.coroutines.flow.StateFlow] from
 * [`in`.jphe.storyvox.data.repository.AuthRepository].
 *
 * The cookie header is held in-memory on the [Authenticated] value so the
 * playback layer doesn't need a suspend call to fetch it on every outgoing
 * request. The persisted secret lives in `EncryptedSharedPreferences`; this
 * in-memory copy is regenerated from disk on app start.
 */
sealed interface SessionState {
    data object Anonymous : SessionState
    data class Authenticated(
        val cookieHeader: String,
        val expiresAt: Long?,
        val userDisplayName: String? = null,
    ) : SessionState
    data object Expired : SessionState
}
