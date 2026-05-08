# GitHub OAuth (Device Flow) — Design Spec

**Author:** Ember (identity + secrets lane)
**Date:** 2026-05-08
**Status:** Draft, awaiting JP review (no code until approved)
**Branch:** `dream/ember/github-oauth-spec`
**Issue:** [#91](https://github.com/jphein/storyvox/issues/91) — "add optional GitHub login to read your repository readmes save other sweet integrations"
**Layers on:** [`2026-05-06-github-source-design.md`](2026-05-06-github-source-design.md)

## Problem

The `:source-github` plugin shipped unauthenticated (per the parent spec's
v1 decision). That gets us 60 GitHub REST requests per IP per hour and
read access to every public repo on the platform. It does **not** get us:

- The user's own private repos (their personal mdbook of WIP fictions).
- The user's starred repos (a high-quality "what should I listen to next?"
  signal that exists for free in every GitHub account).
- The user's gists (perfect for short fictions and drafts the user wants
  to listen back to but not publish on Royal Road).
- A 5,000 req/hr rate-limit ceiling for power-user browse sessions.
- The ability to add `topic:fiction` repos that authors keep private
  during writing and unlist on launch.

The parent spec defers all of this to "when a clean GitHub OAuth path
exists." This spec is that path.

JP's framing on issue #91 is precise: **"add optional GitHub login to
read your repository readmes save other sweet integrations."** Read the
user's own readmes is the entry-point feature; "sweet integrations" is
the menu of follow-ups the auth unlocks.

## Goals

1. Optional sign-in. Unauthenticated browse of public GitHub fiction
   keeps working exactly as it does today — the auth path is purely
   additive.
2. Smallest possible default scope (`read:user public_repo`). Private-repo
   access is a deliberate second step, gated by a separate Settings
   toggle, so the common case is a least-privilege token.
3. Token at rest is encrypted with the same `EncryptedSharedPreferences`
   instance the Royal Road cookie already lives in. Single token per
   app instance — no multi-account in v1.
4. Login UX works on a sideload Android with no app domain, no callback
   URL, and no WebView gymnastics. The user presses "Sign in," reads an
   8-character code on-screen, taps a button to open `github.com/login/device`,
   pastes the code in the browser, and storyvox's polling closes the loop.
5. Existing `GitHubApi` calls work in both modes: an interceptor
   attaches `Authorization: Bearer <token>` when a token exists and falls
   through unauthenticated when it doesn't. No call site changes.

## Non-goals (this spec)

- **Multi-account.** v1 stores one token. A future "switch account"
  feature can re-key the prefs map; not designing it now.
- **Server-side OAuth broker.** storyvox has no backend. The client is
  public; client_secret is not bundled. (See Open Question #1.)
- **Write access.** No commit/PR/issue scopes. Read-only forever (modulo
  whatever a future "write a chapter back to a gist" feature might add,
  but that's its own design).
- **Refresh-token plumbing.** Classic OAuth Apps issue non-expiring
  tokens; we don't need a refresh dance. (See Open Question #3.)
- **GitHub Apps (vs OAuth Apps).** GitHub Apps issue 8-hour user-to-server
  tokens with refresh — wrong shape for a sideload reader. We use a
  classic OAuth App.
- **The "sweet integrations" themselves.** This spec enumerates them as
  future PRs but doesn't design them. The point is that auth unblocks
  them all.

## Why Device Flow over Web Flow

GitHub supports two interactive OAuth flows for desktop/mobile clients:

| Flow | URL shape | Fits storyvox? |
|---|---|---|
| **Web flow** (`/login/oauth/authorize` → callback) | requires a registered redirect URL (HTTPS, custom-scheme, or `localhost:port`) | No |
| **Device Flow** (RFC 8628; `/login/device/code` → poll `/oauth/access_token`) | no redirect URL; user pastes a code in their browser | Yes |

Web flow rejected because:

- **No redirect URL.** storyvox is sideload-only — there's no `https://app.storyvox.in`
  to register. Custom-scheme redirects (`storyvox://oauth-callback`) work
  on Android via `intent-filter`, but Custom Tabs + intent dispatch is
  fragile (the user can opt into a non-Chrome browser that doesn't
  honor the intent filter and the redirect ends in a 404 page they
  can't recover from). `localhost:port` requires running an HTTP server
  inside the app process, which is overkill for one auth call and adds
  a permission ask.
- **WebView capture.** We considered a WebView (the same shape as Royal
  Road's existing sign-in path). Rejected: GitHub explicitly disallows
  embedded WebViews for OAuth as of 2021 — Google's "no embedded
  webviews" policy plus GitHub's enforcement means a WebView attempt
  hits a "browser not supported" interstitial before the user can sign
  in. RR survives because Cloudflare-driven WebView capture is the only
  path; GitHub actively forbids it.

Device flow rejected nothing of value:

- The user briefly leaves the app to a real Chrome tab. That's a UX cost,
  but a small one — the on-screen code is short, and "tap → paste → confirm"
  is a familiar pattern (Apple TV, Roku, GitHub CLI, `gh auth login`,
  Stripe CLI all use it).
- No callback to wire up. No app-domain registration. No browser-redirect
  edge cases. Polling is dead-simple.

The **public client** (no `client_secret`) variant of Device Flow is what
GitHub's docs call it when "Enable Device Flow" is checked on the OAuth
app settings page. Storyvox uses the public-client variant: only `client_id`
is sent, never a secret.

## Scope minimization

GitHub scopes requested by storyvox, in order of intrusiveness:

| Scope | Granted | Unlocks |
|---|---|---|
| `read:user` | always at sign-in | `/user` for `login`, `name`, `avatar_url` (display) |
| `public_repo` | always at sign-in | rate-limit ceiling lift to 5,000 req/hr; metadata for repos user owns/contributes to |
| `repo` | **opt-in via separate toggle** | private repo contents, private gists |
| `gist` | only if user enables gist features | reading the user's own gists as fiction sources |

The default sign-in flow asks for **`read:user public_repo`** — nothing
more. That's enough to:

- Show "Signed in as @octocat" in Settings.
- Lift the unauthenticated 60/hr cap to 5,000/hr (the single biggest
  practical win for power users).
- Discover and read public repos the user owns (the issue #91 entry-point
  feature: "read your repository readmes").

The `repo` scope is gated behind a second action: Settings → Sources →
GitHub → "Enable private repos" toggle. Flipping it on triggers a fresh
device-flow call with the broader scope (GitHub does **not** support
incremental authorization for OAuth Apps — re-auth is the canonical
path). The user sees a clear "we're asking for private-repo access now"
modal before the second device-code prompt.

`gist` is similarly gated, behind a "Enable gists as fiction sources"
toggle when/if that feature lands.

This three-tier model means the default user is on a least-privilege
token. Power users opt up. Audit-friendly.

## Token storage

The persisted token shape, mirroring `AuthRepository`'s cookie store:

```
EncryptedSharedPreferences instance: "storyvox.secrets" (existing)
Keys (all per-source, namespace `token:github`):
  token:github            → gho_* access token (string)
  token:github:login      → @username (string, used for UI display + future multi-account migration key)
  token:github:scopes     → space-separated scope list as granted (string)
  token:github:granted_at → epoch ms when token issued (long, stored as string)
```

These keys live alongside the existing `cookie:royalroad` key in the
same `EncryptedSharedPreferences` instance. **No new MasterKey, no new
prefs file** — the existing `provideEncryptedPrefs` in `:core-data`'s
`DataModule` already sets up `AES256_SIV` (key encryption) and
`AES256_GCM` (value encryption), Tink-backed. That covers the GitHub
token at rest with the same threat model as the RR cookie.

**Why not Room.** Same reason RR doesn't: SQLite is a plaintext file.
A token in SQLite means a stolen `/data/data/in.jphe.storyvox/databases/storyvox.db`
yields a working credential.

**Why not raw `SharedPreferences`.** Same reason RR doesn't: an unencrypted
prefs XML in `/data/data/in.jphe.storyvox/shared_prefs/` is the same
plaintext file with extra steps.

**Why not `DataStore`.** Could work — Jetpack DataStore + Tink encryption
exists. But `EncryptedSharedPreferences` is already wired and tested
in this app for credentials. Switching credential storage to a different
backend just for GitHub adds risk for no security gain. (DataStore is
the right answer for *new* preference surfaces — quotas, toggles, etc.
— and is used elsewhere; for credentials we follow the existing pattern.)

**In-memory copy.** `GitHubAuthRepository` (new) holds the token in a
`StateFlow<GitHubSession>` so the OkHttp interceptor doesn't hit the
encrypted store on every outbound request. Hydrate from disk in the
`init` block, exactly like `AuthRepositoryImpl` does for the RR cookie.

**Auth metadata in Room.** A parallel `GitHubAuth` row (in the existing
`auth_cookie` table or a new `github_auth` table — see Open Question #4)
stores the non-secret metadata: `login`, `scopes`, `grantedAt`, `lastVerifiedAt`.
This lets the Settings screen render "Signed in as @octocat" via a
Flow without touching the encrypted store on the main thread, mirroring
the RR `AuthCookie` row pattern.

## Multi-source `AuthRepository`

Today's `AuthRepositoryImpl` is hardcoded to Royal Road:

```kotlin
private val source: FictionSource = sources[SourceIds.ROYAL_ROAD]
    ?: error("AuthRepository: expected $sources to bind ${SourceIds.ROYAL_ROAD}; …")
```

…with a comment explicitly anticipating this refactor:

> When GitHub auth lands this becomes a per-call lookup — the cookie
> store is already keyed `cookie:$sourceId` so the data layer doesn't
> need migration.

The refactor is straightforward. Extract a per-source interface:

```kotlin
interface SourceAuth {
    val sessionState: StateFlow<SessionState>
    suspend fun clearSession()
    suspend fun verifyOrExpire(): SessionState
}

interface AuthRepository {
    fun forSource(sourceId: String): SourceAuth
    val anySignedIn: StateFlow<Boolean>  // OR over all sources, used by Settings
}
```

Two implementations:

- `RoyalRoadAuth` — wraps the existing cookie path. `captureSession`
  takes a cookie header, persists to `cookie:royalroad`.
- `GitHubAuth` (new) — wraps the device-flow path. `captureSession`
  takes a token + scopes + login, persists to `token:github*`.

The single-source-impl error in `AuthRepositoryImpl` is replaced by a
per-source resolution. Existing call sites that say `auth.cookieHeader()`
become `auth.forSource(SourceIds.ROYAL_ROAD).cookieHeader()` — a
mechanical change, not a behavioral one.

The encrypted prefs key namespace is already `<kind>:<sourceId>` so no
data migration needed.

## Auth header propagation (OkHttp interceptor)

Add a single interceptor in `:source-github`:

```kotlin
internal class GitHubAuthInterceptor @Inject constructor(
    private val auth: GitHubAuth,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        // Don't double-authenticate raw.githubusercontent.com or external
        // hosts that may show up in redirects; only attach to api.github.com.
        if (req.url.host != "api.github.com") {
            return chain.proceed(req)
        }
        val session = auth.sessionState.value
        val token = (session as? SessionState.Authenticated)?.token
        return if (token != null) {
            chain.proceed(
                req.newBuilder()
                    .header("Authorization", "Bearer $token")
                    .build(),
            )
        } else {
            chain.proceed(req)
        }
    }
}
```

Wired in `GitHubHttpModule.provideClient`:

```kotlin
@Provides
@Singleton
@GitHubHttp
fun provideClient(authInterceptor: GitHubAuthInterceptor): OkHttpClient =
    OkHttpClient.Builder()
        .addInterceptor(authInterceptor)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()
```

Properties of this design:

- **Zero call-site changes.** `GitHubApi.getRepo`, `getContent`,
  `searchRepositories`, etc. all keep their current signature. The
  interceptor is invisible to callers.
- **Anonymous fallback.** If the token is missing or expired, the request
  goes out unauthenticated and gets the 60/hr quota. Existing 403/429
  rate-limit handling in `GitHubApi.mapResponse` (returning
  `GitHubApiResult.RateLimited`) keeps working.
- **401 handling.** When the token is *invalid* (revoked at github.com
  or rotated by the user), GitHub returns `401 Unauthorized` with a
  `WWW-Authenticate: Bearer` header. The interceptor detects 401, calls
  `auth.markExpired()` (which transitions `SessionState → Expired`,
  clears the in-memory token, but does NOT delete from disk so the user
  sees "session expired, sign in again" rather than silent loss),
  and returns the 401 response so the caller sees the failure.
- **No host leak.** The host check (`req.url.host == "api.github.com"`)
  prevents the token leaking to `raw.githubusercontent.com` or any
  redirected host. (raw.githubusercontent.com handles auth via a separate
  `Authorization` header anyway, but keeping the check tight avoids
  surprises.)

## Login flow UX

A modal screen (`GitHubSignInScreen`) replaces the RR-style WebView path
for the GitHub source. Visual reference: brass-themed, single column,
fits Galaxy Tab A7 Lite. State machine:

| State | What's on screen | Transitions out |
|---|---|---|
| `Idle` | "[ Sign in to GitHub ]" button + explainer | tap → `RequestingCode` |
| `RequestingCode` | spinner, "Asking GitHub for a code…" | 200 → `AwaitingUser`; network err → `Idle` with error toast |
| `AwaitingUser` | `Code: ABCD-1234`, `[ Open github.com/login/device ]`, "expires in MM:SS", live status text | poll OK → `Capturing`; `access_denied` → `Denied`; `expired_token` → `Expired`; back → `Idle` |
| `Capturing` | spinner, "Fetching your profile…" | `GET /user` OK → `Captured`; failure → `Idle` with error |
| `Captured` | "Signed in as @octocat ✓" | auto-dismiss to Settings |
| `Denied` | "Sign-in cancelled" + retry button | retry → `RequestingCode` |
| `Expired` | "Code expired, get a new one" + retry button | retry → `RequestingCode` |

**Browser open:** `Intent.ACTION_VIEW` with `Uri.parse("https://github.com/login/device")`
plus `FLAG_ACTIVITY_NEW_TASK` so the system browser handles it. We
*could* prefill the code via the `?user_code=ABCD-1234` query param —
GitHub's verification page accepts it — but the user still has to
confirm, so the difference is "click confirm" vs "paste then click
confirm." Spec recommendation: prefill it (better UX), with a "Copy
code" button as fallback for users whose browser strips the query.

**Code copy:** clipboard fallback button. Some users have blocked
intent dispatch or want to paste manually.

**Polling:** the device-code response includes an `interval` (typically
5 seconds). Poll at exactly that cadence. On `slow_down` error, back
off by adding 5s to the interval, per RFC 8628 §3.5. Cap total polling
at the device-code's `expires_in` (typically 900 s / 15 min) — beyond
that, surface "code expired" and offer "Get new code."

**Cancellation:** user can back out of the modal. ViewModel cancels
the polling coroutine. Token is never written.

**Pause/resume:** if the user backgrounds storyvox while polling, we
keep polling (a short-lived foreground service is overkill; the modal
is brief). On modal recompose post-resume, status carries forward —
the `StateFlow` survives.

**Network failure during polling:** retry the same request silently
once (per CLAUDE.md error-recovery rule). On second failure, surface
"Network error — retry?" and pause polling until the user taps retry.

## Sign-out / revocation

Local sign-out (always works):

1. `GitHubAuth.clearSession()` removes the four `token:github*` keys
   from `EncryptedSharedPreferences`.
2. Clears the in-memory `StateFlow` to `SessionState.Anonymous`.
3. Deletes the `github_auth` Room row.
4. Cancels any in-flight `GitHubApi` calls that were authed (best-effort —
   the interceptor will reattach nothing, so a call that was already in
   flight just lands as if anonymous).

**Remote revocation** (`DELETE /applications/{client_id}/grant`) requires
HTTP Basic auth with `client_id:client_secret`. Storyvox has no
client_secret (public client). **Therefore: no remote revoke from the app.**

Documentation in the post-sign-out toast and the Settings page:

> Signed out locally. To revoke storyvox's access on GitHub's side,
> visit github.com/settings/applications.

This is honest about the boundary and matches what every other public-client
device-flow tool (gh CLI, GitHub Mobile, GitHub Desktop) tells users.
We can deep-link the user there with a button: `Intent.ACTION_VIEW`
to `https://github.com/settings/applications`.

If someday storyvox grows a backend (status.realm.watch is a vibes-only
heartbeat; nothing user-bound), the remote-revoke endpoint becomes
trivial to add. Out of scope here.

**Token rotation.** GitHub OAuth-App tokens don't expire by default.
If the user revokes at github.com, the next API call returns 401 and
the interceptor's 401 path transitions us to `Expired`. Settings shows
"Session expired — sign in again." The user re-runs device flow. Clean.

## Settings UI surface

Today's "Account" section becomes a "Sources" subsection with one row
per source — Royal Road keeps its current shape, GitHub gets a sibling
row. Each row shows: source name, signed-in chip (`Signed in as @octocat`)
or "Not signed in", a `[Sign in]` / `[Sign out]` brass button, and a
short explainer. The GitHub row additionally shows an "Enable private
repos" toggle when signed in, which (when flipped) surfaces a confirm
dialog and re-runs the device flow with `read:user public_repo repo`.

The GitHub row is shown unconditionally — even before any GitHub fiction
is in the user's library — because sign-in is *additive* (lifts the rate
limit for browse). No surprise rows appear/disappear based on library
state.

## Sequence — device-flow sign-in (happy path)

```
User      UI                 github.com/login        api.github.com
 │  Sign in │                       │                       │
 │─────────>│  POST /device/code    │                       │
 │          │──────────────────────>│                       │
 │          │  device_code, user_code=ABCD-1234,            │
 │          │  verification_uri, expires_in=900, interval=5 │
 │          │<──────────────────────│                       │
 │  shows code, taps "Open browser"                         │
 │<──── ACTION_VIEW ────┤                                   │
 │  → Chrome: github.com/login/device?user_code=ABCD-1234   │
 │  user authorizes in browser ────>│                       │
 │                                  │                       │
 │  every 5s: POST /oauth/access_token                      │
 │          │──────────────────────>│                       │
 │          │  {error: authorization_pending} (× N)          │
 │          │<──────────────────────│                       │
 │          │  POST /oauth/access_token (after user confirms)│
 │          │──────────────────────>│                       │
 │          │  {access_token: gho_xxx, scope: "read:user,public_repo"}
 │          │<──────────────────────│                       │
 │          │  GET /user (Authorization: Bearer)             │
 │          │─────────────────────────────────────────────> │
 │          │  {login: "octocat", id, name, avatar_url}      │
 │          │<───────────────────────────────────────────── │
 │  store token + login + scopes in encrypted prefs         │
 │  insert github_auth row, set SessionState.Authenticated  │
 │  navigate back to Settings, show "Signed in as @octocat" │
```

**Authed API call (post sign-in):** caller invokes `GitHubApi.getRepo`,
the `GitHubAuthInterceptor` checks `host == "api.github.com"` and
attaches `Authorization: Bearer <token>` if a token is present, the
request returns with a 5,000/hr rate-limit header. Caller sees normal
`GitHubApiResult.Success`.

**401 handling (token revoked at github.com):** the interceptor adds the
header as usual, but GitHub returns `401 Unauthorized`. The interceptor
detects the 401, calls `auth.markExpired()` (clears the in-memory token,
flips `SessionState → Expired`, leaves the disk copy so Settings shows
"Session expired" rather than silent loss), and returns the response so
the caller sees `GitHubApiResult.HttpError(401, …)`. The user re-runs
the device flow to recover.

## Error model (device flow)

GitHub's `/login/oauth/access_token` returns these error codes during
polling (RFC 8628 + GitHub-specific):

| Error | Meaning | UI behavior |
|---|---|---|
| `authorization_pending` | user hasn't confirmed yet | continue polling at `interval` |
| `slow_down` | we polled too fast | bump `interval` by 5s, continue |
| `expired_token` | the device code expired (>900s) | show "Code expired — get a new one" + retry button |
| `access_denied` | user clicked "Cancel" in browser | show "Sign-in cancelled" + retry button |
| `unsupported_grant_type` | bug — wrong grant_type sent | crash in dev, "Sign-in failed (bug)" in prod |
| `incorrect_client_credentials` | bug — wrong client_id | crash in dev, "Sign-in failed (bug)" in prod |
| `incorrect_device_code` | bug — wrong device_code | crash in dev, "Sign-in failed (bug)" in prod |
| `device_flow_disabled` | OAuth app not configured for device flow | "Sign-in unavailable, please report" |

Network errors (no HTTP response) → retry once silently, then surface
"Network error — retry?" with a manual retry button (per CLAUDE.md).

## Downstream features unlocked (PR sequence)

This spec lands the auth substrate. Each of these is a separate PR
**after** the auth PRs ship; they're listed here only so JP can see the
throughline and confirm the priority order.

| PR | What | Touches |
|---|---|---|
| **F1: My Repos** | "Read your repository readmes." Settings or Library entry: list repos owned by the signed-in user that have `book.toml`/`storyvox.json`/bare-repo structure. One-tap add to library. | `:source-github` `GitHubApi.listMyRepos`, new ViewModel, Library `+` flow gains a "From my GitHub" tab |
| **F2: Starred as Suggestions** | A row in Browse → GitHub: "From your stars." Filters the user's `/user/starred` for repos that look like fictions (have a manifest or topic match). Personalized version of the existing curated registry. | `:source-github` `GitHubApi.listStarred`, Browse screen row |
| **F3: Gists as Drafts** | Sign-in scope upgrade to `gist`. New "Add a gist" entry: paste a gist URL, treat it as a single-chapter fiction. Useful for quick drafts the user wants to listen back to. | New scope upgrade flow, `:source-github` `GitHubApi.getGist`, parser for gist JSON → chapter |
| **F4: Private Repos** | Sign-in scope upgrade to `repo`. The "Enable private repos" toggle in Settings. Subsequent `/repos/{owner}/{repo}` and `/contents` calls succeed for private repos. | Existing GitHub source code path; just needs the broader scope on the token |
| **F5: Auth-aware Browse** | Browse → GitHub gains "Public + private" toggle when private scope is granted; `/search/repositories?q=user:@me+topic:fiction+is:private` lights up. | Browse search composer |

**None of these are designed in this spec.** Each is a follow-up PR
once the auth substrate ships and lands.

## Build sequence (for this spec's PRs)

Suggested PR shape; the writing-plans pass will refine.

1. **PR Auth-A — `AuthRepository` multi-source refactor.** Extract
   `SourceAuth` interface from `AuthRepositoryImpl`. Implement
   `RoyalRoadAuth` with the existing cookie path. Update call sites
   (`AuthViewModel`, `:source-royalroad` cookie jar wiring). Behavioral
   no-op. Tests cover the routing + per-source clear/verify.
2. **PR Auth-B — `GitHubAuth` + storage layer.** New `GitHubAuth` impl
   binding into the `SourceAuth` map at key `SourceIds.GITHUB`. Adds
   the `github_auth` Room entity + DAO + migration. Adds the four
   `token:github*` keys to encrypted prefs. Internal-only — no UI yet.
   Tests cover persistence + StateFlow updates.
3. **PR Auth-C — Device-flow client (`DeviceFlowApi`).** Two endpoints
   (`/code`, `/access_token`) on `github.com` (NOT `api.github.com`,
   important detail — the device flow lives on the website's domain
   under `/login/device/code` and `/login/oauth/access_token`). OkHttp
   client without the auth interceptor (we can't bear-token the
   token-issuing endpoint). Polling loop with `interval`, `slow_down`,
   `expired_token` handling. Unit-tested with a mock server.
4. **PR Auth-D — Sign-in screen + ViewModel.** `GitHubSignInScreen`
   composable + `GitHubSignInViewModel`. State machine from this spec.
   Wired to `Intent.ACTION_VIEW` for browser open and clipboard copy.
   Tests cover the state transitions.
5. **PR Auth-E — Settings integration + auth interceptor.** Adds the
   "Sources" UI section. Adds `GitHubAuthInterceptor` to
   `GitHubHttpModule`. Tests verify the header attaches when a token
   is present and falls through when not. **This is the first PR with
   user-visible behavior change.**
6. **PR Auth-F — Scope upgrade flow (private repos).** "Enable private
   repos" toggle re-runs device flow with `repo` scope. Tests verify
   the scope persists in `token:github:scopes` and the toggle reflects
   actual granted scope (not requested).

After Auth-F lands, the F1-F5 feature PRs above can ship in any order;
they're independent of each other.

Total estimated implementation time across Auth-A through Auth-F:
~1.5 weeks of single-engineer time. Each PR is independently shippable
modulo the per-PR dependency chain (B depends on A, etc.).

## Security considerations

Token threat model — what we defend against, and what we don't.

| Vector | Defense |
|---|---|
| Stolen `/data/data/.../databases/storyvox.db` | Token isn't in SQLite. (RR cookie also isn't — same model.) |
| Stolen `/data/data/.../shared_prefs/storyvox.secrets.xml` | File is `EncryptedSharedPreferences` — `AES256_GCM` value encryption keyed by Android keystore master key. Attacker would also need the keystore key, which is hardware-backed on devices that support it (most modern Android, including Tab A7 Lite). |
| Token in process memory | Lives on `SessionState.Authenticated.token: String`. Not zeroized on logout. Acceptable: a process-memory-read attacker has full app access already; that's outside our threat model. |
| Token in logs | **Critical.** OkHttp default logging interceptor would print headers including `Authorization: Bearer gho_xxx`. We do not enable HTTP logging in release builds (already true today; verify in PR Auth-E). For debug builds, configure the logger to redact `Authorization` (`HttpLoggingInterceptor.redactHeader("Authorization")`). |
| Token exfil to wrong host | `GitHubAuthInterceptor` only attaches when `req.url.host == "api.github.com"`. Redirects to other hosts are followed by OkHttp without re-running the interceptor (the original request didn't bear the token). |
| Token in crash reports | storyvox doesn't ship a crash reporter today. If/when one lands, configure header redaction on the breadcrumb path. |
| Token leak via debug build → release migration | `EncryptedSharedPreferences` files don't move between builds. A debug-build token stays in the debug install's data dir. Reinstall over the top is what `clean install` (CLAUDE.md feedback `feedback_install_test_each_iteration`) handles. |
| Phishing via fake device-code page | The user opens a real `github.com/login/device` page in their real browser. Storyvox doesn't intermediate that page. The phishing vector reduces to "user trusts wrong site," which is GitHub's problem to solve, not ours. |

Tink-backed `EncryptedSharedPreferences` is a known-good primitive in
the Android security review canon. We're not inventing a crypto layer;
we're reusing the one that already protects the RR cookie.

## Dependencies

**Zero new dependencies.** All of the pieces this spec needs are already
in the project's classpath:

- `androidx.security.crypto:security-crypto-ktx` (already in `:core-data`
  for the RR cookie)
- `okhttp` (already in `:source-github`)
- `kotlinx.serialization` for the device-flow JSON responses (already used
  by `GitHubJson`)
- `androidx.room` for the auth metadata table (already used)
- Hilt multibindings (already used for `FictionSource`)

No new third-party crypto, no OAuth library (the device flow is a
4-line POST and a polling loop — overkill to pull in `appauth-android`
or similar for it).

## Storage policy

Token data is small (a few KB total, compared to RR cookies' few KB) and
lives forever (until sign-out or 401). No quota, no eviction.

If a future "switch account" feature lands, this spec recommends the key
namespace stays `token:github` (one slot) and re-keying becomes "clear
old, write new" rather than "store many." Multi-account is the wrong
shape for storyvox's "this device, this listener" mental model.

## Migration / rollout

- **Zero data migration.** No existing GitHub auth state. `auth_cookie`
  table is RR-only today; the new `github_auth` table is additive.
- **Feature flag.** The Settings → GitHub row is gated on a marker file
  (`github-auth-enabled` in `filesDir`) until v0.5.0, on by default
  after. Same pattern as the PCM cache spec proposes.
- **Rollback safety.** If the GitHub auth code is reverted, the encrypted
  prefs keys (`token:github*`) become orphaned reads — harmless. The
  `github_auth` Room row becomes orphaned data — harmless until a future
  schema migration drops it.

## Risks and open questions

| risk | mitigation |
|---|---|
| **Device flow polling drains battery on flaky networks.** | Polling stops on user-cancel, on success, on `expired_token` (15 min cap), and on `access_denied`. The window is bounded. |
| **GitHub temporarily disables Device Flow on the OAuth app.** | Surface `device_flow_disabled` error, link to support, log to status.realm.watch (when monitoring exists). Not blocking — affects new sign-ins only; existing tokens keep working. |
| **User signs in on multiple devices with the same OAuth app.** | GitHub allows this — each device gets its own token, all valid concurrently. No conflict. |
| **User has 2FA enabled on GitHub.** | Device flow handles 2FA on github.com's side, transparent to storyvox. The browser tab handles whatever auth GitHub asks for. |
| **Public client_id in the APK is "leaked."** | Public clients are designed to expose `client_id`. There's no `client_secret` to leak. An attacker who pulls the client_id from the APK can spin up their own polling loop, but they still need a real user to authorize at `github.com/login/device` with a real code — the only thing the client_id buys them is the ability to ask. Acceptable. |
| **Rate-limit exhaustion at 5,000/hr.** | Same caching strategy as the unauth path (parent spec). 5,000/hr is genuinely a lot — a power user browsing all day shouldn't hit it. |

## Open questions (need JP input)

### Open Question #1 — Whose `client_id`?

Device Flow is public-client (no `client_secret`), but somebody has to
register the OAuth app at `github.com/settings/developers`. Options:

- **A. JP-owned.** JP creates "storyvox" under `jphein`. `client_id` is
  baked into the APK. Users see "Authorize storyvox by jphein" on the
  github.com confirm page. Matches the `jphein/storyvox-registry`
  ownership pattern. Ownership transfer is awkward if storyvox ever
  grows past JP, but that's a future-JP problem.
- **B. Bring-your-own.** User registers their own OAuth app and pastes
  the `client_id` into Settings. Maximally private; terrible UX. Strands
  the median user.
- **C. Hybrid.** Bundle JP's `client_id` as default; allow override in
  advanced Settings. B-grade privacy for the power user, A-grade UX for
  everyone else.

**Ember's recommendation: A,** with the door open to C in a future PR.

**JP needs to:** decide A vs C, and (if either) register the OAuth app
at `github.com/settings/applications/new` with name `storyvox`, homepage
`https://github.com/jphein/storyvox`, callback URL pointing at the
homepage (unused; required by the form), and **Enable Device Flow:
checked**. The resulting 20-char `client_id` lands in
`BuildConfig.GITHUB_CLIENT_ID` via Gradle. Note: the `client_id` is not
a secret in the public-client model — it ships in every APK by design,
so a hardcoded constant or unencrypted `local.properties` value is
fine. Deliberate exception to CLAUDE.md's Vaultwarden rule.

### Open Question #2 — Private-repo scope: opt-in via toggle, or upfront?

This spec recommends the **two-step** path: default sign-in asks for
`read:user public_repo`, and a separate "Enable private repos" toggle
re-runs the flow with `repo` added. The alternative — a "Include private
repos" checkbox on the initial sign-in screen — is simpler to implement
but invites casual over-grant. Two-step keeps the default least-privilege.

### Open Question #3 — Refresh tokens?

Classic OAuth App tokens don't expire and don't have refresh tokens.
**Recommendation: stick with OAuth App, no refresh logic.** Token
survives until the user revokes (at `github.com/settings/applications`)
or storyvox detects 401. GitHub Apps issue 8-hour tokens with refresh
rotation, which is the wrong shape for a long-running reader.

### Open Question #4 — Reuse `auth_cookie` table or add `github_auth`?

Today's `auth_cookie` columns (`sourceId`, `userDisplayName`, `userId`,
`capturedAt`, `expiresAt`, `lastVerifiedAt`) all make sense for GitHub
too. Two options: rename to `auth_session` and add an `extras` JSON
column for source-specific fields like `scopes`, or add a parallel
`github_auth` table. **Ember's lean: rename + reuse** — one place to
look, simpler migration. JP's call.

## Out of scope

- **GitHub Apps (vs OAuth Apps).** Wrong shape — 8-hour tokens with
  refresh rotation are friction we don't need for a sideload reader.
- **Multi-account.** "I want to be signed in as both my work and
  personal GitHub" is a real use case but solves a problem nobody has
  filed yet. Defer until requested.
- **OIDC / SSO.** GitHub Enterprise OIDC flows. storyvox is consumer-targeted.
- **Cross-source SSO.** "One sign-in for both RR and GitHub" — they
  have different identity providers; they stay separate.
- **Server-side token broker.** Would let us hold `client_secret` and
  do remote revoke. Adds infrastructure storyvox doesn't have. Defer
  until storyvox grows a backend (no current plans).
- **Biometric unlock for the token.** Could gate `GitHubAuth.cookieHeader()`
  behind a `BiometricPrompt`. Cute but high-friction for the ratelimit-lift
  use case. Defer; the token's at-rest encryption is the main defense.
- **Token rotation on schedule.** GitHub doesn't offer rotation for OAuth
  App tokens. Skip.
- **Audit log of API calls.** "Show me what storyvox has done with my
  token." Nice-to-have, defer.

## Definition of done

- A user with no GitHub account can use storyvox exactly as they do today
  (browse public GitHub fictions unauthenticated).
- A user who signs in via Settings → GitHub → "Sign in" sees the device
  code on-screen, opens github.com/login/device, authorizes, and lands
  back at Settings showing "Signed in as @username."
- After sign-in, GitHub API calls carry `Authorization: Bearer <token>`.
  Verified by inspecting OkHttp request log in debug builds (with
  `Authorization` redacted, of course).
- The token persists across app restarts — sign-in survives a kill-and-relaunch.
- Sign-out clears all four `token:github*` encrypted-prefs keys, the
  `github_auth` Room row, and the in-memory `StateFlow`. Verified by
  inspecting the prefs file via `adb pull` post-sign-out.
- `EncryptedSharedPreferences` storage verified: token is *not* visible
  in the prefs XML when pulled with `adb`.
- Revoking the token at github.com (without using storyvox) and making
  a subsequent API call from storyvox transitions to `SessionState.Expired`,
  the Settings UI updates to "Session expired," and a re-auth recovers.
- "Enable private repos" toggle re-runs the device flow and persists
  the broader scope. The next call to a private `/repos/{owner}/{repo}`
  endpoint succeeds.
- Galaxy Tab A7 Lite end-to-end test: sign in, see "Signed in as" row,
  add a fiction from a public repo, listen, sign out, sign in again,
  enable private repos, add a private repo, listen.
- Zero new third-party dependencies in the build graph.
- All existing `:core-data` and `:source-github` tests still pass; new
  tests cover `GitHubAuth` persistence, `GitHubAuthInterceptor` header
  attachment, `DeviceFlowApi` polling state machine, and the sign-in
  ViewModel state machine.
