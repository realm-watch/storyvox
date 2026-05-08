package `in`.jphe.storyvox.playback.voice

/**
 * UI-facing description of a voice the user can choose.
 *
 * One row per item in [VoiceCatalog]. The same shape covers both
 * Piper (per-voice .onnx + tokens.txt downloaded from huggingface) and
 * Kokoro (a single shared model bundled by the user that exposes 53
 * speaker IDs). The [engineType] discriminator tells the playback layer
 * which path to take when the user picks this voice.
 *
 * [sizeBytes] is the on-disk install size (catalog estimate for Piper;
 * 0 for Kokoro since the speaker selection is just an integer index into
 * the shared Kokoro model — no per-speaker download).
 */
data class UiVoiceInfo(
    val id: String,
    val displayName: String,
    val language: String,
    val sizeBytes: Long,
    val isInstalled: Boolean,
    val qualityLevel: QualityLevel,
    val engineType: EngineType,
)

/**
 * Voice quality tiers shown in the picker. Order in this enum is
 * **ascending**, but the UI sorts **descending** (Studio first, Low last)
 * so users see the best voices at the top.
 *
 * - **Piper** voices encode tier in their model filename suffix
 *   (`*-low`, `*-medium`, `*-high`). [Studio] never applies to Piper —
 *   it's reserved for the highest-graded Kokoro speakers.
 * - **Kokoro** voices share one model with no per-voice quality
 *   variants; tier is therefore a curated property keyed on voice id
 *   (see [VoiceCatalog.kokoroEntries]). The [Studio] picks reflect
 *   upstream `hexgrad/Kokoro-82M` voice grades — the handful of
 *   speakers that reliably produce broadcast-grade audio without the
 *   small artifacts that creep into the lower-graded speakers.
 */
enum class QualityLevel { Low, Medium, High, Studio }

sealed interface EngineType {
    /** Piper voice — per-voice .onnx downloaded from rhasspy/piper-voices. */
    data object Piper : EngineType

    /** Kokoro speaker — shared Kokoro model, picks a speaker by index. */
    data class Kokoro(val speakerId: Int) : EngineType
}
