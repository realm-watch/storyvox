package `in`.jphe.storyvox.feature.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import `in`.jphe.storyvox.sync.client.InstantClient
import `in`.jphe.storyvox.sync.client.InstantSession
import `in`.jphe.storyvox.sync.client.SignedInUser
import `in`.jphe.storyvox.sync.client.SyncAuthResult
import `in`.jphe.storyvox.sync.coordinator.SyncCoordinator
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * UI state machine for the sync sign-in screen.
 *
 * State graph:
 *   SignedOut (idle, email entry)
 *     → SendingCode
 *       → CodePrompt (code entry)
 *         → Verifying
 *           → SignedIn (success — calls coordinator to push existing local state)
 *         → CodePrompt (with [SignInState.CodePrompt.error] for a bad code)
 *       → SignedOut (with error)
 *
 * The flow is one screen — fields appear progressively as state
 * advances. The coordinator's `requestPushAll()` is fired on
 * successful sign-in to upload existing on-device state (the
 * migration story).
 */
@HiltViewModel
class SyncAuthViewModel @Inject constructor(
    private val client: InstantClient,
    private val session: InstantSession,
    private val coordinator: SyncCoordinator,
) : ViewModel() {

    private val _state = MutableStateFlow<SignInState>(initialState())
    val state: StateFlow<SignInState> = _state.asStateFlow()

    private fun initialState(): SignInState =
        session.current()?.let { SignInState.SignedIn(it) } ?: SignInState.SignedOut(email = "")

    fun updateEmail(email: String) {
        val current = _state.value as? SignInState.SignedOut ?: return
        _state.value = current.copy(email = email, error = null)
    }

    fun updateCode(code: String) {
        val current = _state.value as? SignInState.CodePrompt ?: return
        _state.value = current.copy(code = code, error = null)
    }

    /** Email → "send the code." Triggers a transition to [SignInState.SendingCode]
     *  while the network call is in flight. */
    fun sendCode() {
        val current = _state.value as? SignInState.SignedOut ?: return
        val email = current.email.trim()
        if (!email.contains('@')) {
            _state.value = current.copy(error = "Enter an email address")
            return
        }
        _state.value = SignInState.SendingCode(email)
        viewModelScope.launch {
            when (val result = client.sendMagicCode(email)) {
                is SyncAuthResult.Ok -> {
                    _state.value = SignInState.CodePrompt(email = email, code = "", error = null)
                }
                is SyncAuthResult.Err -> {
                    _state.value = SignInState.SignedOut(email = email, error = result.message)
                }
            }
        }
    }

    /** Code → "verify it." On success, persists the refresh token and
     *  kicks off the post-sign-in push migration. */
    fun verifyCode() {
        val current = _state.value as? SignInState.CodePrompt ?: return
        val code = current.code.trim()
        if (code.length < 4) {
            _state.value = current.copy(error = "Enter the 6-digit code")
            return
        }
        _state.value = SignInState.Verifying(current.email, code)
        viewModelScope.launch {
            when (val result = client.verifyMagicCode(current.email, code)) {
                is SyncAuthResult.Ok -> {
                    session.store(result.value)
                    val signedIn = SignedInUser(
                        userId = result.value.id,
                        email = result.value.email,
                        refreshToken = result.value.refreshToken,
                    )
                    _state.value = SignInState.SignedIn(signedIn)
                    // Migration: push existing local state up so a new
                    // device pulls it back. Fire-and-forget on the
                    // coordinator's own scope — we don't block the UI.
                    coordinator.requestPushAll()
                }
                is SyncAuthResult.Err -> {
                    _state.value = SignInState.CodePrompt(
                        email = current.email,
                        code = current.code,
                        error = result.message,
                    )
                }
            }
        }
    }

    /** Local sign-out + best-effort server sign-out. */
    fun signOut() {
        val current = session.current() ?: run {
            _state.value = SignInState.SignedOut(email = "")
            return
        }
        viewModelScope.launch {
            client.signOut(current.refreshToken) // best-effort; ignored on failure
            session.clear()
            _state.value = SignInState.SignedOut(email = "")
        }
    }

    /** Reset to email-entry from any state (e.g. user wants a fresh code). */
    fun reset() {
        _state.value = SignInState.SignedOut(email = "")
    }
}

sealed interface SignInState {
    data class SignedOut(val email: String, val error: String? = null) : SignInState
    data class SendingCode(val email: String) : SignInState
    data class CodePrompt(val email: String, val code: String, val error: String?) : SignInState
    data class Verifying(val email: String, val code: String) : SignInState
    data class SignedIn(val user: SignedInUser) : SignInState
}
