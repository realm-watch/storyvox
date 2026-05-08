package `in`.jphe.storyvox.data.repository.playback

import kotlinx.coroutines.flow.Flow

/**
 * Issue #85 — Voice-determinism preset for the VoxSherpa engine.
 *
 * Sibling of [PlaybackBufferConfig] / [PlaybackModeConfig]: surfaced as its
 * own contract so `core-playback` (which owns the only consumer of the
 * flow — the singleton VoxSherpa `VoiceEngine`) can stay free of
 * feature-layer types. The implementation lives in `:app`'s
 * `SettingsRepositoryUiImpl`, which already implements the other two
 * playback configs; one DataStore, three contracts.
 *
 * **Steady (default, [voiceSteady] = true).** VoxSherpa's calmed VITS
 * defaults — `noise_scale = 0.35`, `noise_scale_w = 0.667`. Identical text
 * re-renders sound nearly identical between runs. Best for audiobook
 * listeners replaying chapters.
 *
 * **Expressive ([voiceSteady] = false).** sherpa-onnx upstream's Piper
 * defaults — `noise_scale = 0.667`, `noise_scale_w = 0.8`. Slightly more
 * variable prosody, fuller delivery, take-to-take variation. Closer to
 * vanilla Piper for users who find Steady's output too dry.
 *
 * The flow flips force a model reload on the active engine (~1-3s on
 * Piper, ~30s on Kokoro — though Kokoro ignores noise_scale and the
 * setter is a cheap no-op there). EnginePlayer subscribes and applies
 * via `VoiceEngine.setNoiseScale*()`; the setter destroys + reconstructs
 * `OfflineTts` only if the new value differs from the active config, so
 * idempotent re-emits don't trigger spurious reloads.
 */
interface VoiceTuningConfig {

    /** Live flow of the Voice-Determinism preset. Default `true` (Steady). */
    val voiceSteady: Flow<Boolean>

    /**
     * Snapshot read for callers that need a single value without subscribing.
     * Implementations should return the most recent value, falling back to
     * the documented default (`true`) if the underlying store hasn't
     * emitted yet.
     */
    suspend fun currentVoiceSteady(): Boolean
}

/**
 * VoxSherpa's calmed VITS noise_scale (matches `VoiceEngine.DEFAULT_NOISE_SCALE`
 * upstream). Used when [VoiceTuningConfig.voiceSteady] is `true`.
 */
const val NOISE_SCALE_STEADY: Float = 0.35f

/** VoxSherpa's calmed VITS noise_scale_w. Used when Steady. */
const val NOISE_SCALE_W_STEADY: Float = 0.667f

/** sherpa-onnx upstream Piper default. Used when Expressive. */
const val NOISE_SCALE_EXPRESSIVE: Float = 0.667f

/** sherpa-onnx upstream Piper default. Used when Expressive. */
const val NOISE_SCALE_W_EXPRESSIVE: Float = 0.8f
