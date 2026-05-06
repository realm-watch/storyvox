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

enum class QualityLevel { Low, Medium, High }

sealed interface EngineType {
    /** Piper voice — per-voice .onnx downloaded from rhasspy/piper-voices. */
    data object Piper : EngineType

    /** Kokoro speaker — shared Kokoro model, picks a speaker by index. */
    data class Kokoro(val speakerId: Int) : EngineType
}
