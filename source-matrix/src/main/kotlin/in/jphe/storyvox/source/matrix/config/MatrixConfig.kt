package `in`.jphe.storyvox.source.matrix.config

import kotlinx.coroutines.flow.Flow

/**
 * Issue #457 — abstraction over the Matrix source's persistent
 * config. Same leaf-source pattern as [`in`.jphe.storyvox.source.discord.config.DiscordConfig]
 * and [`in`.jphe.storyvox.source.telegram.config.TelegramConfig]:
 * the source module declares the interface + state shape; the host
 * app supplies the DataStore + EncryptedSharedPreferences-backed
 * implementation.
 *
 * Auth model: **access token + homeserver URL**. The user creates
 * an access token via their homeserver's Element / web client
 * (Settings → Help & About → Advanced → "Access Token"), pastes
 * both the homeserver URL (e.g. `https://matrix.org`) and the
 * token (`syt_…` / `mat_…`) into Settings → Library & Sync →
 * Matrix. No password-login flow (worse security posture for
 * storyvox to hold passwords), no SSO/OIDC (deferred to v2).
 *
 * The token is stored in `storyvox.secrets` (Tink-backed
 * EncryptedSharedPreferences) under `pref_source_matrix_token`.
 * Plaintext DataStore holds the homeserver URL + coalesce window.
 *
 * Federation note: a Matrix user's profile lookups
 * (`/profile/{userId}/displayname`) target the user's *home*
 * homeserver, which may differ from the configured homeserver
 * (a room on `matrix.org` can contain members from `kde.org`).
 * v1 simplifies by routing every API call through the configured
 * homeserver — most homeservers maintain a federated cache of
 * profile data, so this works in practice; cross-server profile
 * lookups are a v2 enhancement that requires multi-host
 * dispatch in [`in`.jphe.storyvox.source.matrix.net.MatrixApi].
 */
interface MatrixConfig {
    /** Hot stream of the current config state. */
    val state: Flow<MatrixConfigState>

    /** Synchronous snapshot for code paths that can't suspend. */
    suspend fun current(): MatrixConfigState
}

/**
 * One Matrix config state.
 *
 * All three user-controlled fields are empty by default; the
 * source returns
 * [`in`.jphe.storyvox.data.source.model.FictionResult.AuthRequired]
 * on every call until both [homeserverUrl] and [accessToken] are
 * present.
 */
data class MatrixConfigState(
    /** Access token from the user's homeserver. Empty means the
     *  source returns AuthRequired on every call. Stored encrypted;
     *  never exposed to the UI as a readable string (the UI surface
     *  carries only a `matrixTokenConfigured: Boolean` projection). */
    val accessToken: String = "",

    /** Homeserver URL the user pasted, normalised to drop a trailing
     *  slash (so concatenation with `/_matrix/client/v3/...` always
     *  yields a clean URL). Empty means no homeserver picked yet;
     *  the source returns AuthRequired in that case. Common values:
     *  `https://matrix.org`, `https://matrix.kde.org`,
     *  `https://matrix.fosdem.org`, or a self-hosted Synapse /
     *  Dendrite / Conduit instance. */
    val homeserverUrl: String = "",

    /** Resolved `@user:homeserver` Matrix id captured at the moment
     *  the user's access token validated via `whoami`. Used by the
     *  Settings UI to confirm the token is live ("Signed in as
     *  @alice:matrix.org"). Empty when the user hasn't validated
     *  yet; the source still works on a non-empty token, this is
     *  just a UX nicety. */
    val resolvedUserId: String = "",

    /** Coalesce window for same-sender messages → one chapter
     *  (minutes). Mirrors Discord's coalesce-minutes shape. Defaults
     *  to [MatrixDefaults.DEFAULT_COALESCE_MINUTES]; slider range in
     *  Settings is 1-30. */
    val coalesceMinutes: Int = MatrixDefaults.DEFAULT_COALESCE_MINUTES,
) {
    /** True when the source can make API calls. Requires both a
     *  configured access token AND a homeserver URL — without
     *  either, the request would have nowhere to dispatch to. */
    val isConfigured: Boolean
        get() = accessToken.isNotBlank() && homeserverUrl.isNotBlank()

    /** The configured homeserver URL with any trailing slash
     *  stripped, so callers can append `/_matrix/client/v3/...`
     *  without producing a double slash. Empty when
     *  [homeserverUrl] is itself empty. */
    val baseUrl: String
        get() = homeserverUrl.trimEnd('/')
}
