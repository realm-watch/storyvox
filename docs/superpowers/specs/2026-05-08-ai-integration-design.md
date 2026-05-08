# AI Integration — Design Spec

**Author:** Cassia (LLM lane)
**Date:** 2026-05-08
**Status:** Draft + first-PR companion. Spec written and implementation
landed in the same PR per Cassia's brief; the spec sections marked **P1**
and **P2** are roadmap, not part of PR-1.
**Branch:** `dream/cassia/llm-integration-spec`
**Issue:** [#81](https://github.com/jphein/storyvox/issues/81)

## Recommendation (TL;DR)

- **Match cloud-chat-assistant's full provider roster** in the
  abstraction layer — Claude direct, OpenAI, Ollama, AWS Bedrock,
  Google Vertex, Azure AI Foundry, and Anthropic Teams. The
  `LlmProvider` interface accepts all seven; the actual code in
  PR-1 ships **three** of them (Claude direct + OpenAI + Ollama).
  The other four are **spec-only** — the wire-format details and
  open questions are documented here so the next PR can grind them
  out without re-deriving the architecture.
- **BYOK for cloud providers, LAN URL for Ollama.** No proxy, no
  shared key, no payment integration. Mirrors Solara's Azure HD
  voices spec (#85): API key (or AWS profile, GCP token, Foundry
  endpoint+key) sits in `EncryptedSharedPreferences` next to the
  Royal Road cookie and the (forthcoming) GitHub PAT.
- **New `:core-llm` module**, mirroring the `:source-github`
  separation. Pure Hilt-wired Android library. No JNI, no native
  libs — direct REST over OkHttp + kotlinx-serialization, both
  already on the classpath.
- **`LlmProvider` interface**, three implementations shipping in
  PR-1: `ClaudeApiProvider` (Anthropic Messages API, BYOK),
  `OpenAiApiProvider` (OpenAI Chat Completions, BYOK), and
  `OllamaProvider` (OpenAI-compat endpoint). Sealed `ProviderId`
  enum already covers all seven; adding the others is mechanical
  once their auth questions are answered.
- **Multi-session support**, per cloud-chat-assistant's
  `create_session` / `switch_session` / `list_sessions` shape.
  Sessions live in Room (table `llm_session` + `llm_message`) so
  history survives process death. Each session has its own
  provider, model, system prompt, and chat history. **PR-1 ships
  the schema + repository + an "AI Chat" Settings entry that lists
  sessions.** The reader's Chapter Recap is technically a session
  (single-shot, auto-named "Recap of <chapter>") so the storage
  shape and the user-facing chat surface use the same plumbing.
- **One P0 reader feature lands in PR-1: Chapter Recap.** Reader's
  "Player options" sheet gains a "Recap so far" entry. Tap →
  brass-themed modal appears, streams a 150–220 word recap of the
  last three chapters, with Cancel and "Read aloud" buttons.
  Character lookup (P1), voice control (P2), and others enumerated
  below.
- **Privacy disclosure modal** fires on first activation.
  Plain-language: "the text of these chapters will be sent to the
  AI provider you've picked." Persistent toggle in Settings → AI →
  "Send chapter text to AI" so users can disable it after the fact
  without un-installing the feature. The privacy disclosure
  string is **provider-specific** — sending text to Anthropic
  reads differently than sending text to your home Ollama box.
- **Streaming responses must be cancellable.** OkHttp's
  `Call.cancel()` + a coroutine `Job` per request — hitting Cancel
  mid-recap aborts the HTTP body read and unwinds without
  dangling state.

This is the librarian's first apprentice trick: the listener has been
gone from the book for three days, taps the lectern, and the librarian
murmurs back the gist of the last three chapters. Quiet, useful, opt-in.

The librarian's robe has many pockets — Anthropic, OpenAI, the
homelab Ollama box. PR-1 sews three of them; the other four are
patterns drafted on the workbench, ready when JP confirms which
auth-shape each one wants.

## Problem

Listeners come back to a fiction after a week away. They've forgotten
who Cassia is, why the protagonist hates the Vintner's Guild, and what
just happened in chapter 8. Today storyvox offers them a play button
and nothing else. The honest answer to "what just happened?" is "go
re-read chapter 8" — fifteen minutes of audio for a thirty-second
question.

Storyvox already has the substrate for richer queries: every chapter's
plaintext is in Room (`Chapter.text`), the active chapter is on a
`Flow<String>` exposed by `PlaybackControllerUi.chapterText`, and the
reader has a "Player options" bottom sheet ready to host new actions.
The missing piece is a knowledge engine — and JP already runs Ollama
on the LAN, has Anthropic credentials in his Vaultwarden, and wants
**listeners** (his audience, not just himself) to be able to plug in
their own Claude key without storyvox running any server-side
infrastructure.

JP's success criterion: **a listener pauses, taps "Recap so far",
and gets the gist in 10 seconds — no infra, no payment hookup, no
storyvox-as-a-service.**

## Provider matrix

The `LlmProvider` interface accepts seven providers, mirroring
JP's `cloud-chat-assistant/llm_stream.py` plus Anthropic Teams (the
`cc` switcher's "teams" mode). PR-1 ships three; the other four are
spec'd here with their open questions.

| Provider | PR-1 | Auth | Wire format | Streaming | LAN-friendly | Cost (rough) |
|---|---|---|---|---|---|---|
| **Anthropic Claude (direct)** | **YES** | `x-api-key` header | Anthropic Messages | SSE `content_block_delta` | no | $1/1M-input (Haiku 4.5); recap ≈ $0.005 |
| **OpenAI** | **YES** | `Authorization: Bearer` | OpenAI Chat Completions | SSE `choices[0].delta.content` | no | $0.15/1M-input (gpt-4o-mini); recap ≈ $0.001 |
| **Ollama (local)** | **YES** | none | OpenAI-compat | SSE same as OpenAI | yes | free |
| AWS Bedrock | spec-only | SigV4 (HMAC-SHA256) | Bedrock converse-stream | binary event-stream | no | varies by model |
| Google Vertex AI | spec-only | OAuth2 token (gcloud) | Gemini generateContent | SSE `candidates[].content.parts[].text` | no | varies by model |
| Azure AI Foundry | spec-only | `api-key` header + endpoint | OpenAI Chat Completions (deployed) or Foundry serverless | SSE same as OpenAI | no | varies by deployment |
| Anthropic Teams (OAuth) | spec-only | OAuth browser flow → bearer token | Anthropic Messages | SSE same as Claude direct | no | covered by Teams subscription |

### Why these three for PR-1

**Claude direct, OpenAI, and Ollama** were picked because:

1. **Two cloud BYOK shapes, one local.** Claude direct represents
   "single header, simplest auth"; OpenAI represents "the OpenAI-compat
   wire format that 80% of the field shares" (Ollama, DigitalOcean,
   Puter, Foundry serverless, even Azure deployed); Ollama represents
   "I run my own". Together they cover the patterns you'll keep
   adding providers under.
2. **Zero new dependencies.** All three are pure REST + SSE. No
   AWS SDK (~5 MB transitive), no Google auth library, no MSAL.
   The implementation is OkHttp + kotlinx-serialization, both
   already on the classpath.
3. **JP can use all three on day one.** Anthropic key from
   Vaultwarden, OpenAI key from Vaultwarden, Ollama on
   `10.0.6.x:11434`. No new infrastructure or accounts required.
4. **They prove the abstraction.** Adding Bedrock / Vertex /
   Foundry / Teams is mechanical once their open questions are
   answered, because each one is "a different auth + a different
   wire format" and we already proved the seam handles two wire
   formats (Anthropic + OpenAI-compat) and two auth shapes
   (header BYOK + URL-only).

### Spec-only providers

Each one needs a small auth-and-wire-format spike before
implementation. The wire format is a known quantity (cloud-chat-
assistant's `llm_stream.py` has it for all of these); the **auth
flow** is the open question because Android's auth story differs
from a Linux desktop running `gcloud` or `aws configure`.

#### AWS Bedrock

- **Wire format**: `bedrock-runtime.{region}.amazonaws.com/model/{id}/converse-stream`. Body:
  `{modelId, messages, inferenceConfig: {maxTokens, temperature}, system?}`.
  Response: AWS binary event-stream (NOT SSE) — frames carry
  `contentBlockDelta` events with text in `delta.text`. The frame
  parser is ~50 lines of Kotlin (port of `_parse_event_stream` in
  `llm_stream.py`).
- **Auth options**:
  1. **BYOK access key + secret** (recommended). User pastes both
     into Settings → AI → Bedrock; both go to
     `EncryptedSharedPreferences`. We compute SigV4 in-app (port of
     `_aws_sign_v4` from `llm_stream.py` — pure stdlib HMAC).
  2. Pull from `~/.aws/credentials` — N/A on Android (no shared file
     system, no AWS CLI on device).
  3. Cognito Identity Pools — would need a backend to issue temporary
     creds. Out of scope for v1 (proxy mode).
- **Recommendation**: BYOK access key + secret. Add a fourth
  encrypted-prefs row, a small SigV4 signer, the binary event-stream
  parser. Models hardcoded in a small list (Claude 4.x, Nova,
  Llama 4) — same shape as cloud-chat-assistant's `BEDROCK_MODELS`
  map.
- **Open question for JP**: confirm BYOK access-key over
  proxy/Cognito.

#### Google Vertex AI

- **Wire format**: `generativelanguage.googleapis.com/v1beta/models/{model}:streamGenerateContent?alt=sse&key={apiKey}`.
  Body: `{contents: [{role, parts: [{text}]}], system_instruction?}`.
  Response: SSE; tokens in `candidates[0].content.parts[0].text`.
- **Auth options**:
  1. **Vertex API key** (recommended for v1). Google issues simple
     API keys for Gemini that go in the URL query string. Trivially
     BYOK-able. Same shape as Claude direct.
  2. Service-account JSON — file picker UX, JSON parse, OAuth2 token
     refresh. Heavier; defer.
  3. `gcloud auth` — N/A on Android.
- **Recommendation**: Vertex API key. Simplest path; one Settings
  entry; no token refresh.
- **Open question for JP**: confirm API key over service-account
  JSON for v1.

#### Azure AI Foundry

- **Wire format**: depends on deployment type (cloud-chat-assistant
  splits into `deployed` and `serverless`):
  - Deployed: `${endpoint}/openai/deployments/${model}/chat/completions?api-version=2024-12-01-preview`
  - Serverless: `${endpoint}/models/chat/completions?api-version=...`
  - Both use OpenAI-compat wire shape (same as our OpenAI provider!).
- **Auth**: `api-key` header + endpoint URL. Both BYOK; no token
  refresh.
- **Recommendation**: implement as a thin wrapper over the OpenAI
  provider — different base URL builder, different header name
  (`api-key` not `Authorization: Bearer`), same SSE parser, same
  body shape. **Probably 50 lines of Kotlin** once OpenAI provider
  is in.
- **Open question for JP**: do listeners actually want Foundry, or
  is this only "JP's homelab uses it for storyvox-adjacent work"?
  If the latter, defer to a later PR.

#### Anthropic Teams (OAuth)

- **Wire format**: identical to Claude direct (Anthropic Messages
  API, SSE). The difference is the auth flow: instead of an API
  key the user pastes, Teams uses an OAuth-style browser login that
  yields a bearer token.
- **Auth flow**: storyvox launches a `CustomTabsIntent` to
  `console.anthropic.com`, user signs in to their Teams workspace,
  workspace issues a token, redirects back to a custom scheme
  (`storyvox://auth/anthropic-teams`). App captures the token and
  stores in `EncryptedSharedPreferences`. Token refresh on expiry.
- **Recommendation**: defer. The OAuth client registration with
  Anthropic, the redirect-URI handshake, and token-refresh logic
  add real surface area. Direct API + (forthcoming, if you want)
  proxy mode covers most of the user base.
- **Open question for JP**: is Teams a "must-have for first
  release" or "nice to have for some homelab users on a Teams
  subscription"? If the latter, defer.

**Why three for PR-1:**

1. **Two opposite use cases, one shape.** Claude is the cloud
   answer (BYOK, fast, high quality, ~$0.005 / recap). OpenAI is
   the same shape with a different wire format (Bearer auth,
   choices/delta SSE). Ollama is the privacy-preserving local
   answer (LAN URL, free). One UI ("AI" Settings section, one
   provider picker) covers all three, and the abstraction shape
   handles the two wire formats (Anthropic + OpenAI-compat) that
   cover everything except Bedrock's binary event-stream and
   Google's Gemini-shaped JSON.
2. **Auth simplicity.** Claude is one header, OpenAI is one
   header, Ollama is no header. No OAuth, no SigV4, no service-
   account JSON, no token refresh.
3. **No proxy infra.** All three providers talk directly from
   device to endpoint. Storyvox stays a pure-client app, just like
   the existing Royal Road and GitHub source modules.

**REST over SDK** because Anthropic doesn't ship an official Kotlin
SDK and the third-party ones are heavy (anthropic-java pulls in
jackson, guava, ~3 MB transitive). OpenAI's situation is similar.
OkHttp + kotlinx-serialization is ~200 lines of Kotlin per provider
and stays a pure JVM-17 module. We already have both deps.

## Architecture

### Module shape

New module `:core-llm`, alongside `:core-data` and `:core-playback`:

```
core-llm/
  build.gradle.kts                    ← android-library, JVM-17
  src/main/kotlin/in/jphe/storyvox/llm/
    LlmProvider.kt                    ← interface: stream(prompt) → Flow<String>
    LlmMessage.kt                     ← data classes: role, content, system
    LlmConfig.kt                      ← provider choice, model, baseUrl, etc.
    LlmCredentialsStore.kt            ← reads/writes EncryptedSharedPreferences
    LlmRepository.kt                  ← high-level API used by ViewModels
    LlmSessionRepository.kt           ← multi-session CRUD + history
    sse/SseLineParser.kt              ← shared SSE event-line parsing
    provider/
      ClaudeApiProvider.kt            ← Anthropic Messages API (REST + SSE)
      OpenAiApiProvider.kt            ← OpenAI Chat Completions (REST + SSE)
      OllamaProvider.kt               ← OpenAI-compat /v1/chat/completions
    feature/
      ChapterRecap.kt                 ← high-level "recap last N chapters" use case
    di/LlmModule.kt                   ← Hilt bindings
  src/test/kotlin/...
```

Sessions persist in Room — schema lives in `:core-data` so it
participates in the existing migration chain. The new entities
(`LlmSession`, `LlmMessage`) and DAOs (`LlmSessionDao`,
`LlmMessageDao`) are added to `:core-data`'s database, and the
`:core-llm` module consumes them via the standard
`@Inject`-from-DataModule path.

`android-library` plugin (not pure JVM) because we need the
`EncryptedSharedPreferences` Android API. Same shape as `:source-github`.

### `LlmProvider` interface

The seam. One operation, two error channels (config + transport):

```kotlin
/**
 * One LLM backend. Implementations: ClaudeApiProvider, OllamaProvider.
 * Add a new backend = add a class + a config branch + a Settings row.
 *
 * Mirrors the cloud-chat-assistant/llm_stream.py provider abstraction:
 * each implementation knows how to (a) build a streaming HTTPS request
 * for its provider's wire format and (b) parse SSE events into text
 * tokens. The ViewModel collects from a single Flow<String> regardless
 * of which provider's wire-format we're behind.
 */
interface LlmProvider {
    /** Provider identity, used in Settings + error messages + logs. */
    val id: ProviderId

    /**
     * Stream a chat completion. Cold flow — collection starts the
     * HTTPS request; cancellation cancels the OkHttp Call so the
     * server stops billing characters.
     *
     * @param messages user/assistant turns. System prompt is its own
     *   parameter (not a message) because Anthropic puts it in a
     *   separate field — keeping it out of the list dodges
     *   provider-specific reshaping.
     * @param systemPrompt optional. Recap uses this for tone control.
     * @param model canonical model id (e.g. "claude-haiku-4.5",
     *   "llama-3.3-70b"). Provider resolves to its native id.
     * @return cold Flow<String> emitting text deltas in arrival order.
     *   Completes normally on `[DONE]` or stream-end. Throws
     *   LlmError on transport / auth / config failure.
     */
    fun stream(
        messages: List<LlmMessage>,
        systemPrompt: String? = null,
        model: String? = null,
    ): Flow<String>

    /**
     * Lightweight reachability + auth probe. Used by the Settings
     * "Test connection" button and once at first-time activation.
     * Does NOT consume model time — Claude implementation hits
     * `/v1/messages` with a 1-token request; Ollama hits `/api/tags`.
     */
    suspend fun probe(): ProbeResult
}

enum class ProviderId {
    /** Anthropic Messages API (BYOK), implemented in PR-1. */
    Claude,
    /** OpenAI Chat Completions (BYOK), implemented in PR-1. */
    OpenAi,
    /** OpenAI-compat local endpoint (LAN URL), implemented in PR-1. */
    Ollama,
    /** AWS Bedrock converse-stream (BYOK access key), spec only. */
    Bedrock,
    /** Google Vertex Gemini (BYOK API key), spec only. */
    Vertex,
    /** Azure AI Foundry (BYOK endpoint+key), spec only. */
    Foundry,
    /** Anthropic Teams (OAuth bearer token), spec only. */
    Teams;

    /** True for providers that ship in PR-1; false for spec-only. */
    val implemented: Boolean
        get() = this == Claude || this == OpenAi || this == Ollama
}

sealed class ProbeResult {
    object Ok : ProbeResult()
    data class AuthError(val message: String) : ProbeResult()
    data class NotReachable(val message: String) : ProbeResult()
    data class Misconfigured(val message: String) : ProbeResult()
}

sealed class LlmError(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause) {

    /** No API key set, no Ollama URL set, etc. UI surfaces a
     *  "Set up AI in Settings" toast and routes to Settings → AI. */
    class NotConfigured(provider: ProviderId) :
        LlmError("$provider is not configured")

    /** 401 / 403 from Claude. Local Ollama on LAN doesn't auth, so
     *  this is Claude-only. Surface "Key invalid — check Settings". */
    class AuthFailed(provider: ProviderId, msg: String) :
        LlmError("$provider auth failed: $msg")

    /** 429 / 5xx, network EOF, DNS failure. Recoverable; offer "Try
     *  again" + "Cancel" in the modal. */
    class Transport(provider: ProviderId, cause: Throwable) :
        LlmError("$provider transport error", cause)

    /** Provider returned a 4xx / 5xx with a parseable error body that
     *  isn't auth — quota exceeded, model not found, malformed body. */
    class ProviderError(provider: ProviderId, val status: Int, msg: String) :
        LlmError("$provider returned $status: $msg")
}
```

### `LlmMessage` + `LlmConfig`

```kotlin
@Serializable
data class LlmMessage(
    val role: Role,
    val content: String,
) {
    @Serializable enum class Role { user, assistant }
    // System role intentionally absent — system prompt is a separate
    // parameter on stream() because Anthropic puts it in a top-level
    // field rather than the messages array. Keeps providers from
    // having to reshape input.
}

/** Persisted in DataStore (non-secret bits) + EncryptedSharedPreferences
 *  (api keys only). Read at provider construction time.
 *
 *  Per-provider model + auxiliary fields are kept on this single
 *  config object rather than splitting per-provider records, because
 *  (a) the config is small (a dozen scalars) and (b) Settings UI
 *  reads them all at once. When users add Bedrock / Vertex / Foundry /
 *  Teams the corresponding fields are added to the same record. */
data class LlmConfig(
    val provider: ProviderId? = null,        // null = AI disabled

    // Claude direct
    val claudeModel: String = "claude-haiku-4.5",

    // OpenAI direct
    val openAiModel: String = "gpt-4o-mini",

    // Ollama (LAN)
    val ollamaBaseUrl: String = "http://10.0.0.1:11434",   // sentinel default
    val ollamaModel: String = "llama3.3",

    // Bedrock (spec only — fields included so DataStore migrations are
    // additive, not breaking, when the provider ships)
    val bedrockRegion: String = "us-east-1",
    val bedrockModel: String = "claude-haiku-4.5",

    // Vertex (spec only)
    val vertexModel: String = "gemini-2.5-flash",

    // Foundry (spec only)
    val foundryEndpoint: String = "",
    val foundryDeployment: String = "",

    // Cross-provider toggles
    /** First-time-activation modal acknowledged once; user is okay
     *  with the privacy disclosure for the provider they picked. */
    val privacyAcknowledged: Boolean = false,
    /** Hard kill switch. When false, the AI section in Settings still
     *  works (key entry + test) but no chapter text is ever sent —
     *  the Recap button is disabled. Lets users keep their key
     *  configured for occasional use without it being live. */
    val sendChapterTextEnabled: Boolean = true,
)
```

### `LlmCredentialsStore`

Mirrors `AuthRepository`'s pattern exactly:

```kotlin
@Singleton
class LlmCredentialsStore @Inject constructor(
    /** The same EncryptedSharedPreferences instance bound in DataModule
     *  for RR cookies. Tink-backed AES-256-GCM. */
    private val prefs: SharedPreferences,
) {
    fun claudeApiKey(): String? = prefs.getString(KEY_CLAUDE, null)
    fun setClaudeApiKey(key: String) =
        prefs.edit { putString(KEY_CLAUDE, key) }
    fun clearClaudeApiKey() =
        prefs.edit { remove(KEY_CLAUDE) }
    val hasClaudeKey: Boolean get() = claudeApiKey() != null

    fun openAiApiKey(): String? = prefs.getString(KEY_OPENAI, null)
    fun setOpenAiApiKey(key: String) =
        prefs.edit { putString(KEY_OPENAI, key) }
    fun clearOpenAiApiKey() =
        prefs.edit { remove(KEY_OPENAI) }
    val hasOpenAiKey: Boolean get() = openAiApiKey() != null

    // Spec-only — these fields wired now so the prefs layout doesn't
    // change when the providers ship. Each is a method, not a constant,
    // so the encrypted-prefs key namespace remains stable across
    // builds.
    fun bedrockAccessKey(): String? = prefs.getString(KEY_BEDROCK_ACCESS, null)
    fun bedrockSecretKey(): String? = prefs.getString(KEY_BEDROCK_SECRET, null)
    fun setBedrockKeys(access: String, secret: String) = prefs.edit {
        putString(KEY_BEDROCK_ACCESS, access)
        putString(KEY_BEDROCK_SECRET, secret)
    }
    fun clearBedrockKeys() = prefs.edit {
        remove(KEY_BEDROCK_ACCESS); remove(KEY_BEDROCK_SECRET)
    }

    fun vertexApiKey(): String? = prefs.getString(KEY_VERTEX, null)
    fun setVertexApiKey(key: String) =
        prefs.edit { putString(KEY_VERTEX, key) }
    fun clearVertexApiKey() = prefs.edit { remove(KEY_VERTEX) }

    fun foundryApiKey(): String? = prefs.getString(KEY_FOUNDRY, null)
    fun setFoundryApiKey(key: String) =
        prefs.edit { putString(KEY_FOUNDRY, key) }
    fun clearFoundryApiKey() = prefs.edit { remove(KEY_FOUNDRY) }

    fun teamsBearerToken(): String? = prefs.getString(KEY_TEAMS, null)
    fun setTeamsBearerToken(token: String) =
        prefs.edit { putString(KEY_TEAMS, token) }
    fun clearTeamsBearerToken() = prefs.edit { remove(KEY_TEAMS) }

    private companion object {
        const val KEY_CLAUDE = "llm_api_key:claude"
        const val KEY_OPENAI = "llm_api_key:openai"
        const val KEY_BEDROCK_ACCESS = "llm_api_key:bedrock_access"
        const val KEY_BEDROCK_SECRET = "llm_api_key:bedrock_secret"
        const val KEY_VERTEX = "llm_api_key:vertex"
        const val KEY_FOUNDRY = "llm_api_key:foundry"
        const val KEY_TEAMS = "llm_api_key:teams"
    }
}
```

The encrypted-prefs instance already bound in `DataModule` (the one used
for RR cookies) is reused — no new master key, no new file. The Claude
key is stored under `llm_api_key:claude`. Ollama URL is non-secret —
stored in the regular DataStore alongside theme/buffer/voice prefs.

### `ClaudeApiProvider`

```kotlin
class ClaudeApiProvider @Inject constructor(
    private val http: OkHttpClient,
    private val store: LlmCredentialsStore,
    private val configFlow: Flow<LlmConfig>,
    private val json: Json,
) : LlmProvider {

    override val id = ProviderId.Claude

    override fun stream(
        messages: List<LlmMessage>,
        systemPrompt: String?,
        model: String?,
    ): Flow<String> = flow {
        val cfg = configFlow.first()
        val apiKey = store.claudeApiKey()
            ?: throw LlmError.NotConfigured(ProviderId.Claude)
        val resolvedModel = model ?: cfg.claudeModel

        val body = AnthropicRequest(
            model = resolvedModel,
            maxTokens = 1024,
            messages = messages,
            system = systemPrompt,
            stream = true,
        )

        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("content-type", "application/json")
            .header("accept", "text/event-stream")
            .post(json.encodeToString(body).toRequestBody(JSON_MEDIA_TYPE))
            .build()

        http.newCall(request).await { resp ->
            when {
                resp.code == 401 || resp.code == 403 ->
                    throw LlmError.AuthFailed(ProviderId.Claude, resp.message)
                !resp.isSuccessful ->
                    throw LlmError.ProviderError(
                        ProviderId.Claude, resp.code,
                        resp.body?.string()?.take(256) ?: resp.message,
                    )
            }
            // Stream the body. Cancellation of the collecting coroutine
            // closes resp via the suspendCancellableCoroutine callback
            // in await(), which cancels the underlying Call.
            resp.body!!.source().use { src ->
                while (!src.exhausted()) {
                    val line = src.readUtf8Line() ?: break
                    val token = SseLineParser.anthropic(line, json) ?: continue
                    emit(token)
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun probe(): ProbeResult { /* 1-token POST, see below */ }
}
```

Endpoint: `https://api.anthropic.com/v1/messages`

Headers:
- `x-api-key: {key}` — read at request time from `LlmCredentialsStore`.
- `anthropic-version: 2023-06-01` — stable since '23, no churn risk.
- `content-type: application/json`
- `accept: text/event-stream` — explicit, even though `stream: true`
  in the body is enough; some HTTP middleboxes have been known to
  buffer when this header is absent.

Body:
```json
{
  "model": "claude-haiku-4-5",
  "max_tokens": 1024,
  "system": "You are a librarian summarizing the last few chapters...",
  "messages": [
    {"role": "user", "content": "<chapter texts...>\n\nRecap in 200 words."}
  ],
  "stream": true
}
```

Response is SSE; we parse each line and pull out
`event: content_block_delta` payloads' `delta.text` field. Other event
types (`message_start`, `message_delta`, `message_stop`) are ignored.
The `[DONE]` sentinel line ends the stream.

**Streaming + cancellation:** OkHttp's `Call.cancel()` aborts the
in-flight body read on the IO thread. We wrap the call in a
`suspendCancellableCoroutine` so coroutine cancellation cancels the
Call. When the user taps "Cancel" in the modal, the ViewModel cancels
the recap Job → coroutine cancellation propagates → OkHttp body close →
TCP RST to Anthropic → no further token billing. (Anthropic bills only
for tokens emitted up to the cancel; the half-finished message is not
charged.)

### `OpenAiApiProvider`

The OpenAI Chat Completions wire format is the lingua franca of the
LLM space — Ollama, DigitalOcean, Puter, Foundry serverless, and
many others speak it natively. Sharing the parser between OpenAI and
Ollama is intentional: adding a fourth OpenAI-shaped provider in the
future is a `baseUrl` + `header name` change, not a new parser.

```kotlin
class OpenAiApiProvider @Inject constructor(
    private val http: OkHttpClient,
    private val store: LlmCredentialsStore,
    private val configFlow: Flow<LlmConfig>,
    private val json: Json,
) : LlmProvider {

    override val id = ProviderId.OpenAi

    override fun stream(
        messages: List<LlmMessage>,
        systemPrompt: String?,
        model: String?,
    ): Flow<String> = flow {
        val cfg = configFlow.first()
        val apiKey = store.openAiApiKey()
            ?: throw LlmError.NotConfigured(ProviderId.OpenAi)
        val resolvedModel = model ?: cfg.openAiModel

        // OpenAI puts system into the messages array (unlike Anthropic).
        val systemMsg = systemPrompt?.let {
            listOf(OpenAiMessage("system", it))
        }.orEmpty()
        val body = OpenAiRequest(
            model = resolvedModel,
            messages = systemMsg + messages.map {
                OpenAiMessage(it.role.name, it.content)
            },
            stream = true,
            maxTokens = 1024,
        )

        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("content-type", "application/json")
            .header("accept", "text/event-stream")
            .post(json.encodeToString(body).toRequestBody(JSON_MEDIA_TYPE))
            .build()

        http.newCall(request).await { resp ->
            when {
                resp.code == 401 || resp.code == 403 ->
                    throw LlmError.AuthFailed(ProviderId.OpenAi, resp.message)
                !resp.isSuccessful ->
                    throw LlmError.ProviderError(
                        ProviderId.OpenAi, resp.code,
                        resp.body?.string()?.take(256) ?: resp.message,
                    )
            }
            resp.body!!.source().use { src ->
                while (!src.exhausted()) {
                    val line = src.readUtf8Line() ?: break
                    val token = SseLineParser.openAiCompat(line, json) ?: continue
                    emit(token)
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun probe(): ProbeResult {
        // GET /v1/models — instant, doesn't consume model time.
        val apiKey = store.openAiApiKey() ?: return ProbeResult.Misconfigured("No API key")
        val req = Request.Builder()
            .url("https://api.openai.com/v1/models")
            .header("Authorization", "Bearer $apiKey")
            .build()
        return try {
            val resp = http.newCall(req).executeSuspending()
            when {
                resp.isSuccessful -> ProbeResult.Ok
                resp.code == 401 -> ProbeResult.AuthError("Invalid OpenAI key")
                else -> ProbeResult.NotReachable("OpenAI returned ${resp.code}")
            }
        } catch (e: IOException) {
            ProbeResult.NotReachable(e.message ?: "Connection failed")
        }
    }
}
```

### `OllamaProvider`

```kotlin
class OllamaProvider @Inject constructor(
    private val http: OkHttpClient,
    private val configFlow: Flow<LlmConfig>,
    private val json: Json,
) : LlmProvider {

    override val id = ProviderId.Ollama

    override fun stream(
        messages: List<LlmMessage>,
        systemPrompt: String?,
        model: String?,
    ): Flow<String> = flow {
        val cfg = configFlow.first()
        val baseUrl = cfg.ollamaBaseUrl.trim().removeSuffix("/")
        if (baseUrl.isBlank()) throw LlmError.NotConfigured(ProviderId.Ollama)
        val resolvedModel = model ?: cfg.ollamaModel

        // Use the OpenAI-compatibility endpoint Ollama exposes since
        // 0.1.34. Same wire format as OpenAI/Azure/DigitalOcean —
        // means our SSE parser has one shape for "OpenAI-family" and
        // one for "Anthropic" and we cover everything by extension.
        val systemMsg = systemPrompt?.let {
            listOf(OpenAiMessage("system", it))
        }.orEmpty()
        val body = OpenAiRequest(
            model = resolvedModel,
            messages = systemMsg + messages.map {
                OpenAiMessage(it.role.name, it.content)
            },
            stream = true,
            maxTokens = 1024,
        )

        val request = Request.Builder()
            .url("$baseUrl/v1/chat/completions")
            .header("content-type", "application/json")
            .header("accept", "text/event-stream")
            .post(json.encodeToString(body).toRequestBody(JSON_MEDIA_TYPE))
            .build()

        http.newCall(request).await { resp ->
            if (!resp.isSuccessful) {
                throw LlmError.ProviderError(
                    ProviderId.Ollama, resp.code,
                    resp.body?.string()?.take(256) ?: resp.message,
                )
            }
            resp.body!!.source().use { src ->
                while (!src.exhausted()) {
                    val line = src.readUtf8Line() ?: break
                    val token = SseLineParser.openAiCompat(line, json) ?: continue
                    emit(token)
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun probe(): ProbeResult {
        // Hit /api/tags — instant, doesn't consume model time, fails
        // fast if the URL is wrong or Ollama isn't running.
        val cfg = configFlow.first()
        val url = cfg.ollamaBaseUrl.trim().removeSuffix("/") + "/api/tags"
        return try {
            val resp = http.newCall(Request.Builder().url(url).build()).executeSuspending()
            when {
                resp.isSuccessful -> ProbeResult.Ok
                resp.code in 400..499 ->
                    ProbeResult.Misconfigured("Ollama returned ${resp.code}")
                else -> ProbeResult.NotReachable("Ollama returned ${resp.code}")
            }
        } catch (e: IOException) {
            ProbeResult.NotReachable(e.message ?: "Connection failed")
        }
    }
}
```

LAN-only by default. The default URL is the documented `localhost`
shape (`http://10.0.0.1:11434`) with a sentinel that's clearly wrong on
purpose — first-run users see the field, fill in their actual LAN host,
and "Test connection" tells them whether they got it right. We don't
silently default to localhost because storyvox runs on phones and
tablets where localhost is the device itself, almost never the Ollama
host.

### SSE line parser

Shared helper. Two flavors — the Anthropic event-typed shape and the
OpenAI-compat `data: {choices: [{delta: {content: ...}}]}` shape. Direct
port of `cloud-chat-assistant/llm_stream.py:_parse_sse_line`:

```kotlin
internal object SseLineParser {

    /** Anthropic events have a `type` field on the JSON object inside
     *  the `data:` line. `content_block_delta` carries text in
     *  `delta.text`; we ignore other events (start/end/usage). */
    fun anthropic(line: String, json: Json): String? {
        if (!line.startsWith("data: ")) return null
        val data = line.substring(6)
        if (data.isBlank()) return null
        val obj = runCatching { json.parseToJsonElement(data) }
            .getOrNull()?.jsonObject ?: return null
        if (obj["type"]?.jsonPrimitive?.contentOrNull != "content_block_delta") return null
        return obj["delta"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
    }

    /** OpenAI-compat (Ollama, OpenAI, DigitalOcean, Puter, Azure
     *  serverless): `choices[0].delta.content`. `[DONE]` ends. */
    fun openAiCompat(line: String, json: Json): String? {
        if (!line.startsWith("data: ")) return null
        val data = line.substring(6)
        if (data.trim() == "[DONE]") return null
        val obj = runCatching { json.parseToJsonElement(data) }
            .getOrNull()?.jsonObject ?: return null
        val choices = obj["choices"]?.jsonArray ?: return null
        if (choices.isEmpty()) return null
        return choices[0].jsonObject["delta"]?.jsonObject
            ?.get("content")?.jsonPrimitive?.contentOrNull
    }
}
```

### `LlmRepository`

The high-level entry point. Wraps both providers behind the active-
provider config flow:

```kotlin
@Singleton
class LlmRepository @Inject constructor(
    private val configFlow: Flow<LlmConfig>,
    private val claude: ClaudeApiProvider,
    private val ollama: OllamaProvider,
) {
    /** Active provider per current config; null when AI is disabled. */
    val active: Flow<LlmProvider?> = configFlow.map { cfg ->
        when (cfg.provider) {
            ProviderId.Claude -> claude
            ProviderId.Ollama -> ollama
            null -> null
        }
    }

    /** Convenience: stream against the active provider, or throw
     *  NotConfigured if AI is disabled. */
    fun stream(
        messages: List<LlmMessage>,
        systemPrompt: String? = null,
    ): Flow<String> = flow {
        val provider = active.first()
            ?: throw LlmError.NotConfigured(ProviderId.Claude)
        emitAll(provider.stream(messages, systemPrompt))
    }
}
```

### Multi-session storage

Mirrors cloud-chat-assistant's
`create_session` / `switch_session` / `delete_session` /
`list_sessions` shape. Sessions persist in Room so chat history
survives process death — Chapter Recap is technically a session
(single-shot, auto-named "Recap of <chapter title>" so it shows up
in the AI Chat Settings list as a record of "I asked the librarian
this on this date").

#### Schema

Two new tables under `:core-data`'s database. Migration adds them in
the standard `ALL_MIGRATIONS` chain (next version bump on
`StoryvoxDatabase`).

```kotlin
@Entity(tableName = "llm_session")
data class LlmSession(
    @PrimaryKey val id: String,             // UUID
    val name: String,                       // user-visible label
    val provider: ProviderId,               // bound provider for this session
    val model: String,                      // bound model
    val systemPrompt: String? = null,       // optional per-session prompt
    val createdAt: Long,                    // millis epoch
    val lastUsedAt: Long,                   // millis epoch
    /** When non-null, this session was auto-created for a feature
     *  (Chapter Recap, Character Lookup) and the UI hides it from
     *  the main session list by default. */
    val featureKind: FeatureKind? = null,
    /** For feature sessions: the fiction/chapter context that anchored
     *  the session, so a returning user can see "the recap I asked
     *  for on Sky Pride chapter 8". */
    val anchorFictionId: String? = null,
    val anchorChapterId: String? = null,
)

enum class FeatureKind { ChapterRecap, CharacterLookup }

@Entity(
    tableName = "llm_message",
    foreignKeys = [
        ForeignKey(
            entity = LlmSession::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["sessionId"])],
)
data class StoredLlmMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val role: LlmMessage.Role,
    val content: String,
    val createdAt: Long,
)
```

The `LlmMessage` data class (used at the wire/API layer) and the
`StoredLlmMessage` entity (Room storage) are intentionally
distinct — wire-layer messages don't carry an id or timestamp;
stored messages do. The repository converts between them.

#### Repository

```kotlin
@Singleton
class LlmSessionRepository @Inject constructor(
    private val sessionDao: LlmSessionDao,
    private val messageDao: LlmMessageDao,
    private val llm: LlmRepository,
    private val configFlow: Flow<LlmConfig>,
) {

    /** All sessions, newest first. UI filters featureKind != null
     *  out of the main list by default. */
    fun observeSessions(): Flow<List<LlmSession>> =
        sessionDao.observeAll()

    fun observeMessages(sessionId: String): Flow<List<StoredLlmMessage>> =
        messageDao.observeBySession(sessionId)

    /** Create a free-form session bound to the user's currently
     *  active provider + model. Returns the new session id. */
    suspend fun createSession(
        name: String,
        provider: ProviderId? = null,
        model: String? = null,
        systemPrompt: String? = null,
        featureKind: FeatureKind? = null,
        anchorFictionId: String? = null,
        anchorChapterId: String? = null,
    ): String { ... }

    /** Send a user message in the named session, stream the reply,
     *  persist both turns once the stream completes. */
    fun chat(sessionId: String, userMessage: String): Flow<String> = flow {
        val session = sessionDao.get(sessionId)
            ?: throw IllegalStateException("Session $sessionId not found")
        // Persist user turn now (even though reply is in-flight) so
        // a process death mid-stream doesn't lose what the user said.
        messageDao.insert(StoredLlmMessage(
            sessionId = sessionId,
            role = LlmMessage.Role.user,
            content = userMessage,
            createdAt = System.currentTimeMillis(),
        ))
        val history = messageDao.getBySession(sessionId).map {
            LlmMessage(it.role, it.content)
        }
        // Provider override per session — NOT the global active provider.
        val provider = providerFor(session.provider)
        val replyBuf = StringBuilder()
        emitAll(
            provider.stream(
                messages = history,
                systemPrompt = session.systemPrompt,
                model = session.model,
            ).onEach { replyBuf.append(it) }
                 .onCompletion {
                     if (it == null) {  // success
                         messageDao.insert(StoredLlmMessage(
                             sessionId = sessionId,
                             role = LlmMessage.Role.assistant,
                             content = replyBuf.toString(),
                             createdAt = System.currentTimeMillis(),
                         ))
                         sessionDao.touchLastUsed(sessionId,
                             System.currentTimeMillis())
                     }
                 }
        )
    }

    suspend fun deleteSession(sessionId: String) {
        sessionDao.delete(sessionId)   // cascades to messages
    }

    private fun providerFor(id: ProviderId): LlmProvider {
        // … delegated through LlmRepository.providerFor(id) — sessions
        // are bound to a specific provider, ignoring global config.
    }
}
```

This shape means a user can have multiple sessions configured
against different providers — "Sky Pride recap session"
on Ollama, "weekly tarot chat" on Claude, "code questions" on
OpenAI — without globally toggling. The Settings UI exposes this
via a session manager.

#### Why Room (not SQLite or DataStore)?

cloud-chat-assistant uses raw SQLite because it's a Python script.
Storyvox already runs Room for the entire data layer; using it
here is the lowest-friction option. Room migrations are well-
trodden in this codebase. DataStore is wrong-shaped for chat
history — it's a key-value store, not a relational one.

### Chapter Recap use case

The actual P0 user-facing feature. Pulls the last N chapters from Room,
builds the prompt, streams the response:

```kotlin
@Singleton
class ChapterRecap @Inject constructor(
    private val chapterDao: ChapterDao,
    private val llm: LlmRepository,
    private val configFlow: Flow<LlmConfig>,
) {
    /**
     * Stream a 200-word recap of the [recapWindow] chapters preceding
     * (and including) [currentChapterId]. Emits text deltas in arrival
     * order. Caller collects on a coroutine that's bound to a Cancel
     * button — cancellation propagates to OkHttp.
     */
    fun recap(
        fictionId: String,
        currentChapterId: String,
        recapWindow: Int = DEFAULT_WINDOW,
    ): Flow<String> = flow {
        val cfg = configFlow.first()
        if (!cfg.sendChapterTextEnabled) {
            throw LlmError.NotConfigured(cfg.provider ?: ProviderId.Claude)
        }
        val infos = chapterDao.observeChapterInfosByFiction(fictionId).first()
        val currentIdx = infos.indexOfFirst { it.id == currentChapterId }
            .takeIf { it >= 0 } ?: return@flow
        val start = (currentIdx - recapWindow + 1).coerceAtLeast(0)
        val window = infos.subList(start, currentIdx + 1)
        val texts = window.mapNotNull { chapterDao.get(it.id)?.text }
        val joined = window.zip(texts).joinToString("\n\n") { (info, text) ->
            "## ${info.title}\n${text.truncateForBudget(MAX_PER_CHAPTER_CHARS)}"
        }

        val systemPrompt = """
            You are a careful, literate librarian recapping a serial
            web fiction for a returning listener. Output a single
            paragraph of 150–220 words. Cover: who the major characters
            are, what just happened, and what unresolved tension drives
            the next chapter. Quote sparingly. Do not invent details
            not present in the text. Do not editorialize or rate the
            writing. End with a period.
        """.trimIndent()

        val userPrompt = """
            Recap the following chapters of "${window.first().fictionTitle ?: "this fiction"}":

            $joined
        """.trimIndent()

        emitAll(
            llm.stream(
                messages = listOf(LlmMessage(LlmMessage.Role.user, userPrompt)),
                systemPrompt = systemPrompt,
            )
        )
    }

    companion object {
        const val DEFAULT_WINDOW = 3
        /** ~5k chars / chapter × 3 = 15k chars. Claude Haiku has 200k
         *  token context; safely fits. Ollama on a default Llama-3.3
         *  setup is ~8k tokens — we leave 2k for prompt scaffolding +
         *  response, so 6k tokens ≈ 24k chars. Truncating at 5k/chapter
         *  ≈ 15k chars total stays under the smaller budget. */
        const val MAX_PER_CHAPTER_CHARS = 5_000
    }
}

private fun String.truncateForBudget(maxChars: Int): String =
    if (length <= maxChars) this
    else take(maxChars) + "\n[…truncated for AI context budget…]"
```

The librarian system prompt is the personality knob. Keeping it short
and explicit ("don't invent details", "end with a period") gives both
Claude and Llama-3.3 a stable shape. We can refine it as we listen to
real recaps.

### Reader integration

`AudiobookView.kt` already has a "Player options" `ModalBottomSheet`
gated by `MoreVert`. Recap goes there, alongside the existing controls,
so it doesn't crowd the playback surface:

```
[Player options]
  ─ Sleep timer            >
  ─ Voice                  >
  ─ Speed slider
  ─ Pitch slider
  ─ ✦ Recap so far         >    [NEW]
```

Tapping "Recap so far" opens the **Recap modal** — a separate
`ModalBottomSheet` shown above the chapter content. The current
"Player options" sheet dismisses first; the recap takes its place.

### Recap modal UX

```
┌────────────────────────────────────────────┐
│  ✦ Recap so far                             │
│  Last 3 chapters                            │
├────────────────────────────────────────────┤
│                                              │
│  Cassia, the apprentice librarian, has       │
│  just realized the Vintner's Guild's          │
│  ledger she stole in chapter 7 was salted▌   │
│                                              │
│  [streaming, brass cursor blinks at ▌]       │
│                                              │
├────────────────────────────────────────────┤
│  [ Cancel ]      [ Read aloud ]              │
└────────────────────────────────────────────┘
```

States:
1. **Loading** — modal opens, "Asking the librarian…" + brass spinner.
   First token usually arrives within 500–1500 ms on Claude Haiku;
   Ollama varies wildly with the model.
2. **Streaming** — text appears word-by-word; brass cursor blinks at
   the end. Cancel button is live.
3. **Done** — cursor stops; "Read aloud" button enables; Cancel
   becomes Close.
4. **Error** — toast + modal closes. Routing varies per error
   (NotConfigured → Settings → AI; AuthFailed → Settings → AI;
   Transport → "Try again" inline button + Close).

"Read aloud" pipes the recap text through `PlaybackControllerUi` as a
synthetic in-memory chapter. We don't persist it as a Room chapter —
it's ephemeral; closing the modal discards. **In PR-1 we keep "Read
aloud" but it's a follow-up if the synthesis-from-arbitrary-text path
isn't already wired.** First-pass: the button starts a `TextToSpeech`
preview using the active voice's `previewVoice` shape, the same way
the Settings voice picker plays a sample. If that's too jarring (only
plays the first sentence), we promote it to a real synthetic-chapter
pipeline in a follow-up PR.

The brass cursor and modal aesthetics use `LocalSpacing` + the
existing brass color tokens — same palette as the BrassButton and
MagicSpinner. The modal feels like a sibling control, not a modern
chatbot widget.

### Settings UI

A new "AI" section in `SettingsScreen.kt`, between "Audio buffer" and
"Theme". With seven possible providers + multi-session, the section
gets a sub-screen of its own (mirrors how the Voice library is its
own screen, not inline). A single "AI" row at the top level routes
into a dedicated `AiSettingsScreen.kt`:

```
─────────────────────────────────────────                 SettingsScreen.kt
AI

  Active provider: Claude (Haiku 4.5)
  [ Configure AI ]   ← routes to AiSettingsScreen
─────────────────────────────────────────
```

The dedicated screen:

```
< AI                                                       AiSettingsScreen.kt

Provider
  ◉ Off
  ◉ Claude (Anthropic, BYOK)             [implemented]
  ◉ OpenAI (BYOK)                        [implemented]
  ◉ Ollama (local LAN)                   [implemented]
  ◯ AWS Bedrock                          [coming soon]
  ◯ Google Vertex AI                     [coming soon]
  ◯ Azure AI Foundry                     [coming soon]
  ◯ Anthropic Teams (OAuth)              [coming soon]

[when Claude selected:]
  API key: ●●●●●●●●●●●●●●●●●●●●●●●●  [show] [paste]
  Model: [claude-haiku-4.5  ▾]
  [ Test connection ]
  How do I get a Claude API key?  ↗

[when OpenAI selected:]
  API key: ●●●●●●●●●●●●●●●●●●●●●●●●  [show] [paste]
  Model: [gpt-4o-mini  ▾]
  [ Test connection ]
  How do I get an OpenAI API key?  ↗

[when Ollama selected:]
  Server URL: http://10.0.6.50:11434
  Model: llama3.3                         (dropdown populated from /api/tags)
  [ Test connection ]
  How to set up Ollama on the LAN  ↗

[when "coming soon" provider tapped:]
  modal: "Bedrock support is in the design spec but not yet
  implemented. Want to help? See PR #XX."

──────────
Send chapter text to AI                              [ Toggle ]
   Required for Recap. Off means the feature
   is disabled even with a key configured.

Smart features
  ✦ Chapter Recap                                    [Enabled]
  (more coming — see roadmap)

──────────
Sessions
  + New chat session
  - "Sky Pride recap" — Claude, Haiku 4.5 (last used 3d ago)
  - "Voice tutor" — OpenAI, gpt-4o-mini (last used 1w ago)
  - … (feature-kind sessions hidden by default —
       toggle "Show feature sessions" to reveal)

──────────
Forget all AI settings  [ Reset ]
```

The "coming soon" rows are visible but not selectable in PR-1 — same
greyed-out pattern Solara used for Azure voice rows in the picker.
Tapping them surfaces a one-line "in design spec, not yet built"
modal. This sets the expectation that more providers are coming
without forcing PR-1 to ship them.

The "Sessions" subsection is the user-facing entry to the
multi-session system. **PR-1 ships only the schema + repository +
read-only list view** — the chat UI surface (message list, input
field, streaming response area) is a small follow-up PR. The
reason: PR-1's user-visible value is Chapter Recap; a free-form
chat UI is a sibling feature that benefits from its own design
review and would inflate PR-1 past reviewability. The session
list shows feature-kind sessions (Recap-of-X) so users can re-read
their recaps; "+ New chat session" is greyed out with "Free-form
chat coming soon" until the chat UI ships.

The "Send chapter text to AI" toggle is the loud privacy switch. Off
by default? The privacy-first answer is yes; the discoverability
answer is no (a feature that ships disabled is an anti-feature). We
ship **on by default after the first-time disclosure modal acknowledge**
— see Privacy section below.

The "Forget this provider's settings" button clears the API key and
resets the URL (one-tap escape hatch, mirrors the RR sign-out shape).

The "Test connection" button calls `provider.probe()`, surfaces a
toast: "Connection OK" / "Auth failed: bad key" / "Can't reach Ollama
at 10.0.6.50:11434 — check the URL and that Ollama is running".

## BYOK rationale

Same logic as Solara's #85 spec. Briefly:

**BYOK wins** because:
- Storyvox stays a pure-client app. No infra to host, no GDPR scope, no
  abuse mitigation, no payment processor.
- User pays Anthropic / runs their own Ollama. No middleman, no
  surcharge. JP doesn't take on PCI scope.
- Power users with existing Anthropic credentials (the homelab crowd
  that storyvox attracts) plug right in. They keep their billing
  history, alerts, and quotas.
- Matches storyvox's existing shape — RR cookies, GitHub PAT (#86),
  Azure key (#85). The AI key is the fourth tenant of the same
  encrypted store, not a new pattern.

**Proxy is deferred.** If future demand emerges for a zero-onboarding
path, that becomes its own spec (and probably overlaps with #86's
proxy considerations — one billing surface, multiple service types).

## Privacy

Three-tier disclosure:

### 1. First-time activation modal

Triggers once, the first time the user picks any provider in Settings
(transition from `provider == null` to `Claude` or `Ollama`):

> **Heads up — chapter text leaves your device.**
>
> Storyvox sends the text of recent chapters to your chosen AI
> provider so it can answer questions about them.
>
> - **Claude** (cloud): chapters are sent to api.anthropic.com.
>   Anthropic's policy is to not train on API traffic, but verify
>   their current terms.
> - **Ollama** (your own server): chapters are sent to the URL you
>   configured. If that URL is on your home network, the text never
>   leaves your network.
>
> You can disable this any time in Settings → AI → "Send chapter
> text to AI".
>
> [ Cancel ]   [ I understand — enable AI ]

Cancel reverts the provider selection to Off. Accept persists
`privacyAcknowledged = true` in DataStore + leaves the provider
selection where the user put it. Users who later change providers
don't see the modal again — the disclosure is provider-agnostic.

A "Reset privacy disclosure" button in Settings → AI → Advanced lets
testers re-trigger the modal during dogfood.

### 2. Data flow disclosure (always visible)

The body text under the "AI" section header (above the provider
selector) reads:

> Smart features (Recap, character lookup, …) ask an AI to answer
> questions about what you're reading. **Pick a provider, then enable
> a feature.** Local providers (Ollama) keep your text on your
> network; cloud providers (Claude) send it to that company's
> servers.

One sentence, plain language, no marketing speak. Always present so
users who didn't pause for the activation modal still see it.

### 3. Per-feature opt-in (P1+)

Smart features beyond Recap each have their own toggle in the
"Smart features" subsection. Recap is on by default after activation;
character lookup and others land disabled and the user opts in per
feature. Listed in the spec but not built in PR-1.

## Initial features

### P0 (this PR)

**Chapter Recap.** Detailed above. Reader → "Player options" sheet →
"Recap so far" → modal → streaming response. Sends the last 3
chapters' plaintext to the configured provider, asks for a 150–220
word recap, streams the answer.

### P1 (next PR after recap stabilizes)

1. **Character lookup.** Long-press a character name in the reader
   text → context menu shows "Who is X?" → modal opens with a
   character-focused prompt: "Based on the following chapters of this
   fiction, give a 100-word description of [name]." Same plumbing as
   Recap; the prompt and the trigger differ.
2. **Read-aloud the recap.** Properly thread the recap text through
   `PlaybackControllerUi` as a synthetic chapter so the listener can
   close their eyes and have storyvox read the recap to them. Likely
   needs a small `EnginePlayer.synthesize(text: String)` helper.

### P2 (roadmap, not committed)

3. **Voice control.** "Hey storyvox, recap" + "Hey storyvox, who is
   X?" via the existing speech-to-cli MCP shape (or Android's
   built-in `SpeechRecognizer`). The hands-free trigger is the dream
   case for an audiobook app, but voice activation has real
   battery + always-listening privacy costs that need their own spec.
4. **Pronunciation hints.** Tap a word (especially a fantasy proper
   noun) → AI returns IPA + phonetic spelling. Useful but
   relatively niche.
5. **Settings tutor.** Open the Voice library, ask the AI "which voice
   should I use for this fiction?" — AI looks at the genre/tone and
   recommends a voice. Cute, low priority.
6. **Q&A about the book.** Free-form text input: "When did Cassia
   first meet the Vintner?" — searches across chapters via the LLM's
   long context. The interesting question is whether to load the
   whole fiction into context (expensive, slow) or build a small
   embedding index per fiction (engineering work, persistent storage
   cost).
7. **Mood detection.** AI tags each chapter with a mood ("tense",
   "tender", "horror") and the reader paints a mood ribbon on the
   chapter list. Pure aesthetics; ship if the AI integration is
   already trusted.

## Error handling

| Condition | UI response |
|---|---|
| `NotConfigured` (no provider picked) | Recap entry in player-options sheet is disabled with subtitle "Set up AI in Settings". Tap routes to Settings → AI. |
| `NotConfigured` (chapter-text-toggle off) | Same as above; subtitle "Enable 'Send chapter text to AI' in Settings". |
| `AuthFailed` from Claude | Toast "Claude key is invalid". Route to Settings → AI; the Claude key field gets a red outline. Don't auto-clear the key — user might have a typo to fix; clearing makes them re-paste. |
| `Transport` (timeout, EOF, DNS fail) | Modal shows error state with "Try again" + "Close" buttons. "Try again" re-runs the same prompt. |
| `ProviderError` 429 | Modal: "AI is throttled — try again in a moment." Auto-retry once after 2s; if that also 429s, surface error. |
| `ProviderError` 5xx | Modal: "AI service error — try again." Same auto-retry-once shape. |
| Ollama not reachable | Probe surfaces this directly via "Test connection". For an in-flight Recap request, surface "Can't reach Ollama at $url — is it running?" with the URL filled in for context. |
| User taps Cancel mid-stream | Coroutine cancel → OkHttp Call cancel → modal closes. No error state, no toast — cancellation is a normal exit. |

## Cost UX

This is the lighter cousin of Solara's Azure cost surface.

**Recap cost is small enough that a per-request hint is overkill.** A
typical recap input is ~15k chars ≈ 4k tokens; Claude Haiku 4.5 is
$1/1M-input + $5/1M-output. Output ~250 tokens. Cost per recap:
≈ $0.005. Even an obsessive recap-tapper does dozens of dollars a
month before they notice.

So the PR-1 cost surface is:

- **No per-recap cost annotation.** Modal just streams the answer.
- **Settings → AI → Claude → "Estimated cost per recap: ~$0.01"**
  static hint under the API key row. Single sentence, easy reference.
- **No cumulative usage tracking.** Anthropic's console is the source
  of truth. We don't try to mirror it.

Ollama is free. The Settings UI for Ollama doesn't surface cost at
all — that's just how a self-hosted free thing should feel.

If a later P1 feature changes this calculus (a long-context Q&A pulls
in entire fictions and runs $0.20/query), we add a cost hint at that
point, not preemptively.

## Integration with existing features

### Reader

The "Player options" bottom sheet is the host. No changes to
`PlaybackControllerUi` required for Recap (we pull chapter text from
`ChapterDao` directly, not through the playback flow). Read-aloud-the-
recap is the only feature that touches the playback layer, and it's
explicitly in P1 not P0.

### Settings

`SettingsRepositoryUi` gains methods for the new fields:

```kotlin
suspend fun setLlmProvider(provider: ProviderId?)
suspend fun setClaudeApiKey(key: String?)   // null = clear
suspend fun setClaudeModel(model: String)
suspend fun setOllamaBaseUrl(url: String)
suspend fun setOllamaModel(model: String)
suspend fun setSendChapterTextEnabled(enabled: Boolean)
suspend fun acknowledgePrivacy()
```

The non-secret ones store in DataStore alongside theme/buffer; the
key-setting goes through `LlmCredentialsStore`.

`UiSettings` data class extends with corresponding fields.

### Sentence highlighting / sleep timer / voice swap

None of these touch the AI path. Recap is a side-channel — text comes
out of an LLM, into a modal, and either ends in user dismissal or
gets piped through TTS as a one-shot. The chapter playback is
unaffected and continues on its own track.

### MediaSession / Wear / Auto

Recap is a foreground UI feature. Wear and Auto don't see it. (Future
P2 voice control would need to bridge into the MediaSession command
layer; that's its own spec.)

### Brass theming + accessibility

The Recap modal uses the existing brass tokens
(`MaterialTheme.colorScheme.primary`, `LocalSpacing.current`, the brass
button/spinner components). The streaming text is selectable so users
with screen readers can navigate it. Cancel button has a clear
content description; the brass cursor honors `LocalReducedMotion`.

## Testing strategy

Pure-Kotlin unit tests against MockWebServer for both providers:

- **`SseLineParserTest`** — feeds canned Anthropic + OpenAI-compat
  event lines through both parsers, asserts the right tokens come
  out; junk lines and `[DONE]` return null.
- **`ClaudeApiProviderTest`** — MockWebServer returns a canned SSE
  stream; asserts the Flow emits the right text deltas in order.
  Also tests: 401 → AuthFailed; 429 → ProviderError; missing key →
  NotConfigured; cancellation → Call.cancel called (verified via
  MockWebServer's takeRequest + checking the connection close).
- **`OllamaProviderTest`** — same shape, with the OpenAI-compat
  event format. Tests: success path; missing base URL →
  NotConfigured; non-200 → ProviderError; `/api/tags` 200 → ProbeOk;
  IOException on probe → NotReachable.
- **`ChapterRecapTest`** — uses an in-memory Room DB seeded with 5
  chapters; asserts the recap pulls chapters N-2..N (inclusive),
  truncates per-chapter at the budget, builds the right prompt
  (snapshot test on the messages list), and proxies the LLM
  Flow<String> through to its caller.
- **`LlmCredentialsStoreTest`** — mocks `SharedPreferences` (no
  encrypted-prefs in unit test, but the wrapper is so thin that
  mocking the underlying iface covers behavior); asserts get/set/
  clear semantics.

Compose smoke test:

- **`RecapModalTest`** — Compose ui-test renders the modal in
  loading + streaming + done + error states using a fake
  `ChapterRecap` whose `recap()` returns a controllable test Flow.
  Asserts the cursor visibility and the buttons' enabled states for
  each.

Integration test:

- **`SettingsAiSectionTest`** — the new AI section renders correctly
  given various LlmConfig + provider+key combinations; toggles flip
  state; "Test connection" fires the probe.

## Build + dependencies

`build.gradle.kts` for `:core-llm`:

```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "in.jphe.storyvox.llm"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":core-data"))   // ChapterDao access for ChapterRecap

    implementation(libs.androidx.security.crypto)   // already in catalog
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation(libs.kotlinx.coroutines.test)
}
```

Module wired into `settings.gradle.kts`. App + feature modules add
`implementation(project(":core-llm"))` where they consume it.

**No new external deps.** OkHttp + kotlinx-serialization +
security-crypto are all already on the version catalog. `mockwebserver`
is a test-only addition (already used implicitly by other modules'
OkHttp tests but not declared on the catalog yet — we add it as a
versioned test dep here).

## PR sequence

This brief is "single PR closes #81", so the implementation lands
together. For future maintenance, the natural slicing — should anyone
need to back this out or extend it — is:

1. **(landed in this PR) `:core-llm` skeleton + ChapterRecap.** Module,
   provider interface, both providers, credentials store,
   ChapterRecap use case, Settings UI section, Reader entry point,
   Recap modal. All tests.
2. **Future PR — Character lookup.** Adds the long-press menu in
   reader, a `CharacterLookup` use case, a lookup modal. Reuses the
   existing LlmRepository + ChapterDao pieces.
3. **Future PR — Read-aloud-the-recap.** Threads the recap text
   through `PlaybackControllerUi` as a synthetic chapter.
4. **Future PR — Voice control (P2).** Brings in speech recognition;
   its own spec.

## Risks

| risk | mitigation |
|---|---|
| **Anthropic rate-limits a key.** F-tier free trial has aggressive throttling. | 429 path in error handling; auto-retry-once + user-facing "throttled, try again". Ollama path keeps working as a fallback. |
| **Ollama URL is wrong.** First-run users mis-type. | "Test connection" button surfaces the failure immediately, with the URL in the message for context. Default URL is a sentinel that's clearly wrong (10.0.0.1 — not localhost) so users don't silently send to a bad endpoint without noticing. |
| **API key is leaked via logs.** | Standard rule: never log the key. Logging interceptor filters `x-api-key` header. Test asserts the header is stripped from any log output the OkHttpClient emits. |
| **Token-budget overflow.** A fiction with very long chapters can blow the 200k-token Claude window or the smaller Ollama context. | `MAX_PER_CHAPTER_CHARS = 5000` truncates per-chapter; recap window is 3 chapters; total ≈ 15k chars ≈ 4k tokens. Fits both. If JP later wants 5-chapter recaps we revisit. |
| **Cost shock on Claude.** Recap-tapping kid runs up a $50 bill on parents' API key. | Static cost hint in Settings ("~$0.01 per recap"). Hard cap is out of scope (matches Solara's Azure spec). For homelab JP this is theoretical; if storyvox grows beyond friends-and-family we revisit. |
| **Privacy regression.** A future feature ships chapter text to AI without disclosure. | The `sendChapterTextEnabled` toggle is the chokepoint. New features MUST consult it before sending text. The toggle is a hard gate, not a soft hint. We add a lint rule (`grep` in CI) that checks every new caller of `LlmRepository.stream()` is preceded by a `sendChapterTextEnabled` check. |
| **Anthropic API version bump.** `2023-06-01` is currently stable but Anthropic could break it. | Version is a constant; bumping is a one-line change. Tests pin against canned wire format so a regression is caught immediately. |
| **Ollama OpenAI-compat endpoint changes.** Less stable than Anthropic; Ollama is a young project. | Bake the endpoint URL into a constant; pin Ollama model versions with the user's `ollamaModel` setting. If Ollama breaks the compat shape, fall back to its native `/api/chat` (newline-delimited JSON, slightly different parser). |
| **Long recaps in slow Ollama configs.** A 7B model on a CPU-only Ollama box can take 30–60s to recap. | Modal shows a "this might take a moment on slow models" hint after 5s of streaming-without-progress. Cancel button is always live. |
| **Provider abstraction leaks.** Future provider needs something the two-shape parser can't express. | Provider interface accepts the burden — `stream()` is the seam. Adding e.g. Bedrock means a new class with a new SSE event-stream parser, contained to that provider. The two-parser shape covers ~95% of real-world LLM providers; outliers are isolated. |
| **Multi-session schema migration.** Adding `llm_session` + `llm_message` tables bumps `StoryvoxDatabase` version. | Standard Room migration path — `MIGRATION_X_Y` in the existing `ALL_MIGRATIONS` chain. Tables are new (no data movement), so the migration is one `CREATE TABLE` per table + the index. Test the migration with the existing `MigrationTestHelper` pattern. |
| **Spec-only providers diverge from spec.** PR-1 ships a Settings UI showing 7 providers; later PRs implement them. The wire format documented here may be wrong by the time someone codes Bedrock. | Each spec-only provider section explicitly says "verify wire format against current cloud-chat-assistant on PR start". The wire formats are pinned to API versions (Anthropic `2023-06-01`, Bedrock `converse-stream`, Vertex `v1beta`) which have been stable; even so, the implementer's first task is to verify, not assume. |

## Out of scope

- **Multi-cloud.** OpenAI, Gemini, Bedrock, Azure OpenAI — all viable
  and all use the same provider abstraction. Each is ~150 lines of
  Kotlin + a Settings entry. Defer until users ask. The shape is
  proven by Claude + Ollama.
- **Long-context Q&A.** "Ask any question about this fiction" with
  an embedding index per fiction. Substantially more engineering;
  probably its own module.
- **Streaming play-as-you-recap.** Pipe the recap straight into TTS
  before the LLM finishes. Doable but adds an extra coordination
  layer; defer until users ask. P1 just plays the finished recap.
- **MCP integration.** Storyvox could expose its library/playback
  state as an MCP server (à la JP's mempalace, cloud-chat-assistant).
  Powerful but requires a separate transport (stdio? local socket?
  HTTP?) — its own spec.
- **AI-driven voice selection.** "AI, pick a voice for this fiction."
  Too much agency for v1; defer to the P2 settings tutor.
- **Cost capping / usage tracking.** Anthropic's console is the
  source of truth; reimplementing is wrong-shaped work for v1.
- **Custom models / LoRA / RAG over the user's library.** All cool,
  none for v1.
- **Federation / shared keys.** "Storyvox-issued AI key" — the proxy
  shape from #85's Open Questions. Same answer: deferred.

## Open questions for JP

### Scope

0. **Spec-only providers — confirm or cut.** Four are spec'd here
   (Bedrock, Vertex, Foundry, Teams) with their open auth questions.
   Are any of them not actually wanted? Specifically:
   - **Bedrock**: BYOK access-key + secret + SigV4 signer in-app
     (~50 lines), or punt to a proxy mode (~storyvox-side server)?
     Cassia recommends **BYOK SigV4**, mirrors cloud-chat-assistant.
   - **Vertex**: API key in URL (simplest), or service-account JSON
     (more capable, more complex)? Cassia recommends **API key**.
   - **Foundry**: thin OpenAI-compat wrapper, or full Foundry SDK?
     Cassia recommends **thin wrapper**.
   - **Teams**: full OAuth flow with redirect handlers, or skip
     entirely? Cassia recommends **skip** — direct API + future
     proxy mode covers most users; Teams adds real Android-OAuth
     surface area without a proportionate user-base win.
   Lock these in before PR-1 lands so the spec doesn't promise
   what we won't deliver.

### Defaults

1. **Default provider on first install: Off (recommend) vs Ollama
   guess vs Claude entry?** Off is the safest — no surprise traffic.
   But it leaves the AI section feeling vestigial until the user
   discovers it. Cassia recommends **Off**, with a one-line "AI
   features available — set up in Settings" prompt the first time
   the user opens the player-options sheet (and never again).

2. **First-time disclosure: required vs skippable?** The privacy
   modal makes the first AI activation deliberate. Two paths:
   - Required (recommend): the modal blocks until acknowledged. Can't
     enable AI without seeing the disclosure.
   - Skippable: a "don't show again" checkbox on the modal. Faster
     for power users, riskier for the "kid hands phone to grandma"
     case.

   Cassia recommends **required**; the cost is one tap and the gain
   is a clean trail of consent.

3. **Ollama default URL: sentinel `10.0.0.1` vs blank field vs
   localhost?** `10.0.0.1` is clearly wrong and prompts the user to
   fix it; blank field is honest but UI-ugly; localhost is a trap
   on a phone. Cassia recommends **sentinel `10.0.0.1`** —
   immediately wrong, immediately fixable, no silent failure.

4. **Claude default model: Haiku vs Sonnet?** Haiku is cheap and
   fast (≈ $0.005/recap); Sonnet is markedly better at narrative
   summarization (≈ $0.05/recap). Cassia recommends **Haiku 4.5**
   for v1 — recap is a low-stakes summarization task and Haiku
   nails it; the user can switch to Sonnet via the model dropdown
   if they want extra polish.

5. **Recap window size: 3 chapters vs 5 vs user-configurable?**
   3 fits both Claude's huge context and Ollama's small one. 5
   means we have to truncate harder for Ollama and would push small
   contexts past the limit. **3 is the right v1 default**; expose
   it as a slider in Settings only if users ask.

6. **Read-aloud button in PR-1 or P1?** The brief mentions it as
   part of the recap modal. The clean implementation needs a
   `EnginePlayer.synthesizeAdHoc(text: String)` helper that doesn't
   exist today — we'd build it new. Cassia recommends **PR-1 ships
   the button greyed out with a "coming soon" tooltip**, and the
   first P1 PR after this brings the feature live. Keeps PR-1
   scope contained; doesn't fragment the modal aesthetic.

7. **Should the AI section live in a sub-screen rather than
   inline in the main Settings list?** Inline is simpler and matches
   the existing structure. A sub-screen scales better as P1 features
   add toggles. Cassia recommends **inline for v1**; promote to
   sub-screen if it crosses ~5 controls (the threshold the existing
   "Voices" section is approaching).

8. **Anthropic-version pin: leave at `2023-06-01` or bump to a
   newer header?** Anthropic has continued to add events
   (`message_delta`, `usage`); we ignore the new ones gracefully.
   Cassia recommends **leave at `2023-06-01`** — we don't need any
   newer feature, and the older version is the most-tested wire
   format on Anthropic's side.

## Definition of done

For the spec:
- JP has reviewed and either approved or annotated open questions.
- BYOK + Ollama provider choice locked in.
- P0 = Chapter Recap only, locked in.
- Privacy disclosure shape locked in.

For the implementation (this PR):
- `:core-llm` module compiles and tests green.
- All three provider unit tests pass against MockWebServer
  (Claude, OpenAI, Ollama).
- ChapterRecap test green against an in-memory Room DB.
- LlmSession schema + DAOs in `:core-data`, with a Room migration
  test against the previous schema version.
- Settings → AI section renders, persists, surveys via Test
  connection on each implemented provider.
- "Coming soon" rows for Bedrock / Vertex / Foundry / Teams render
  greyed out with the design-spec disclosure modal.
- Reader → Player options → Recap so far → modal opens, streams a
  recap on at least one provider configured locally.
- First-time-activation modal fires and blocks until acknowledged.
- Per-provider privacy disclosure strings (sending text to
  Anthropic vs. OpenAI vs. local Ollama).
- API key never appears in logs (verified by adding an OkHttp
  logging-interceptor test that asserts header redaction for both
  `x-api-key` and `Authorization`).
- Cancel mid-stream cancels OkHttp Call within ~250 ms (verified by
  MockWebServer takeRequest + close-detection).
- Tablet install: Recap actually generates plausible text on at
  least one provider configured at JP's bench.
- No version bump, no tablet install in PR script (orchestrator owns
  both).
