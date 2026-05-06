package `in`.jphe.storyvox.playback.voice

/**
 * Hardcoded recommended voice catalog.
 *
 * Order matters: recommended Piper voices come first (English starters
 * Aurora's Library UI surfaces as the top tiles), followed by Kokoro
 * speaker selections covering languages Piper's recommended set doesn't
 * already cover.
 *
 * Piper URL pattern (rhasspy/piper-voices on huggingface):
 *   .../resolve/main/{lang}/{lang_country}/{voice}/{quality}/{voice}.onnx
 *   .../resolve/main/{lang}/{lang_country}/{voice}/{quality}/tokens.txt
 *
 * Kokoro speaker IDs come from KokoroVoiceHelper in engine-lib — the
 * speaker selection is just an int passed to the Kokoro engine; the
 * model itself is not bundled (a future task will surface a shared
 * Kokoro install flow if needed). Kokoro entries here are flagged with
 * sizeBytes = 0 because they don't carry per-voice download payload.
 */
object VoiceCatalog {

    /** All voices we surface in the picker. Stable, ordered, hardcoded. */
    val voices: List<CatalogEntry> = listOf(
        // ----- Piper recommendations (download Aurora highlights) -----
        CatalogEntry(
            id = "piper_amy_en_US_low",
            displayName = "Amy (Low)",
            language = "en_US",
            sizeBytes = 30_000_000L,
            qualityLevel = QualityLevel.Low,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/amy/low/en_US-amy-low.onnx",
                tokensUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/amy/low/en_US-amy-low.onnx.json",
            ),
        ),
        CatalogEntry(
            id = "piper_lessac_en_US_medium",
            displayName = "Lessac (Medium)",
            language = "en_US",
            sizeBytes = 64_000_000L,
            qualityLevel = QualityLevel.Medium,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/lessac/medium/en_US-lessac-medium.onnx",
                tokensUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/lessac/medium/en_US-lessac-medium.onnx.json",
            ),
        ),
        CatalogEntry(
            id = "piper_ryan_en_US_high",
            displayName = "Ryan (High)",
            language = "en_US",
            sizeBytes = 110_000_000L,
            qualityLevel = QualityLevel.High,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/ryan/high/en_US-ryan-high.onnx",
                tokensUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/ryan/high/en_US-ryan-high.onnx.json",
            ),
        ),
        CatalogEntry(
            id = "piper_glados_en_US_low",
            displayName = "GLaDOS (Low)",
            language = "en_US",
            sizeBytes = 30_000_000L,
            qualityLevel = QualityLevel.Low,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/glados/low/en_US-glados-low.onnx",
                tokensUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/glados/low/en_US-glados-low.onnx.json",
            ),
        ),
        CatalogEntry(
            id = "piper_huayan_zh_CN_medium",
            displayName = "Huayan (Medium)",
            language = "zh_CN",
            sizeBytes = 64_000_000L,
            qualityLevel = QualityLevel.Medium,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/zh/zh_CN/huayan/medium/zh_CN-huayan-medium.onnx",
                tokensUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/zh/zh_CN/huayan/medium/zh_CN-huayan-medium.onnx.json",
            ),
        ),

        // ----- Kokoro speaker IDs (no download — speaker index into shared model) -----
        // English American — Heart (warm female default), Adam (male)
        CatalogEntry(
            id = "kokoro_heart_en_US",
            displayName = "Heart (Kokoro, en-US)",
            language = "en_US",
            sizeBytes = 0L,
            qualityLevel = QualityLevel.High,
            engineType = EngineType.Kokoro(speakerId = 3),
            piper = null,
        ),
        CatalogEntry(
            id = "kokoro_adam_en_US",
            displayName = "Adam (Kokoro, en-US)",
            language = "en_US",
            sizeBytes = 0L,
            qualityLevel = QualityLevel.High,
            engineType = EngineType.Kokoro(speakerId = 11),
            piper = null,
        ),
        // English British — Emma
        CatalogEntry(
            id = "kokoro_emma_en_GB",
            displayName = "Emma (Kokoro, en-GB)",
            language = "en_GB",
            sizeBytes = 0L,
            qualityLevel = QualityLevel.High,
            engineType = EngineType.Kokoro(speakerId = 21),
            piper = null,
        ),
        // Japanese — Alpha
        CatalogEntry(
            id = "kokoro_alpha_ja_JP",
            displayName = "Alpha (Kokoro, ja-JP)",
            language = "ja_JP",
            sizeBytes = 0L,
            qualityLevel = QualityLevel.High,
            engineType = EngineType.Kokoro(speakerId = 37),
            piper = null,
        ),
        // Korean — KokoroVoiceHelper has no Korean speakers; substitute Mandarin Yunjian (male)
        // Mandarin Chinese — Xiaoxiao
        CatalogEntry(
            id = "kokoro_xiaoxiao_zh_CN",
            displayName = "Xiaoxiao (Kokoro, zh-CN)",
            language = "zh_CN",
            sizeBytes = 0L,
            qualityLevel = QualityLevel.High,
            engineType = EngineType.Kokoro(speakerId = 47),
            piper = null,
        ),
        CatalogEntry(
            id = "kokoro_yunjian_zh_CN",
            displayName = "Yunjian (Kokoro, zh-CN)",
            language = "zh_CN",
            sizeBytes = 0L,
            qualityLevel = QualityLevel.High,
            engineType = EngineType.Kokoro(speakerId = 49),
            piper = null,
        ),
    )

    fun byId(id: String): CatalogEntry? = voices.firstOrNull { it.id == id }
}

/**
 * Catalog entry — adds provisioning fields ([piper] URLs) on top of the
 * UI-facing [UiVoiceInfo] shape. [VoiceManager] projects this into
 * [UiVoiceInfo] for callers, hiding the URLs from the UI layer.
 */
data class CatalogEntry(
    val id: String,
    val displayName: String,
    val language: String,
    val sizeBytes: Long,
    val qualityLevel: QualityLevel,
    val engineType: EngineType,
    /** Non-null only for Piper entries. */
    val piper: PiperPaths?,
)

data class PiperPaths(val onnxUrl: String, val tokensUrl: String)
