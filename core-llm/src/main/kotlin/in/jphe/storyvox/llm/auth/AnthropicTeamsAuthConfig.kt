package `in`.jphe.storyvox.llm.auth

/**
 * Static OAuth-app config for the Anthropic Teams (OAuth) provider (#181).
 *
 * Mirrors `:source-github`'s `GitHubAuthConfig` shape — public-client PKCE
 * flow, the client_id ships in every APK by design, the secret-less posture
 * is documented in the kdoc.
 *
 * Storyvox uses Claude Code's published public OAuth client to talk to
 * `claude.ai/oauth/authorize` (Authorization Code + PKCE / S256). The
 * browser handshake is identical to what Claude Code uses on the desktop;
 * the `console.anthropic.com/oauth/code/callback` redirect URI is what
 * Anthropic accepts for that client.
 *
 * If the public client_id ever rotates, bump the constant and ship a new
 * build — same posture as `GitHubAuthConfig.DEFAULT_CLIENT_ID`.
 */
object AnthropicTeamsAuthConfig {

    /**
     * Public Claude Code OAuth client id. Same one used by the Claude
     * Code CLI; visible in the binary, not a secret.
     */
    const val DEFAULT_CLIENT_ID: String = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"

    /**
     * Scopes requested at sign-in.
     *
     * - `org:create_api_key` — required for full Messages API access.
     * - `user:profile` — surfaces account info on the "Signed in" row.
     * - `user:inference` — required to call the Messages API on behalf
     *   of the signed-in workspace.
     */
    const val DEFAULT_SCOPES: String = "org:create_api_key user:profile user:inference"

    /**
     * Browser-facing authorize endpoint. PKCE-S256 code-flow; the device
     * picks up the redirect via the `code` query param on the callback URL
     * and exchanges it for a bearer token at [TOKEN_URL].
     */
    const val AUTHORIZE_URL: String = "https://claude.ai/oauth/authorize"

    /** Token endpoint — exchanges authorization code or refresh token for a bearer. */
    const val TOKEN_URL: String = "https://console.anthropic.com/v1/oauth/token"

    /**
     * The redirect URI that this OAuth client accepts. Anthropic shows
     * the resulting code on the page after the user clicks "Authorize"
     * — the user copy-pastes the code into storyvox; we don't claim a
     * `storyvox://` scheme intent-filter here because the public OAuth
     * client is registered with the console.anthropic.com URL only.
     *
     * The page after authorization renders the code in a copy-able block
     * (Anthropic's standard out-of-band flow); the modal in `:app` shows
     * a "Paste authorization code" field after the browser handoff.
     */
    const val REDIRECT_URI: String = "https://console.anthropic.com/oauth/code/callback"

    /**
     * `anthropic-beta` header value required when calling Messages API
     * with an OAuth bearer token. The `2025-04-20` value is the OAuth
     * beta gate; without this header the bearer is rejected as "use an
     * API key instead." See `cc-usage` for the same usage at
     * `~/Projects/claude-code-switcher/cc-usage`.
     */
    const val OAUTH_BETA_HEADER: String = "oauth-2025-04-20"

    /** Anthropic's account/profile endpoint — used to surface the user
     *  identity after sign-in. */
    const val PROFILE_URL: String = "https://api.anthropic.com/api/oauth/profile"
}
