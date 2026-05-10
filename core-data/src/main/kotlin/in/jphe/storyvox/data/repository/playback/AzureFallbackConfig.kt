package `in`.jphe.storyvox.data.repository.playback

import kotlinx.coroutines.flow.Flow

/**
 * PR-6 (#185) — Azure offline-fallback config snapshot. When Azure
 * synthesis fails with a non-auth error (network out, Azure 5xx after
 * retries, throttled-after-retries), `EnginePlayer` consults this
 * config to decide whether to auto-swap to a local voice.
 *
 * Surfaced as its own contract (mirroring [PlaybackBufferConfig]) so
 * `core-playback` can read user UX prefs without taking a dep on the
 * feature-layer `SettingsRepositoryUi`. SettingsRepositoryUiImpl in
 * `:app` implements both this and `PlaybackBufferConfig` against the
 * same DataStore.
 *
 * Default state when the user hasn't opted in: [enabled] = false,
 * [fallbackVoiceId] = null, no swap fires.
 */
interface AzureFallbackConfig {
    /** Hot flow of (enabled, voiceId). Re-emits on every setter call. */
    val state: Flow<AzureFallbackState>

    /** Snapshot read at error-time; cheaper than awaiting a flow tick
     *  inside the AzureVoiceEngine.lastError observer. Named
     *  [currentAzureFallback] (not bare `current`) to avoid an
     *  override collision with [PronunciationDictRepository.current],
     *  which `SettingsRepositoryUiImpl` also implements with a
     *  different return type. */
    suspend fun currentAzureFallback(): AzureFallbackState
}

/**
 * @param enabled true if the user wants storyvox to swap to a local
 *                voice when Azure goes offline.
 * @param fallbackVoiceId voice id picked in Settings → Cloud voices →
 *                        "Fall back to local voice." Null until the
 *                        user picks; if null while [enabled] is true,
 *                        fallback is a no-op (no voice to swap to).
 */
data class AzureFallbackState(
    val enabled: Boolean = false,
    val fallbackVoiceId: String? = null,
)
