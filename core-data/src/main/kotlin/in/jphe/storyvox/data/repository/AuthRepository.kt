package `in`.jphe.storyvox.data.repository

import android.content.SharedPreferences
import `in`.jphe.storyvox.data.auth.SessionState
import `in`.jphe.storyvox.data.coroutines.ApplicationScope
import `in`.jphe.storyvox.data.db.dao.AuthDao
import `in`.jphe.storyvox.data.db.entity.AuthCookie
import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.data.source.SourceIds
import `in`.jphe.storyvox.data.source.model.FictionResult
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the captured WebView session for a single source (Royal Road in v1).
 *
 * The actual cookie value is persisted in `EncryptedSharedPreferences` —
 * never in Room — so it doesn't end up in the SQLite plaintext file.
 */
interface AuthRepository {

    /** Hot stream of the session state. Initialized from disk on first read. */
    val sessionState: StateFlow<SessionState>

    /** Persist a captured WebView cookie. Transitions state → Authenticated. */
    suspend fun captureSession(
        cookieHeader: String,
        userDisplayName: String?,
        userId: String?,
        expiresAt: Long?,
    )

    /** Clear all session data. Transitions state → Anonymous. */
    suspend fun clearSession()

    /** Returns the raw Cookie header to attach to outgoing fetches, or null. */
    suspend fun cookieHeader(): String?

    /**
     * Validate by hitting an authed endpoint. Updates `lastVerifiedAt` on
     * success or transitions to [SessionState.Expired] on auth failure.
     */
    suspend fun verifyOrExpire(): SessionState
}

@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val dao: AuthDao,
    private val prefs: SharedPreferences,
    private val sources: Map<String, @JvmSuppressWildcards FictionSource>,
    @ApplicationScope private val appScope: CoroutineScope,
) : AuthRepository {

    private val state = MutableStateFlow<SessionState>(SessionState.Anonymous)
    override val sessionState: StateFlow<SessionState> = state.asStateFlow()

    // Auth is per-source. Royal Road is the only source with a
    // SessionHydrator wired today; GitHub source (3d-detail-and-chapter
    // and beyond) has no auth flow until step 3f adds optional PAT
    // support. Pin to Royal Road explicitly. When GitHub auth lands
    // this becomes a per-call lookup — the cookie store is already
    // keyed `cookie:$sourceId` so the data layer doesn't need
    // migration.
    private val source: FictionSource = sources[SourceIds.ROYAL_ROAD]
        ?: error("AuthRepository: expected $sources to bind ${SourceIds.ROYAL_ROAD}; got ${sources.keys}")
    private val sourceId: String get() = source.id
    private val cookieKey: String get() = "cookie:$sourceId"

    init {
        // Hydrate on construction from the encrypted store + DAO row.
        // Hilt creates this on the singleton component, so blocking IO here
        // is acceptable (it runs before any coroutine touches the StateFlow).
        val cookie = prefs.getString(cookieKey, null)
        if (cookie != null) {
            // Best-effort: pull metadata sync from a parallel scope; the
            // StateFlow is updated as soon as the row arrives. Until then,
            // treat as Authenticated with whatever metadata is in the cookie
            // header itself — Anonymous would be a wrong default since the
            // disk store says we have a session.
            state.value = SessionState.Authenticated(
                cookieHeader = cookie,
                expiresAt = null,
                userDisplayName = null,
            )
            // Hydrate the rest of the session metadata off the main thread.
            // Routed through the injected @ApplicationScope (SupervisorJob +
            // Dispatchers.Default) so:
            //   - a throw from dao.get() doesn't poison sibling coroutines,
            //   - the coroutine is part of a structured tree (cancellable in
            //     tests and torn down with the process), and
            //   - tests can swap a TestScope without mocking the global launch.
            appScope.launch {
                withContext(Dispatchers.IO) {
                    val row = dao.get(sourceId)
                    if (row != null) {
                        state.value = SessionState.Authenticated(
                            cookieHeader = cookie,
                            expiresAt = row.expiresAt,
                            userDisplayName = row.userDisplayName,
                        )
                    }
                }
            }
        }
    }

    override suspend fun captureSession(
        cookieHeader: String,
        userDisplayName: String?,
        userId: String?,
        expiresAt: Long?,
    ) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        prefs.edit { putString(cookieKey, cookieHeader) }
        dao.upsert(
            AuthCookie(
                sourceId = sourceId,
                userDisplayName = userDisplayName,
                userId = userId,
                capturedAt = now,
                expiresAt = expiresAt,
                lastVerifiedAt = now,
            ),
        )
        state.value = SessionState.Authenticated(cookieHeader, expiresAt, userDisplayName)
    }

    override suspend fun clearSession() = withContext(Dispatchers.IO) {
        prefs.edit { remove(cookieKey) }
        dao.clear(sourceId)
        state.value = SessionState.Anonymous
    }

    override suspend fun cookieHeader(): String? = withContext(Dispatchers.IO) {
        prefs.getString(cookieKey, null)
    }

    override suspend fun verifyOrExpire(): SessionState = withContext(Dispatchers.IO) {
        // Capture the cookie up front. Re-reading from prefs after the
        // network call would race with a concurrent clearSession() and
        // the prior `cookieHeader()!!` would crash on the resulting null.
        val cookie = cookieHeader() ?: run {
            state.value = SessionState.Anonymous
            return@withContext state.value
        }
        when (val result = source.followsList(page = 1)) {
            is FictionResult.Success -> {
                dao.touchVerified(sourceId, System.currentTimeMillis())
                // If the session was cleared mid-flight, don't reinstate it.
                if (cookieHeader() == null) {
                    state.value = SessionState.Anonymous
                } else {
                    // Keep current state; refresh expiresAt from latest row.
                    val row = dao.get(sourceId)
                    state.value = SessionState.Authenticated(
                        cookieHeader = cookie,
                        expiresAt = row?.expiresAt,
                        userDisplayName = row?.userDisplayName,
                    )
                }
            }
            is FictionResult.AuthRequired -> state.value = SessionState.Expired
            is FictionResult.Failure -> {
                // Network/Cloudflare/RateLimited — don't expire, just don't bump.
            }
        }
        state.value
    }
}
