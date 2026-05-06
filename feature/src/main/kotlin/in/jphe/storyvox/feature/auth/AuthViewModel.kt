package `in`.jphe.storyvox.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import `in`.jphe.storyvox.data.auth.SessionHydrator
import `in`.jphe.storyvox.data.repository.AuthRepository
import `in`.jphe.storyvox.data.repository.FictionRepository
import `in`.jphe.storyvox.feature.api.SettingsRepositoryUi
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Sits between the WebView capture path and the persisted session state.
 *
 * The WebView (in :source-royalroad) hands us the captured cookie map. We:
 * 1. Build the canonical `Cookie:` header string and stash it in
 *    EncryptedSharedPreferences via [AuthRepository.captureSession].
 * 2. Push the same cookies into the live OkHttp jar via [SessionHydrator] so
 *    the next browse / chapter fetch is authed without restarting the app.
 * 3. Flip the UI sign-in flag in DataStore via [SettingsRepositoryUi] so the
 *    Settings screen rerenders the "Sign out" button immediately.
 *
 * [captureState] flips to [CaptureState.Captured] once both stores are
 * written; the screen observes it to know when to pop the back stack.
 */
@HiltViewModel
class AuthViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val hydrator: SessionHydrator,
    private val settings: SettingsRepositoryUi,
    private val fictionRepo: FictionRepository,
) : ViewModel() {

    private val _captureState = MutableStateFlow<CaptureState>(CaptureState.Idle)
    val captureState: StateFlow<CaptureState> = _captureState.asStateFlow()

    fun captureCookies(cookies: Map<String, String>) {
        if (cookies.isEmpty()) return
        if (_captureState.value is CaptureState.Captured) return
        _captureState.value = CaptureState.Capturing
        viewModelScope.launch {
            val cookieHeader = cookies.entries.joinToString("; ") { (k, v) -> "$k=$v" }
            auth.captureSession(
                cookieHeader = cookieHeader,
                userDisplayName = null,
                userId = null,
                expiresAt = null,
            )
            hydrator.hydrate(cookies)
            settings.signIn()
            _captureState.value = CaptureState.Captured
            // Fire-and-forget: pull the user's RR follows into the local DB
            // so the Follows tab populates without an extra user action.
            // Failures are silent — the Follows tab will retry on next visit.
            runCatching { fictionRepo.refreshRemoteFollows() }
        }
    }
}

sealed interface CaptureState {
    data object Idle : CaptureState
    data object Capturing : CaptureState
    data object Captured : CaptureState
}
