package `in`.jphe.storyvox.source.github.auth

/**
 * Static OAuth-app config for storyvox's GitHub Device Flow integration.
 *
 * Issue #91. Spec: docs/superpowers/specs/2026-05-08-github-oauth-design.md
 *
 * The Device Flow is a *public-client* flow: only `client_id` is sent to
 * GitHub, never a `client_secret`. The `client_id` ships in every APK by
 * design (no secrecy), so a hardcoded constant here is the right shape —
 * this is a **deliberate exception** to CLAUDE.md's Vaultwarden rule, which
 * applies to actual secrets.
 *
 * The OAuth app is registered to JP's account; if it ever needs to rotate
 * (e.g. compromised, or the public-client posture changes), bump the
 * constant and ship a new build.
 */
object GitHubAuthConfig {

    /**
     * Live storyvox GitHub OAuth-app client id. Registered 2026-05-09 with
     * Device Flow enabled; see class kdoc for the (no-)secret posture.
     */
    const val DEFAULT_CLIENT_ID: String = "Ov23liJEGbBN5ohx95XM"

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
