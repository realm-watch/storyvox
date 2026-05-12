package `in`.jphe.storyvox.auth.github

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import `in`.jphe.storyvox.source.github.auth.DeviceCodeResult
import `in`.jphe.storyvox.source.github.auth.DeviceFlowApi
import `in`.jphe.storyvox.source.github.auth.GitHubAuthConfig
import `in`.jphe.storyvox.source.github.auth.GitHubAuthRepository
import `in`.jphe.storyvox.source.github.auth.GitHubProfileService
import `in`.jphe.storyvox.source.github.auth.GitHubScopePreferences
import `in`.jphe.storyvox.source.github.auth.ProfileResult
import `in`.jphe.storyvox.source.github.auth.TokenPollResult
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * State machine for the GitHub Device Flow sign-in modal (#91).
 *
 * Spec § Login flow UX, lines 304-347 of
 * `docs/superpowers/specs/2026-05-08-github-oauth-design.md`.
 *
 * States and transitions:
 *
 * ```
 *  Idle ──[start]──▶ RequestingCode ──[ok]──▶ AwaitingUser ──[poll ok]──▶ Capturing
 *    ▲                    │                       │                          │
 *    │                    │                       ├─[denied]──▶ Denied       │
 *    └────[error/back]────┴───────────────────────┴─[expired]──▶ Expired     │
 *                                                                            ▼
 *                                                                         Captured
 * ```
 *
 * Polling cadence is RFC 8628 + GitHub-specific:
 * - Initial interval from device-code response (typically 5s).
 * - On `slow_down`, bump interval by +5s and continue.
 * - On `authorization_pending`, just wait the next interval.
 * - On `expired_token`, stop and surface "Code expired."
 * - On `access_denied`, stop and surface "Sign-in cancelled."
 * - On network error: silent retry once, then surface "Network error."
 */
@HiltViewModel
class GitHubSignInViewModel @Inject constructor(
    private val deviceFlow: DeviceFlowApi,
    private val auth: GitHubAuthRepository,
    private val profile: GitHubProfileService,
    private val scopePrefs: GitHubScopePreferences,
) : ViewModel() {

    private val _state = MutableStateFlow<SignInState>(SignInState.Idle)
    val state: StateFlow<SignInState> = _state.asStateFlow()

    private var pollJob: Job? = null

    /** Test-only seam — production path uses the constant. */
    internal var clientIdOverride: String? = null
    private val clientId: String
        get() = clientIdOverride ?: GitHubAuthConfig.DEFAULT_CLIENT_ID

    fun start() {
        if (_state.value !is SignInState.Idle && _state.value !is SignInState.Denied
            && _state.value !is SignInState.Expired && _state.value !is SignInState.Failure
        ) {
            return
        }
        cancelPolling()
        _state.value = SignInState.RequestingCode
        viewModelScope.launch {
            // #203 — pick the scope set off the user's persisted "Enable
            // private repos" toggle. ON re-runs Device Flow with the
            // `repo` scope (full repo, includes private); OFF stays on
            // `public_repo`. Read at sign-in time so a freshly-flipped
            // toggle takes effect on the next attempt.
            val scopes = GitHubAuthConfig.scopesFor(scopePrefs.privateReposEnabled())
            when (val result = deviceFlow.requestDeviceCode(
                clientId = clientId,
                scopes = scopes,
            )) {
                is DeviceCodeResult.Success -> {
                    _state.value = SignInState.AwaitingUser(
                        userCode = result.userCode,
                        verificationUri = result.verificationUri,
                        verificationUriComplete = result.verificationUriComplete,
                        expiresInSeconds = result.expiresInSeconds,
                        intervalSeconds = result.intervalSeconds,
                    )
                    startPolling(
                        deviceCode = result.deviceCode,
                        intervalSeconds = result.intervalSeconds,
                        expiresInSeconds = result.expiresInSeconds,
                    )
                }
                is DeviceCodeResult.GitHubError -> {
                    _state.value = SignInState.Failure(
                        message = describe(result),
                        retryable = result.code != "device_flow_disabled",
                    )
                }
                is DeviceCodeResult.NetworkError -> {
                    _state.value = SignInState.Failure(
                        message = friendlyNetworkError(result.cause),
                        retryable = true,
                    )
                }
                is DeviceCodeResult.HttpError -> {
                    _state.value = SignInState.Failure(
                        message = "GitHub returned HTTP ${result.code} — ${result.message}",
                        retryable = true,
                    )
                }
                is DeviceCodeResult.MalformedResponse -> {
                    _state.value = SignInState.Failure(
                        message = "Couldn't read GitHub's response — ${result.message}",
                        retryable = true,
                    )
                }
            }
        }
    }

    /** User pressed back / dismissed the modal. Cancel everything in flight. */
    fun cancel() {
        cancelPolling()
        _state.value = SignInState.Idle
    }

    private fun cancelPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private fun startPolling(
        deviceCode: String,
        intervalSeconds: Int,
        expiresInSeconds: Int,
    ) {
        cancelPolling()
        val deadline = System.currentTimeMillis() + expiresInSeconds * 1000L
        var currentInterval = intervalSeconds
        var consecutiveNetworkErrors = 0
        pollJob = viewModelScope.launch {
            // Wait the initial interval before the first poll — GitHub
            // documents this; sending the first POST immediately gets us
            // a `slow_down` on the second request.
            delay(currentInterval * 1000L)
            while (true) {
                if (System.currentTimeMillis() >= deadline) {
                    _state.value = SignInState.Expired
                    return@launch
                }
                when (val r = deviceFlow.pollAccessToken(clientId, deviceCode)) {
                    is TokenPollResult.Success -> {
                        consecutiveNetworkErrors = 0
                        capture(token = r.token, scopes = r.scopes)
                        return@launch
                    }
                    TokenPollResult.Pending -> {
                        consecutiveNetworkErrors = 0
                        // continue at currentInterval
                    }
                    TokenPollResult.SlowDown -> {
                        consecutiveNetworkErrors = 0
                        // Per RFC 8628 §3.5, bump interval by 5s on slow_down.
                        currentInterval += 5
                    }
                    TokenPollResult.Expired -> {
                        _state.value = SignInState.Expired
                        return@launch
                    }
                    TokenPollResult.Denied -> {
                        _state.value = SignInState.Denied
                        return@launch
                    }
                    is TokenPollResult.GitHubError -> {
                        _state.value = SignInState.Failure(
                            message = "GitHub error: ${r.code}${r.message?.let { " — $it" } ?: ""}",
                            retryable = false,
                        )
                        return@launch
                    }
                    is TokenPollResult.NetworkError -> {
                        // Silent retry once per CLAUDE.md error-recovery rule.
                        consecutiveNetworkErrors++
                        if (consecutiveNetworkErrors >= 2) {
                            _state.value = SignInState.Failure(
                                message = friendlyNetworkError(r.cause),
                                retryable = true,
                            )
                            return@launch
                        }
                    }
                    is TokenPollResult.HttpError -> {
                        _state.value = SignInState.Failure(
                            message = "GitHub returned HTTP ${r.code} — ${r.message}",
                            retryable = true,
                        )
                        return@launch
                    }
                    is TokenPollResult.MalformedResponse -> {
                        _state.value = SignInState.Failure(
                            message = "Couldn't read GitHub's response — ${r.message}",
                            retryable = true,
                        )
                        return@launch
                    }
                }
                delay(currentInterval * 1000L)
            }
        }
    }

    private suspend fun capture(token: String, scopes: String) {
        _state.value = SignInState.Capturing
        // Persist the token first so the auth interceptor will attach the
        // Bearer header on the upcoming /user lookup.
        auth.captureSession(token = token, login = null, scopes = scopes)
        // Then resolve the @username. Failure here is non-fatal — we have
        // a valid token and a working session, just no display login. The
        // Settings UI tolerates a null login by falling back to "Signed in".
        when (val p = profile.getCurrentUser()) {
            is ProfileResult.Success -> {
                auth.captureSession(token = token, login = p.login, scopes = scopes)
                _state.value = SignInState.Captured(login = p.login)
            }
            is ProfileResult.NetworkError,
            is ProfileResult.HttpError,
            is ProfileResult.MalformedResponse -> {
                _state.value = SignInState.Captured(login = null)
            }
        }
    }

    private fun describe(error: DeviceCodeResult.GitHubError): String = when (error.code) {
        "device_flow_disabled" ->
            "GitHub says Device Flow is disabled for this OAuth app. Report to JP — the app needs " +
                "'Enable Device Flow' checked at github.com/settings/applications."
        "incorrect_client_credentials" ->
            "Sign-in failed: the OAuth app's client_id is wrong. " +
                "(Has JP wired the real client_id yet? See GitHubAuthConfig.)"
        else -> "Sign-in failed: ${error.code}${error.description?.let { " — $it" } ?: ""}"
    }

    /**
     * Map raw OkHttp network exceptions to a friendlier user-facing message.
     * The default OkHttp message ("Unable to resolve host 'github.com': No
     * address associated with hostname") is technically accurate but reads
     * as jargon — it doesn't tell the user what to actually try. See #341.
     */
    private fun friendlyNetworkError(cause: Throwable): String {
        val raw = cause.message.orEmpty()
        return when {
            // DNS failure — most often airplane mode, captive-portal WiFi
            // before the user signed into it, or a DNS provider being down.
            cause is java.net.UnknownHostException ||
                raw.contains("Unable to resolve host", ignoreCase = true) ->
                "Couldn't reach github.com — check your network connection " +
                    "(Wi-Fi signed in? VPN? airplane mode?) and try again."
            // TLS handshake / certificate failures — usually MITM proxy or
            // a corporate captive portal injecting itself into the TLS path.
            cause is javax.net.ssl.SSLException ||
                raw.contains("SSL", ignoreCase = true) ||
                raw.contains("trust anchor", ignoreCase = true) ->
                "TLS handshake failed — the network may be intercepting " +
                    "HTTPS traffic (captive portal, corporate proxy). Try a " +
                    "different network."
            cause is java.net.SocketTimeoutException ||
                raw.contains("timeout", ignoreCase = true) ->
                "Network timed out reaching github.com. Try again, or switch networks."
            else -> "Network error — ${raw.ifBlank { "request failed" }}"
        }
    }

    override fun onCleared() {
        cancelPolling()
        super.onCleared()
    }
}

sealed class SignInState {
    object Idle : SignInState()
    object RequestingCode : SignInState()
    data class AwaitingUser(
        val userCode: String,
        /** Plain `https://github.com/login/device` (no code prefilled). */
        val verificationUri: String,
        /** Pre-filled URL `https://github.com/login/device?user_code=ABCD-1234`. */
        val verificationUriComplete: String?,
        val expiresInSeconds: Int,
        val intervalSeconds: Int,
    ) : SignInState()
    object Capturing : SignInState()
    data class Captured(val login: String?) : SignInState()
    object Denied : SignInState()
    object Expired : SignInState()
    data class Failure(val message: String, val retryable: Boolean) : SignInState()
}
