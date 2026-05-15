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
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the captured WebView session for any auth-supporting source.
 *
 * Originally Royal-Road-only; #426 generalizes the store so AO3 (and any
 * future WebView-cookie-capture source) can share the same plumbing. Every
 * method takes an optional `sourceId` parameter — callers that don't pass
 * one get the historical Royal Road behaviour bit-identically, so existing
 * RR sign-in / sign-out / verify call sites keep compiling and behaving.
 *
 * The actual cookie value is persisted in `EncryptedSharedPreferences` —
 * never in Room — so it doesn't end up in the SQLite plaintext file. The
 * encrypted-prefs key is `cookie:$sourceId` (already keyed this way pre-
 * #426, so no migration is needed when AO3 starts writing through the
 * same store).
 *
 * Per-source [SessionState] flows are kept independent: capturing an AO3
 * session must not flip the RR state to `Authenticated` and vice versa.
 * The legacy `sessionState` accessor returns the RR flow so existing UI
 * observers (Settings sign-in row, [`in`.jphe.storyvox.data.work.SessionRefreshWorker])
 * see the same stream they did before.
 */
interface AuthRepository {

    /**
     * Hot stream of the Royal Road session state, initialized from disk
     * on first read. Equivalent to `sessionState(SourceIds.ROYAL_ROAD)`
     * — kept as a top-level field for backwards compatibility with the
     * pre-#426 call sites that observed a single source.
     */
    val sessionState: StateFlow<SessionState>

    /** Per-source session state. Each sourceId gets its own flow. */
    fun sessionState(sourceId: String): StateFlow<SessionState>

    /** Persist a captured WebView cookie. Transitions state → Authenticated. */
    suspend fun captureSession(
        cookieHeader: String,
        userDisplayName: String?,
        userId: String?,
        expiresAt: Long?,
        sourceId: String = SourceIds.ROYAL_ROAD,
    )

    /** Clear all session data for [sourceId]. Transitions state → Anonymous. */
    suspend fun clearSession(sourceId: String = SourceIds.ROYAL_ROAD)

    /**
     * Returns the raw Cookie header to attach to outgoing fetches for
     * [sourceId], or null if no session is captured for that source.
     */
    suspend fun cookieHeader(sourceId: String = SourceIds.ROYAL_ROAD): String?

    /**
     * Validate by hitting an authed endpoint on the source. Updates
     * `lastVerifiedAt` on success or transitions to [SessionState.Expired]
     * on auth failure. No-op (returns current state) for sources that
     * aren't wired up in the source map.
     */
    suspend fun verifyOrExpire(sourceId: String = SourceIds.ROYAL_ROAD): SessionState
}

@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val dao: AuthDao,
    private val prefs: SharedPreferences,
    private val sources: Map<String, @JvmSuppressWildcards FictionSource>,
    @ApplicationScope private val appScope: CoroutineScope,
) : AuthRepository {

    // Per-source state flows. ConcurrentHashMap because [sessionState] /
    // capture / clear can be called from any thread — Hilt-scoped singletons
    // don't synchronize their callers for us.
    private val states: MutableMap<String, MutableStateFlow<SessionState>> =
        ConcurrentHashMap()

    private fun stateFlow(sourceId: String): MutableStateFlow<SessionState> =
        states.getOrPut(sourceId) { MutableStateFlow(SessionState.Anonymous) }

    override val sessionState: StateFlow<SessionState>
        get() = stateFlow(SourceIds.ROYAL_ROAD).asStateFlow()

    override fun sessionState(sourceId: String): StateFlow<SessionState> =
        stateFlow(sourceId).asStateFlow()

    private fun cookieKey(sourceId: String): String = "$COOKIE_KEY_PREFIX$sourceId"

    private companion object {
        // EncryptedSharedPreferences key prefix. Pre-#426 this string was
        // inlined as "cookie:$sourceId"; the constant centralizes it so
        // init() can scan disk for every persisted source without that
        // string drifting between the writer and the scanner.
        const val COOKIE_KEY_PREFIX = "cookie:"
    }

    init {
        // Hydrate every source we have a cookie for on construction.
        // Pre-#426 this was hardcoded to ROYAL_ROAD; #426 scans every
        // `cookie:*` key on disk so an AO3 cookie persisted in PR2 gets
        // the same on-construction hydration even if the AO3 source
        // binding isn't loaded yet. Hilt creates this on the singleton
        // component, so blocking IO here is acceptable (it runs before
        // any coroutine touches a StateFlow).
        //
        // Walking the prefs (not `sources.keys`) is what keeps disk
        // state authoritative: the cookie store is keyed by sourceId,
        // not by FictionSource binding, and the prior contract was
        // "if a cookie exists on disk, surface Authenticated immediately
        // on cold start" — regardless of whether the FictionSource is
        // currently wired.
        val cookieKeys: Set<String> = prefs.all.keys
            .filter { it.startsWith(COOKIE_KEY_PREFIX) }
            .toSet()
        val candidateIds: Set<String> = cookieKeys
            .map { it.removePrefix(COOKIE_KEY_PREFIX) }
            .toSet() + SourceIds.ROYAL_ROAD
        for (sourceId in candidateIds) {
            val cookie = prefs.getString(cookieKey(sourceId), null) ?: continue
            // Best-effort: pull metadata sync from a parallel scope; the
            // StateFlow is updated as soon as the row arrives. Until then,
            // treat as Authenticated with whatever metadata is in the cookie
            // header itself — Anonymous would be a wrong default since the
            // disk store says we have a session.
            stateFlow(sourceId).value = SessionState.Authenticated(
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
                        stateFlow(sourceId).value = SessionState.Authenticated(
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
        sourceId: String,
    ) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        prefs.edit { putString(cookieKey(sourceId), cookieHeader) }
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
        stateFlow(sourceId).value =
            SessionState.Authenticated(cookieHeader, expiresAt, userDisplayName)
    }

    override suspend fun clearSession(sourceId: String) = withContext(Dispatchers.IO) {
        prefs.edit { remove(cookieKey(sourceId)) }
        dao.clear(sourceId)
        stateFlow(sourceId).value = SessionState.Anonymous
    }

    override suspend fun cookieHeader(sourceId: String): String? = withContext(Dispatchers.IO) {
        prefs.getString(cookieKey(sourceId), null)
    }

    override suspend fun verifyOrExpire(sourceId: String): SessionState = withContext(Dispatchers.IO) {
        val state = stateFlow(sourceId)
        // Capture the cookie up front. Re-reading from prefs after the
        // network call would race with a concurrent clearSession() and
        // the prior `cookieHeader()!!` would crash on the resulting null.
        val cookie = cookieHeader(sourceId) ?: run {
            state.value = SessionState.Anonymous
            return@withContext state.value
        }
        val source = sources[sourceId]
        if (source == null) {
            // The source isn't bound (e.g. a test fixture or a source
            // module disabled at compile time). We can't verify, but the
            // cookie store says we're authed — keep the existing state
            // rather than hard-erroring. Pre-#426 this branch couldn't
            // exist (RR was the only binding and was checked at init);
            // post-#426 it's reachable when callers verify a source that
            // hasn't been wired yet (e.g. AO3 between PR1 and PR2).
            return@withContext state.value
        }
        when (source.followsList(page = 1)) {
            is FictionResult.Success -> {
                dao.touchVerified(sourceId, System.currentTimeMillis())
                // If the session was cleared mid-flight, don't reinstate it.
                if (cookieHeader(sourceId) == null) {
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
