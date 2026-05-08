package `in`.jphe.storyvox.source.github.auth

/**
 * Static OAuth-app config for storyvox's GitHub Device Flow integration.
 *
 * Issue #91. Spec: docs/superpowers/specs/2026-05-08-github-oauth-design.md
 *
 * ## TODO(client_id) — JP must register the OAuth app
 *
 * The Device Flow is a *public-client* flow: only `client_id` is sent to
 * GitHub, never a `client_secret`. The `client_id` ships in every APK by
 * design (no secrecy), so a hardcoded constant here is the right shape —
 * this is a **deliberate exception** to CLAUDE.md's Vaultwarden rule, which
 * applies to actual secrets.
 *
 * Steps for JP after this PR merges (per Ember's spec § Open Question #1):
 *
 * 1. Visit https://github.com/settings/applications/new
 * 2. Name: `storyvox`
 * 3. Homepage: `https://github.com/jphein/storyvox`
 * 4. Authorization callback URL: `https://github.com/jphein/storyvox`
 *    (unused for Device Flow but the form requires *something*)
 * 5. **Check "Enable Device Flow"** — without this the `/login/device/code`
 *    endpoint returns `device_flow_disabled`.
 * 6. Save → copy the resulting 20-character `client_id`.
 * 7. Replace [DEFAULT_CLIENT_ID] below with the real value, OR wire it via
 *    `local.properties` → `BuildConfig.GITHUB_CLIENT_ID` (cleaner; matches
 *    realm-sigil's Gradle pattern).
 *
 * Until then, sign-in attempts will hit a hardcoded placeholder and fail
 * with `incorrect_client_credentials`. The auth substrate (token storage,
 * interceptor, polling, UI) is fully wired and tested — only the live
 * sign-in path needs the real id.
 */
object GitHubAuthConfig {

    /**
     * Placeholder. **Not a real GitHub OAuth app id.** See class kdoc for
     * how JP wires the real value.
     *
     * Format check: real GitHub `client_id`s are 20 lowercase hex chars
     * (e.g. `Iv1.b507a08c87ecfe98` for GitHub Apps; OAuth-App ids are
     * shorter). Anything other than the placeholder will be tried verbatim
     * against `https://github.com/login/device/code`.
     */
    const val DEFAULT_CLIENT_ID: String = "TODO_JP_REGISTER_OAUTH_APP"

    /**
     * Default scopes requested at sign-in. Spec § Scope minimization:
     * least-privilege default. `repo` (private) and `gist` are deferred
     * to opt-in toggles in future PRs.
     */
    const val DEFAULT_SCOPES: String = "read:user public_repo"

    /** Device Flow endpoints live on the *website* domain, not api.github.com. */
    const val DEVICE_CODE_URL: String = "https://github.com/login/device/code"
    const val ACCESS_TOKEN_URL: String = "https://github.com/login/oauth/access_token"

    /**
     * Browser-friendly verification URL. The device-code response also
     * carries a `verification_uri_complete` that pre-fills the user's
     * code via query param; the modal prefers that when present and falls
     * back to this constant.
     */
    const val VERIFICATION_URL: String = "https://github.com/login/device"

    /**
     * Where to send users to revoke storyvox's token on github.com.
     * Local sign-out always succeeds; remote revoke requires the
     * client_secret we don't have, so we deep-link instead.
     */
    const val SETTINGS_APPLICATIONS_URL: String = "https://github.com/settings/applications"
}
