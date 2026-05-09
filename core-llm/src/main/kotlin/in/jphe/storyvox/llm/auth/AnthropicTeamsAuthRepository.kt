package `in`.jphe.storyvox.llm.auth

import `in`.jphe.storyvox.llm.LlmCredentialsStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Public Teams (OAuth) session surface (#181).
 *
 * Mirrors the [`in`.jphe.storyvox.source.github.auth.GitHubAuthRepository]
 * shape — a hot StateFlow that the UI layer subscribes to so the
 * Settings row updates the moment a sign-in completes (or the refresh
 * token gets revoked mid-session).
 *
 * The token + refresh + scope cache live in [LlmCredentialsStore]
 * (encrypted prefs); this class is the in-memory mirror so callers
 * don't need to poll prefs to see state changes. The [`AnthropicTeamsProvider`]
 * also clears the session via [LlmCredentialsStore.clearTeamsSession]
 * on `invalid_grant` — we listen there too via [refreshFromStore].
 */
sealed interface TeamsSession {
    data object SignedOut : TeamsSession
    data class SignedIn(val scopes: String) : TeamsSession
}

@Singleton
open class AnthropicTeamsAuthRepository @Inject constructor(
    private val store: LlmCredentialsStore,
) {
    private val _state = MutableStateFlow<TeamsSession>(initialState())
    val sessionState: StateFlow<TeamsSession> = _state.asStateFlow()

    /** Persist a freshly-issued session and flip the in-memory flag. */
    fun captureSession(
        bearer: String,
        refreshToken: String?,
        expiresAtEpochMillis: Long,
        scopes: String,
    ) {
        store.setTeamsSession(
            bearer = bearer,
            refreshToken = refreshToken,
            expiresAtEpochMillis = expiresAtEpochMillis,
            scopes = scopes,
        )
        _state.value = TeamsSession.SignedIn(scopes = scopes)
    }

    /** Forget the entire Teams session (local-only — see [`SettingsRepositoryUi.signOutTeams`]). */
    fun clearSession() {
        store.clearTeamsSession()
        _state.value = TeamsSession.SignedOut
    }

    /**
     * Re-read prefs and emit. Called by callers that bypass [captureSession]
     * — for now that's just [`AnthropicTeamsProvider.refreshOrInvalidate`]
     * when it wipes the session on `invalid_grant`.
     */
    fun refreshFromStore() {
        _state.value = initialState()
    }

    private fun initialState(): TeamsSession =
        if (store.hasTeamsToken) {
            TeamsSession.SignedIn(scopes = store.teamsScopes().orEmpty())
        } else {
            TeamsSession.SignedOut
        }
}
