# Azure Cloud HD Voices — Design Spec

**Author:** Solara (cloud lane)
**Date:** 2026-05-08
**Status:** Draft, awaiting JP review — **no implementation until approved**
**Branch:** `dream/solara/azure-hd-voices-spec`
**Issue:** [#85](https://github.com/jphein/storyvox/issues/85) (split from #80)

## Recommendation (TL;DR)

- **Azure Speech Services REST API**, not the full SDK. Lighter footprint
  (no native libs, no AAR), trivial to vendor as plain OkHttp + JSON.
- **BYOK** (Bring-Your-Own-Key). User pastes a Speech resource key into
  Settings → Sources → Azure; key persists in `EncryptedSharedPreferences`
  next to RR cookies and the GitHub PAT (#86). No proxy infra to run.
- **New `:source-azure` module**, mirroring the `:source-royalroad` /
  `:source-github` separation. Pure JVM module — no engine AAR, no JNI.
- **`EngineType.Azure`** joins `Piper` and `Kokoro` in the existing voice
  catalog. Azure voices appear in the picker under a "Cloud — Azure"
  section with Studio tier and a per-voice cost annotation.
- **Sentence-keyed SSML synthesis**, one HTTP request per sentence. Keeps
  the PCM-cache key model (Aurelia's #94 spec) intact: each sentence
  drops into the existing `PcmAppender` exactly like a Piper-generated
  sentence does today, so the cache layer doesn't need to know cloud TTS
  exists.
- **Graceful offline**: Azure voices grey out when the network is down;
  optional Settings toggle "Fall back to local voice when offline" picks
  a user-chosen Piper/Kokoro voice as the backup engine.

This is a **multi-PR effort**. The PR sequence at the bottom breaks it
into ~7 reviewable steps, each shippable on its own — the early ones
(BYOK plumbing, Settings entry) land value before the engine wiring is
done.

## Problem

Local synthesis on the target device class — Galaxy Tab A7 Lite
(MediaTek Helio P22T, 3 GB RAM) — caps quality at "tolerable" even with
the PCM cache (Aurelia, [PCM cache spec](2026-05-07-pcm-cache-design.md)).
The measured baseline from the overnight Sky Pride run on Piper-high
"cori":

| metric | value |
|---|---|
| realtime factor | **0.285×** |
| inter-chunk audible gap (median) | 8021 ms |
| inter-chunk audible gap (max) | 19601 ms |

The PCM-cache plan addresses replay (cache hit = gapless on any device).
First-ever play of a chapter still streams at the engine's native pace,
which on Piper-high means buffering pauses that effectively double the
chapter's wall-clock duration.

**Cloud HD bypasses CPU entirely.** Azure's Neural HD voices and the
newer Dragon HD tier render server-side and stream audio over HTTPS at
realtime or faster — the device just decodes a 16 kHz mono PCM stream
into AudioTrack. The cost is paid in money (per-character billing) and
network dependency rather than CPU. For a user who has paid Azure $5 and
wants to listen to their slow tablet on a long bus ride, that's the
right trade.

JP's success criterion: **a high-quality voice on a slow device, with
cost transparency and no implementation backflips**.

## Provider choice

We're picking the cloud provider **and** the integration shape (SDK vs
REST). Considered:

| Provider | API style | HD tier | Cost (USD per 1M chars) | Notes |
|---|---|---|---|---|
| **Azure Speech Services** | REST + SDK | HD Neural (`*Neural`), Dragon HD (`*DragonHDLatestNeural`) | $30 (HD), $30 (Dragon HD) [needs verification at GA pricing] | Generous F0 free tier (500K chars/month), broad voice roster, SSML support, regional endpoints. Low bar for sideload Android — REST is curl-able. |
| Google Cloud TTS | REST + SDK | Studio voices | $30 ($16 Neural2) | Comparable quality, but auth is GCP service-account JSON — heavier BYOK UX (file picker, JSON parsing, OAuth2 token refresh). |
| Amazon Polly | REST + SDK | Generative voices | $30 (Generative), $16 (Neural) | Comparable. AWS SigV4 auth is a non-trivial signer to vendor (need HMAC-SHA256 of canonical request); not impossible but more code than an Azure subscription-key header. |
| ElevenLabs | REST | "Eleven HD" / "Multilingual v2" | $0.30 / 1K chars at lowest tier ≈ $300 / 1M | Highest quality. ~10× the price. Defer until users ask. |
| OpenAI TTS | REST | tts-1, tts-1-hd | $15 (HD) | Cheap, decent quality. No SSML; one voice = one prosody. Defer; it's a future second-source if Azure proves the integration shape. |

**Azure wins on three vectors:**

1. **Auth simplicity** — a single `Ocp-Apim-Subscription-Key` header on
   every request. No token refresh, no signer, no service-account JSON.
2. **Free tier** — F0 SKU gives 500K chars/month free for HD voices (or
   0.5M chars/month for Neural). Enough for a cautious user to try the
   feature without entering payment details on Azure first.
3. **SSML + streaming** — chunked-transfer-encoding response with raw
   PCM (`audio-16khz-16bit-mono-pcm`) drops straight into our existing
   PCM pipeline. No decode step, no MP3/Opus dependency.

**REST over SDK** because the Azure Speech SDK ships native libs (~25 MB
across 4 ABIs) and pulls in MSAL for auth flows we don't need. We're
issuing one POST per sentence with a fixed header set; OkHttp + a tiny
SSML builder is ~150 lines of Kotlin and stays a pure JVM module. The
SDK's nominal advantages (push-stream synthesis, fancy event callbacks,
auto-retry) duplicate behavior we already have in `EngineStreamingSource`.

## Architecture

### Module shape

New module `:source-azure`, mirroring the `:source-github` separation:

```
source-azure/
  build.gradle.kts             ← pure JVM, no Android plugin
  src/main/kotlin/in/jphe/storyvox/source/azure/
    AzureSpeechClient.kt       ← OkHttp wrapper, one method: synthesize(SSML) → PCM stream
    AzureVoiceCatalog.kt       ← static Azure voice list (Dragon HD + Neural HD subset)
    AzureSsmlBuilder.kt         ← Sentence text → <speak><voice>...</voice></speak>
    AzureRegion.kt             ← enum of supported regions (eastus default)
    AzureCredentials.kt        ← key + region wrapper, read from EncryptedSharedPreferences
    AzureVoiceEngine.kt        ← VoiceEngineHandle adapter (wires into EngineStreamingSource)
    di/AzureModule.kt          ← Hilt bindings
  src/test/kotlin/...
```

Pure-JVM (no Android plugin) so unit tests run without the emulator,
same as `:source-github`. The Android wiring (Hilt, EncryptedSharedPrefs
lookup) lives in the `core-data` / `app` modules consuming this one.

### Catalog integration

`EngineType` gains a third variant alongside `Piper` and `Kokoro`:

```kotlin
sealed interface EngineType {
    data object Piper : EngineType
    data class Kokoro(val speakerId: Int) : EngineType
    /**
     * Azure Speech Services HD voice. [voiceName] is the Azure voice id
     * (e.g. "en-US-AndrewMultilingualNeural" or
     * "en-US-AvaDragonHDLatestNeural"). [region] is the Azure resource
     * region; user-configurable in Settings, defaults to eastus.
     */
    data class Azure(val voiceName: String, val region: String) : EngineType
}
```

`VoiceCatalog` gains a third helper, `azureEntries()`, contributing
~20 hand-picked voices (the en-US Dragon HD set + a few en-GB Neurals).
The cost annotation surfaces via a new optional field on `CatalogEntry`:

```kotlin
data class CatalogEntry(
    val id: String,
    val displayName: String,
    val language: String,
    val sizeBytes: Long,         // 0 for Azure (no install)
    val qualityLevel: QualityLevel,
    val engineType: EngineType,
    val piper: PiperPaths?,
    val cost: VoiceCost? = null, // NEW. null for local engines.
)

data class VoiceCost(
    val centsPer1MChars: Int,    // e.g. 3000 = $30/1M chars
    val billedBy: String,         // "Azure" — surfaces in the cost modal
)
```

Local engines stay `cost = null`. Azure entries fill it in. The picker UI
reads `cost` and renders an annotation chip ("$30 / 1M chars · Azure")
under the voice display name.

### Engine seam

The existing `VoiceEngineHandle` interface from `EngineStreamingSource`
is the seam we extend:

```kotlin
interface VoiceEngineHandle {
    val sampleRate: Int
    fun generateAudioPCM(text: String, speed: Float, pitch: Float): ByteArray?
}
```

This shape works for Azure too — synthesize one sentence, return its
full PCM payload as a byte array. The producer/consumer queue handles
the rest. **No engine-seam refactor required.** The contract is already
"give me PCM for this sentence text"; Azure satisfies it via HTTP instead
of JNI.

`EnginePlayer.activeVoiceEngineHandle(engineType)` extends:

```kotlin
private fun activeVoiceEngineHandle(engineType: EngineType?) =
    object : EngineStreamingSource.VoiceEngineHandle {
        override val sampleRate: Int = when (engineType) {
            is EngineType.Kokoro -> KokoroEngine.getInstance().sampleRate
            is EngineType.Azure  -> AZURE_PCM_SAMPLE_RATE  // 16000 (matches outputFormat)
            else                 -> VoiceEngine.getInstance().sampleRate
        }.takeIf { it > 0 } ?: DEFAULT_SAMPLE_RATE

        override fun generateAudioPCM(text, speed, pitch): ByteArray? = when (engineType) {
            is EngineType.Azure  -> azureVoiceEngine.synthesize(text, engineType, speed, pitch)
            is EngineType.Kokoro -> KokoroEngine.getInstance().generateAudioPCM(text, speed, pitch)
            else                 -> VoiceEngine.getInstance().generateAudioPCM(text, speed, pitch)
        }
    }
```

Nice property: the existing `engineMutex` semantics still apply
trivially — since the Azure path is HTTP, two concurrent calls don't
corrupt anything, but holding the mutex during a synth is harmless and
keeps voice-swap teardown predictable. We can relax this later (allow
parallel sentence synth on Azure for throughput) but **PR-1 keeps the
serialized contract** so it slots in behind the existing producer loop
with zero changes to `EngineStreamingSource`.

### `AzureSpeechClient`

Thin OkHttp wrapper. One operation:

```kotlin
class AzureSpeechClient(
    private val http: OkHttpClient,
    private val credentials: AzureCredentials,
) {
    /**
     * POSTs SSML to Azure's text-to-speech endpoint, streams the PCM
     * response body, returns it as a ByteArray. Throws on non-2xx;
     * caller (AzureVoiceEngine) maps to AzureError types.
     */
    fun synthesize(ssml: String): ByteArray
}
```

Endpoint: `https://{region}.tts.speech.microsoft.com/cognitiveservices/v1`

Headers:
- `Ocp-Apim-Subscription-Key: {key}` — the resource key, read from
  `EncryptedSharedPreferences` at request time (caller-provided via
  `AzureCredentials`).
- `X-Microsoft-OutputFormat: raw-16khz-16bit-mono-pcm` — gives us PCM
  identical in shape to Kokoro's output (16-bit signed mono, 16 kHz);
  drops directly into AudioTrack at the matching sample rate.
- `Content-Type: application/ssml+xml`
- `User-Agent: storyvox/{version}`

The body is the SSML built by `AzureSsmlBuilder`. Response body is the
raw PCM stream — read fully into a ByteArray (bytes ≤ ~150 KB for a
typical sentence at 16 kHz mono = ~5 s of audio). Buffering the whole
sentence is fine; this matches the Piper/Kokoro shape and keeps the
existing `EngineStreamingSource` queue semantics.

### `AzureSsmlBuilder`

```kotlin
internal fun buildSsml(
    text: String,
    voiceName: String,
    speed: Float,
    pitch: Float,
): String {
    val rate = ((speed - 1f) * 100).toInt().let { if (it >= 0) "+$it%" else "$it%" }
    val pitchPercent = ((pitch - 1f) * 100).toInt().let { if (it >= 0) "+$it%" else "$it%" }
    return """<speak version='1.0' xml:lang='en-US' xmlns='http://www.w3.org/2001/10/synthesis'>
        <voice name='$voiceName'>
            <prosody rate='$rate' pitch='$pitchPercent'>${text.escapeXml()}</prosody>
        </voice>
    </speak>""".trimIndent()
}
```

XML-escape the sentence text to defend against `<` / `&` in chapters
(rare but real — some HTML decoder quirks let `&amp;` through into the
sentence). Speed and pitch translate to SSML `prosody` percentages.
Azure's clamping of these is generous (±50%) so the existing storyvox
speed slider (0.75–2.5×) maps cleanly.

### Sentence chunking strategy

**One sentence per request**, matching Piper / Kokoro. Pros:

- Matches the `Sentence` / `PcmChunk` granularity already in use.
- The PCM cache key (Aurelia's spec) is sentence-keyed; one HTTP
  request → one cache file entry. No alignment work.
- Sentence highlighting + sentence-trailing pause logic (issue #90)
  Just Works.
- Easy to retry on transient HTTP failure: only the failed sentence
  re-requests, not the whole chapter.

Cons:

- ~1 HTTP request per second of audio. Per-request TLS handshake adds
  latency; OkHttp's connection pool reuses the TCP connection so
  steady-state cost is one round-trip per sentence (~50–150 ms).
- Each request has ~120 bytes of fixed overhead (SSML wrapping). At
  $30/1M-chars billing, the SSML envelope is **billed on text
  content only**, not the wrapper, so the overhead is wire bytes, not
  cost.

**Alternative considered:** chunk by paragraph (~3–8 sentences per
request). Cuts request count 5× but breaks the PCM cache's
per-sentence index, requires re-segmenting the streamed PCM by
listening for SSML `<mark>` events, and costs more on retry. **Not
worth it for v1.** Revisit if real-world latency proves problematic.

### Streaming response handling

Azure responds with chunked-transfer-encoding. OkHttp exposes the body
as a streaming `Source`. Two implementation choices:

**A. Read fully into ByteArray (recommended for v1).** Same shape as
Piper/Kokoro — `generateAudioPCM` returns the full sentence PCM as a
byte array. The producer queue carries one `PcmChunk` per sentence;
playback hits AudioTrack as it does today.

**B. Stream sub-sentence chunks into the existing queue.** Yield each
8 KB read of the response body as its own `PcmChunk`. Faster
time-to-first-audio (don't wait for full sentence to download) but
shatters the per-sentence cache contract and complicates sentence
highlighting (the sentence range now crosses N chunks).

**v1 picks A.** Azure's HD voices stream at realtime+ on a ~3 Mbps link;
a typical sentence (~5 s of audio = ~80 KB at 16 kHz mono) downloads in
~200–400 ms on a residential connection. The first-sentence latency
is acceptable. If JP wants sub-second time-to-first-audio later, B
becomes a follow-up PR.

## BYOK vs proxy

**Recommendation: BYOK.**

Two paths considered:

### BYOK (recommended)

User creates an Azure Speech resource at `portal.azure.com`, copies the
key, pastes it into Settings → Sources → Azure. Storyvox uses it directly.

**Pros:**

- **No infra to run.** Storyvox stays a pure-client app; we don't host
  anything cloud-side. No latency tax, no privacy footprint, no scaling
  burden, no key rotation logistics.
- **User pays Azure directly.** No middleman, no surcharge, no payment
  processor in storyvox's surface area. JP doesn't take on PCI scope.
- **Free tier.** Azure F0 SKU = 500K chars/month free for HD. New users
  can verify the feature works before entering payment details on Azure.
  A storyvox proxy can't easily expose this without complex per-user
  metering.
- **Respects user agency.** Power users with existing Azure subscriptions
  (corporate, hobby, academic) plug right in. They keep their billing
  history, alerts, region choice, and resource lifecycle.
- **Matches existing patterns.** Storyvox already uses
  `EncryptedSharedPreferences` for RR cookies and (per #86 GitHub
  source) the GitHub PAT. Azure key is one more entry in that store.

**Cons:**

- **Onboarding friction.** "Make an Azure account" is a real bar. We
  mitigate via in-app docs (Settings → Sources → Azure → "How do I get
  a key?" link to a one-page guide).
- **Cost exposure.** User can rack up real money fast on Azure if the app
  runs Azure synth for a 50-chapter binge. We mitigate via the cost UX
  below (onboarding modal + per-chapter hint + offline fallback).
- **Key on device.** A compromised device is a compromised key.
  Mitigated by `EncryptedSharedPreferences` (Tink-backed AES-256-GCM).
  Same threat model as the RR cookie; not worse.

### Proxy (deferred)

Storyvox would run a server that holds the Azure key and brokers
requests; user authenticates with a storyvox-issued token.

**Pros:**

- Single onboarding step (storyvox account, no Azure portal).
- Hides the key from end devices (slightly).

**Cons:**

- **JP runs a server.** Adds infra, billing, abuse mitigation, GDPR
  scope, payment processing, support load.
- **Cost surcharge or subsidy.** Either we mark up Azure pricing (gross
  for users who already have keys) or eat it (gross for JP).
- **Latency.** Extra round-trip through proxy.
- **Privacy regression.** Every chapter's text passes through a
  storyvox-controlled box.

**Verdict:** BYOK now. Revisit proxy later if there's user demand for a
zero-onboarding path AND the support load doesn't outweigh the win.

### Storage: `AzureCredentials`

Mirrors the cookie/PAT pattern exactly:

```kotlin
@Singleton
class AzureCredentials @Inject constructor(
    private val prefs: SharedPreferences,  // EncryptedSharedPreferences instance from DataModule
) {
    fun key(): String? = prefs.getString(KEY_AZURE_KEY, null)
    fun region(): String = prefs.getString(KEY_AZURE_REGION, null) ?: DEFAULT_REGION
    fun setKey(key: String) = prefs.edit { putString(KEY_AZURE_KEY, key) }
    fun setRegion(region: String) = prefs.edit { putString(KEY_AZURE_REGION, region) }
    fun clear() = prefs.edit {
        remove(KEY_AZURE_KEY); remove(KEY_AZURE_REGION)
    }
    val isConfigured: Boolean get() = key() != null
}
```

The encrypted-prefs instance already bound in `DataModule` (the one used
for RR cookies) is reused — no new master key, no new file. The Azure
key is stored under `azure_key`, region under `azure_region`. Same Tink
AES-256-GCM envelope.

## Voice picker integration

The picker's existing structure groups voices by quality tier (Studio →
High → Medium → Low). Azure voices slot into Studio, with a per-engine
section header so users can tell local from cloud at a glance:

```
🎙 Voice Library

  ☁️  Cloud — Azure  [Cost: pay-per-character, see Settings]
      ⭐ Ava (Dragon HD)            en-US  Studio  $30 / 1M chars
      ⭐ Andrew (Dragon HD)          en-US  Studio  $30 / 1M chars
      Aria (HD Neural)               en-US  Studio  $30 / 1M chars
      ...

  💎  Studio
      ⭐ Heart (Kokoro)              en-US  Studio
      Bella (Kokoro)                 en-US  Studio
      ...

  🎵  High / Medium / Low (Piper, Kokoro)
      ...
```

When `AzureCredentials.isConfigured == false`, the Azure section
collapses to a single CTA row: **"Configure Azure key to enable cloud
voices →"** linking to Settings → Sources → Azure. No voices in the
picker the user can't actually use; no broken activation path.

When `AzureCredentials.isConfigured == true` but **no network**,
the section greys out and shows "Offline — voices will use [fallback
voice]" if a fallback is configured, else "Offline — Azure voices
unavailable".

### First-time activation flow

User picks an Azure voice for the first time → cost-disclosure modal
fires (described in **Cost UX** below). Modal has two buttons: "Cancel"
and "Use this voice". On accept, we mark a "user has acknowledged Azure
billing" flag in DataStore so the modal doesn't re-fire on every voice
swap. (Settings → Sources → Azure has a "Reset cost acknowledgement"
button for users who want the warning back.)

## Cost UX

Three surfaces:

### 1. First-time onboarding modal

Triggers once, the first time the user picks an Azure voice from the
picker. Plain language, real numbers:

> **Cloud voices use Azure Speech Services.**
>
> You pay Azure directly — storyvox does not bill you. Pricing as of
> 2026-05-08:
>
> - **HD Neural voices**: $30 per 1 million characters (free tier:
>   500,000 characters/month).
> - **Dragon HD voices**: $30 per 1 million characters.
>
> A typical novel chapter (~7,500 characters) costs roughly **$0.22**.
> A 50-chapter binge: **~$11**.
>
> Your Azure key is stored encrypted on this device only.
>
> [Cancel] [Use this voice]

The numbers come from `VoiceCost` on each Azure catalog entry — single
source of truth, easy to bump on Azure pricing changes. **The above
numbers are pricing-page estimates and need verification against the
2026 GA pricing — flagged as `[needs verification]` in the open
questions.**

### 2. Per-chapter cost hint (optional, opt-in)

Settings → Sources → Azure has a toggle "Show estimated cost per
chapter". When on, the playback sheet shows a small "≈ $0.22" annotation
next to the duration estimate, calculated from `chapter.text.length × cost.centsPer1MChars / 1_000_000`.

Off by default. Some users will find it useful, others will find it
oppressive — opt-in is the right default, and the toggle is one tap.

### 3. Cumulative usage tracking (deferred)

Tracking total chars-billed-this-month would need either local accounting
(unreliable — multiple devices, multiple apps, no source of truth) or
hitting the Azure usage API (auth-heavy, separate scope). **Out of scope
for v1.** Users who want a real cost view check Azure Portal.

## Offline behavior

Azure-voice playback fails cleanly when the device has no network:

1. Active voice = Azure + no network → playback enters a clear error
   state in `PlaybackState.error = AzureNetworkUnavailable`. The
   playback sheet shows "Network required for cloud voices" with a
   "Switch to local voice" affordance.
2. **Optional fallback toggle** in Settings → Sources → Azure → "Fall
   back to local voice when offline":
   - Off (default): the error state above; user manually switches.
   - On: when synthesis fails with a network error, the engine
     transparently swaps to the user-chosen fallback Piper/Kokoro voice
     and a one-shot toast announces "Azure offline — using [fallback
     voice]". On reconnect, **stays on the fallback** until user
     manually flips back. (Auto-flipping back mid-chapter is too
     surprising; the user explicitly asked for the fallback by setting
     the toggle.)

The fallback voice picker is a regular voice-library row selector in
Settings, scoped to installed local voices only.

The picker UI greys out Azure voices when network is unavailable AND
the fallback isn't configured, so the failure can't happen mid-chapter
from a cold start. (Mid-chapter network drops still hit the error path
above — there's no way to detect "about to be offline" preemptively.)

## Error handling

| HTTP status | Meaning | Storyvox response |
|---|---|---|
| 200 | Success | PCM body returned to engine handle. |
| 401 / 403 | Bad / revoked key | Surface `AzureAuthError` in PlaybackState. Picker invalidates the key (`AzureCredentials.clear()`). User re-pastes from Settings. |
| 429 | Throttled | Exponential backoff with jitter (250 ms → 2 s → 8 s, three retries). After three retries fail, surface `AzureThrottled` in PlaybackState. Document Azure F0 limits in Settings help. |
| 5xx | Azure server error | Same backoff as 429; after retries, surface `AzureServerError`. |
| Network failure (DNS, TLS, EOF) | Connectivity | Treat as offline (see **Offline behavior**); fallback path or error state. |

`AzureVoiceEngine.synthesize` is the integration point — it maps HTTP
results to a small `AzureError` sealed type that
`activeVoiceEngineHandle` translates to `null` (engine handle's "this
sentence had no PCM, skip it") plus a side-channel state update on
`EnginePlayer._observableState.error`. The producer's existing
"if pcm == null, continue" branch already handles the skip-and-keep-going
case.

For the auth-error case specifically, the producer also calls
`stopPlaybackPipeline()` because there's no point synthesizing the next
sentence when the key is invalid — every subsequent request will 401.

## Sample-rate matching

Azure's `raw-16khz-16bit-mono-pcm` output gives 16 kHz mono PCM. Piper
voices are 22050 Hz, Kokoro is 24000 Hz. The existing `EnginePlayer`
already rebuilds the AudioTrack on voice swap with the new engine's
sample rate (see `createAudioTrack(sampleRate)`), so 16 kHz fits in
naturally. **No upsample step needed** — AudioTrack handles arbitrary
sample rates within `AudioTrack.getNativeOutputSampleRate` constraints
(8 kHz – 48 kHz).

We could request 24 kHz (`raw-24khz-16bit-mono-pcm`) to match Kokoro.
Azure's HD voices render natively at 24 kHz, so this is essentially free
on the Azure side and gives marginally better fidelity. **Decision:
default to 24 kHz.** The 16 kHz option is the lowest-bandwidth choice
which we don't need given Azure's response is fast on residential WiFi.

## Integration with existing features

### PCM cache (Aurelia, #94)

Azure-generated PCM lands in the same `PcmAppender` as Piper/Kokoro
output. The cache key already includes `voiceId`, which encodes voice
choice → distinct cache files per Azure voice. **Zero changes to the
cache layer.**

A nice property: a chapter rendered once via Azure ($0.22) costs
nothing on replay (cache hit). The PCM cache is the perfect cost-control
mechanism for cloud voices — the per-chapter bill is one-time, not
per-listen. Aurelia's spec already enables this; we just plug in.

### PCM cache eviction nuance

Local engines can re-render at no cost. Azure can't. **Pin Azure-keyed
cache entries above local-keyed ones in the LRU order**, so the eviction
prefers throwing away easily-regeneratable Piper renders before
Azure-rendered ones the user paid for.

This is a one-line change in `PcmCache.evictTo` — sort the eviction
candidates with Azure entries last. The cache layer already knows the
voice id; the engine type is a quick lookup via `VoiceCatalog.byId`.

### Sentence highlighting

Each Azure synth returns a single sentence's PCM, identical in shape to
Piper output. The existing `SentenceTracker` consumes
`SentenceRange` events emitted at `PcmChunk` boundaries. **No change.**

### Voice swap mid-chapter

`EnginePlayer.observeActiveVoice` already handles voice changes by
tearing down the pipeline and calling `loadAndPlay`. For Azure voices,
the "load model" path is a no-op (no model to load — just credential
verification). We add an `EngineType.Azure` branch in `loadAndPlay`
that:

1. Checks `AzureCredentials.isConfigured` — if not, surfaces an error.
2. Optionally pings `voices/list` once to verify the key is good.
   (Could defer to first synth and let 401 handling cover it. Decision:
   verify on activation, fast-fail with a better error message.)
3. Sets `activeEngineType = EngineType.Azure(voiceName, region)` and
   continues to `startPlaybackPipeline()` as today.

The 30-second Kokoro warm-up doesn't apply to Azure — first sentence
synth latency is just one HTTP round-trip.

### MediaSession / Wear / Auto

`EnginePlayer` keeps its `SimpleBasePlayer` API. **No change** to the
exposed `Player.STATE_*` mapping. Wear and Auto see Azure-driven
playback identical to Piper/Kokoro-driven playback because they only
see AudioTrack output and `SimpleBasePlayer` state.

## PR sequence

Each PR independently reviewable, each ships as soon as it's correct.

### PR-1 — `EngineType.Azure` + catalog seam

- Add `EngineType.Azure(voiceName, region)` to the sealed interface.
- Add optional `cost: VoiceCost?` field on `CatalogEntry`.
- Add a static `azureEntries()` returning ~5 placeholder Azure voices
  with `cost = VoiceCost(3000, "Azure")`.
- **No engine code yet.** Picker shows the new section but rows are
  unselectable (greyed out with a "Coming soon" annotation).
- Tests: `EngineType.Azure` equality, catalog entries surface.

This is a refactor PR. Lays groundwork without surfacing user-visible
features yet.

### PR-2 — `:source-azure` module skeleton

- Create the `:source-azure` Gradle module (pure JVM).
- `AzureSpeechClient` with `synthesize(ssml: String): ByteArray`,
  unit-tested against a MockWebServer fake.
- `AzureSsmlBuilder` with text → SSML logic, XML escaping, prosody
  mapping, unit tests.
- `AzureRegion` enum, `AzureCredentials` reading from a
  `SharedPreferences` injected by Hilt.
- `AzureVoiceEngine` thin wrapper that takes credentials + a
  `CatalogEntry.engineType: Azure` and produces PCM bytes.
- **No EnginePlayer wiring yet.** Module is buildable but unused.

### PR-3 — Settings → Sources → Azure UI

- Add an "Azure" entry to the existing "Sources" Settings section
  (which already has GitHub from #86 — same UI shape).
- Key paste field (masked, with a "show key" toggle).
- Region dropdown (eastus, westus2, westeurope, eastasia for v1).
- "Test connection" button → calls `voices/list`, reports success/fail.
- "Forget key" button.
- Documentation link: "How do I get an Azure Speech key?" → opens an
  in-app help screen with a 4-step list (Azure portal → Speech resource
  → keys & endpoint → paste).
- **Still no playback wiring.** Settings is functional; the key persists
  encrypted; voices in the picker remain greyed out.

This PR alone is shippable as a "preparation" release — users can
configure their key ahead of the engine PR landing.

### PR-4 — `EnginePlayer` Azure branch

- `activeVoiceEngineHandle(EngineType.Azure)` returns an
  `AzureVoiceEngine`-backed handle.
- `loadAndPlay` Azure branch: credential check, optional voices/list
  verify, no model load, straight to `startPlaybackPipeline`.
- `EngineStreamingSource` requires zero changes — it already wraps any
  `VoiceEngineHandle`.
- Picker rows ungrey for Azure voices once a key is configured.
- First-time-Azure cost-disclosure modal lands here.
- Tests: Azure synth path with a fake `AzureSpeechClient`; cost-modal
  fires on first Azure activation only.

This is the first PR where users hear an Azure voice. Should land
behind a feature-marker file `${filesDir}/azure-enabled` (empty file
toggle) for the first beta, similar to `audiotrack-builder`. Promote
to default-on once stability holds in dogfood.

### PR-5 — Error handling + retries

- Wire 401 / 429 / 5xx / network into `PlaybackError` types.
- Backoff + jitter retry inside `AzureSpeechClient`.
- Picker greys out Azure voices when offline; Settings shows last-known
  error.
- Tests: simulated 429 → backoff → success; 401 → state surfaces auth
  error.

### PR-6 — Offline fallback toggle

- Settings → Sources → Azure → "Fall back to local voice when offline"
  toggle + voice selector.
- `EnginePlayer.activeVoiceEngineHandle` wraps the Azure handle with a
  `FallbackVoiceEngineHandle` when toggle is on; on Azure synth failure,
  delegates to the chosen local handle.
- Toast emits on first fallback per chapter; doesn't spam.
- Tests: synth-failure → fallback PCM returned; toast emitted once.

### PR-7 — Per-chapter cost hint + PCM cache eviction priority

- Optional cost annotation in playback sheet (Settings toggle).
- `PcmCache.evictTo` sorts Azure-keyed entries after local entries in
  LRU order so paid renders survive eviction longer.
- Voice catalog gets the full ~20-voice Azure roster (Dragon HD set +
  HD Neural en-GB).

### PR-8 (optional, deferred) — proxy mode

If demand emerges. Storyvox-issued tokens, server-side Azure key,
cost surcharge or subsidy. **Out of scope unless JP signs off.**

## Risks

| risk | mitigation |
|---|---|
| **Azure pricing changes.** $30/1M is current estimate; could shift before GA. | `VoiceCost` is data, not code. Update one constant + bump catalog version. Runtime fetch from a JSON endpoint on `raw.githubusercontent.com/jphein/storyvox-registry/...` is a reasonable PR-9 if pricing churn matters. |
| **Free-tier exhaustion mid-chapter.** F0 caps at 500K chars/month; user runs out 30 sentences into a chapter, gets 429. | Backoff retries cover transient throttles; persistent quota exhaustion surfaces as `AzureThrottled` and the user can either swap to local or wait. Document the F0 cap clearly in onboarding. |
| **Key in shared device storage.** EncryptedSharedPreferences is robust but a rooted device or backup-extracted device leaks. | Same threat model as RR cookie. Acceptable. Document. |
| **Cost shock.** User listens to a 50-chapter binge, sees a $30 Azure bill, blames storyvox. | Cost-disclosure modal at first activation; per-chapter hint opt-in; PCM cache makes replay free; Settings shows last-month estimate (deferred). The hard cap option (PR-9?) is the open question for JP below. |
| **Azure regional outage.** eastus down → all storyvox users on that region offline. | Region is user-configurable; user can swap to another region in Settings. We don't auto-failover (auth keys are region-scoped). |
| **Sentence chunking latency on slow networks.** Per-sentence HTTP round-trip on a 1 Mbps connection adds ~500 ms latency per sentence. | OkHttp connection-pool reuse keeps TLS handshake amortized. Sub-sentence streaming (Option B) is the escape hatch if real-world data shows this matters. |
| **Engine-mutex held during HTTPS round-trip.** Voice swap during Azure synth waits up to ~1s for the in-flight request before tearing down. | Acceptable — same shape as the 30s Kokoro load wait. Can relax to per-sentence HTTP with no mutex in PR-9 if it bites. |
| **SSML rejection on weird sentence text.** Unicode boundary cases in chapter text might violate SSML strictness. | XML-escape rigorously; fuzz with the existing chapter corpus. Fall back to plain `<speak>` (no `<voice>` tag) on a 400 from Azure as a recovery path. |

## Out of scope

- **Multi-cloud.** Google Cloud TTS, Amazon Polly, ElevenLabs, OpenAI
  TTS — all viable but each is its own integration. Azure proves the
  shape; the second cloud (whichever it is) is a 1-week PR after this
  lands.
- **Custom voice training.** Azure supports custom-trained voices —
  irrelevant for v1.
- **Voice mixing.** Per-character voice assignment (a la VoxSherpa
  multi-speaker mode) for cloud voices. Defer.
- **SSML pass-through.** Letting fictions specify SSML markup in their
  source. Trust boundary nightmare; defer indefinitely.
- **Cost capping / hard limits.** Auto-pause at $5/month or similar.
  Open question for JP below.
- **Cumulative usage display.** "You've used $4.23 of Azure this month."
  Needs Azure usage API integration; defer.
- **Browser-side OAuth.** "Sign in with Microsoft" → automatic key
  provisioning. Possible but adds substantial UI surface for a feature
  most users won't use.

## Open questions for JP

1. **BYOK vs proxy preference?** The spec recommends BYOK and defers
   proxy. Confirm before PR-1.

2. **Cost UX strictness — soft hint or hard cap?**
   - **Soft (recommended)**: per-chapter cost annotation + onboarding
     modal, no automatic cutoff. User self-regulates.
   - **Hard cap**: a Settings → "Stop Azure synth at $X this month"
     with local accounting. Local accounting is unreliable
     (multi-device, multi-app), so the cap is approximate. Probably
     not worth the implementation cost.
   - **Hybrid**: surface a running total ("≈ $4.20 this month, est.")
     based on local accounting, no enforcement.

   Solara recommends **soft + hybrid total**. Hard cap deferred.

3. **Default Azure region?**
   - eastus is the cheapest and most-feature-complete. Recommend eastus.
   - User-configurable in Settings → Sources → Azure.
   - Should we auto-detect from device locale (en-US → eastus, en-GB →
     westeurope)? Probably overkill; let the user pick.

4. **Free-tier guidance in onboarding modal?**
   - Loud: "Azure F0 free tier gives you 500K chars/month — about 65
     chapters at no cost."
   - Quiet: just mention pricing, let users discover free tier in Azure
     portal.

   Solara recommends **loud** — the free tier is the on-ramp; it
   significantly de-risks the feature for new users.

5. **Voice catalog: hardcoded or fetched?**
   - Hardcoded (~20 voices) — simple, version-pinned, predictable.
   - Fetched from Azure's `voices/list` — full roster, auto-updates as
     Azure adds voices. Adds an API call at first launch.
   - Hybrid: hardcoded curated list (the 5–10 best HD voices) + opt-in
     "Show all available voices" that fetches the full list.

   Solara recommends **hardcoded curated for v1**, hybrid in PR-9 if
   users want more.

6. **Should we ship behind a feature flag (filesDir marker) or
   default-on for the first release?** PR-4 ships the engine wiring
   first; defaulting it on means every new install sees the Azure
   section in Settings whether or not they want it. Marker file matches
   the AudioTrack-builder pattern. Solara recommends **marker file for
   PR-4 first beta, default-on after one release of dogfood**.

7. **Sample rate: 16 kHz or 24 kHz?** 24 kHz matches Kokoro and
   minimizes resampling artifacts; 16 kHz cuts bandwidth ~33%. Solara
   recommends **24 kHz** (Azure renders at 24 kHz natively; 16 kHz is
   downsampled server-side anyway).

## Definition of done (for the spec, not implementation)

- JP has reviewed and either approved or annotated open questions above.
- Recommendation re: BYOK vs proxy is locked in.
- Cost UX strictness decision is locked in.
- The PR sequence is approved as the implementation roadmap; PR-1
  begins on a follow-up branch.
