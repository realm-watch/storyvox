package `in`.jphe.storyvox.playback.voice

object VoiceCatalog {
    val voices: List<CatalogEntry> = piperEntries() + kokoroEntries() + azureEntries()
    fun byId(id: String): CatalogEntry? = voices.firstOrNull { it.id == id }

    /** The three voices we hand-picked as the strongest starters. Surfaced
     *  on the first-launch picker AND highlighted in the Voice Library
     *  under a "Featured" section so newcomers don't have to scroll the
     *  98-voice catalog hunting for the good ones.
     *
     *  Curated per JP's call (issue #10): Cori for en_GB Piper, Lessac
     *  for en_US Piper, Aoede for the multi-speaker Kokoro path. Three
     *  distinct engines and accents so first-time users hear the range. */
    val featuredIds: List<String> = listOf(
        "piper_cori_en_GB_high",
        "piper_lessac_en_US_high",
        "kokoro_aoede_en_US_1",
    )
    private fun piperEntries(): List<CatalogEntry> = listOf(
        CatalogEntry(
            id = "piper_lessac_en_US_high",
            displayName = "⭐ Lessac (High)",
            language = "en_US",
            sizeBytes = 113895332L,
            qualityLevel = QualityLevel.High,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-lessac-high.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-lessac-high.tokens.txt",
            ),
        ),
        CatalogEntry(
            id = "piper_ryan_en_US_high",
            displayName = "⭐ Ryan (High)",
            language = "en_US",
            sizeBytes = 120786923L,
            qualityLevel = QualityLevel.High,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-ryan-high.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-ryan-high.tokens.txt",
            ),
        ),
        CatalogEntry(
            id = "piper_amy_en_US_medium",
            displayName = "⭐ Amy (Medium)",
            language = "en_US",
            sizeBytes = 63201425L,
            qualityLevel = QualityLevel.Medium,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-amy-medium.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-amy-medium.tokens.txt",
            ),
        ),
        CatalogEntry(
            id = "piper_alan_en_GB_low",
            displayName = "Alan (Low)",
            language = "en_GB",
            sizeBytes = 63104662L,
            qualityLevel = QualityLevel.Low,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-alan-low.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-alan-low.tokens.txt",
            ),
        ),
        CatalogEntry(
            id = "piper_alan_en_GB_medium",
            displayName = "Alan (Medium)",
            language = "en_GB",
            sizeBytes = 63201430L,
            qualityLevel = QualityLevel.Medium,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-alan-medium.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-alan-medium.tokens.txt",
            ),
        ),
        CatalogEntry(
            id = "piper_alba_en_GB_medium",
            displayName = "Alba (Medium)",
            language = "en_GB",
            sizeBytes = 63201430L,
            qualityLevel = QualityLevel.Medium,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-alba-medium.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-alba-medium.tokens.txt",
            ),
        ),
        CatalogEntry(
            id = "piper_aru_en_GB_medium",
            displayName = "Aru (Medium)",
            language = "en_GB",
            sizeBytes = 76754234L,
            qualityLevel = QualityLevel.Medium,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-aru-medium.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-aru-medium.tokens.txt",
            ),
        ),
        CatalogEntry(
            id = "piper_cori_en_GB_medium",
            displayName = "Cori (Medium)",
            language = "en_GB",
            sizeBytes = 63531507L,
            qualityLevel = QualityLevel.Medium,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-cori-medium.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-cori-medium.tokens.txt",
            ),
        ),
        CatalogEntry(
            id = "piper_cori_en_GB_high",
            displayName = "Cori (High)",
            language = "en_GB",
            sizeBytes = 114219480L,
            qualityLevel = QualityLevel.High,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-cori-high.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-cori-high.tokens.txt",
            ),
        ),
        CatalogEntry(
            id = "piper_dii_en_GB_high",
            displayName = "Dii (High)",
            language = "en_GB",
            sizeBytes = 63511174L,
            qualityLevel = QualityLevel.High,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-dii-high.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-dii-high.tokens.txt",
            ),
        ),
        CatalogEntry(
            id = "piper_jenny_dioco_en_GB_medium",
            displayName = "Jenny Dioco (Medium)",
            language = "en_GB",
            sizeBytes = 63201430L,
            qualityLevel = QualityLevel.Medium,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-jenny_dioco-medium.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-jenny_dioco-medium.tokens.txt",
            ),
        ),
        CatalogEntry(
            id = "piper_miro_en_GB_high",
            displayName = "Miro (High)",
            language = "en_GB",
            sizeBytes = 63511174L,
            qualityLevel = QualityLevel.High,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-miro-high.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-miro-high.tokens.txt",
            ),
        ),
        CatalogEntry(
            id = "piper_northern_english_male_en_GB_medium",
            displayName = "Northern English Male (Medium)",
            language = "en_GB",
            sizeBytes = 63201430L,
            qualityLevel = QualityLevel.Medium,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-northern_english_male-medium.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-northern_english_male-medium.tokens.txt",
            ),
        ),
        CatalogEntry(
            id = "piper_semaine_en_GB_medium",
            displayName = "Semaine (Medium)",
            language = "en_GB",
            sizeBytes = 76737847L,
            qualityLevel = QualityLevel.Medium,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-semaine-medium.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-semaine-medium.tokens.txt",
            ),
        ),
        CatalogEntry(
            id = "piper_southern_english_female_en_GB_low",
            displayName = "Southern English Female (Low)",
            language = "en_GB",
            sizeBytes = 63104662L,
            qualityLevel = QualityLevel.Low,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-southern_english_female-low.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-southern_english_female-low.tokens.txt",
            ),
        ),
        CatalogEntry(
            id = "piper_southern_english_female_en_GB_medium",
            displayName = "Southern English Female (Medium)",
            language = "en_GB",
            sizeBytes = 77059414L,
            qualityLevel = QualityLevel.Medium,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-southern_english_female-medium.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-southern_english_female-medium.tokens.txt",
            ),
        ),
        CatalogEntry(
            id = "piper_southern_english_male_en_GB_medium",
            displayName = "Southern English Male (Medium)",
            language = "en_GB",
            sizeBytes = 77063512L,
            qualityLevel = QualityLevel.Medium,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-southern_english_male-medium.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-southern_english_male-medium.tokens.txt",
            ),
        ),
        CatalogEntry(
            id = "piper_sweetbbak_amy_en_GB_high",
            displayName = "Sweetbbak Amy (High)",
            language = "en_GB",
            sizeBytes = 114199142L,
            qualityLevel = QualityLevel.High,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-sweetbbak-amy.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-sweetbbak-amy.tokens.txt",
            ),
        ),
        CatalogEntry(
            id = "piper_vctk_en_GB_medium",
            displayName = "Vctk (Medium)",
            language = "en_GB",
            sizeBytes = 76952891L,
            qualityLevel = QualityLevel.Medium,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-vctk-medium.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-vctk-medium.tokens.txt",
            ),
        ),
        CatalogEntry(
            id = "piper_amy_en_US_low",
            displayName = "Amy (Low)",
            language = "en_US",
            sizeBytes = 63104657L,
            qualityLevel = QualityLevel.Low,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-amy-low.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-amy-low.tokens.txt",
            ),
        ),
        CatalogEntry(
            id = "piper_arctic_en_US_medium",
            displayName = "Arctic (Medium)",
            language = "en_US",
            sizeBytes = 76715309L,
            qualityLevel = QualityLevel.Medium,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-arctic-medium.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-arctic-medium.tokens.txt",
            ),
        ),
        CatalogEntry(
            id = "piper_bryce_en_US_medium",
            displayName = "Bryce (Medium)",
            language = "en_US",
            sizeBytes = 63152970L,
            qualityLevel = QualityLevel.Medium,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-bryce-medium.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-bryce-medium.tokens.txt",
            ),
        ),
        CatalogEntry(
            id = "piper_danny_en_US_low",
            displayName = "Danny (Low)",
            language = "en_US",
            sizeBytes = 63052430L,
            qualityLevel = QualityLevel.Low,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-danny-low.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-danny-low.tokens.txt",
            ),
        ),
        CatalogEntry(
            id = "piper_glados_en_US_high",
            displayName = "GLaDOS (High)",
            language = "en_US",
            sizeBytes = 113800584L,
            qualityLevel = QualityLevel.High,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-glados-high.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-glados-high.tokens.txt",
            ),
        ),
        CatalogEntry(
            id = "piper_glados_en_US_medium",
            displayName = "GLaDOS (Medium)",
            language = "en_US",
            sizeBytes = 63511169L,
            qualityLevel = QualityLevel.Medium,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-glados.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-glados.tokens.txt",
            ),
        ),
        CatalogEntry(
            id = "piper_hfc_female_en_US_medium",
            displayName = "Hfc Female (Medium)",
            language = "en_US",
            sizeBytes = 63149198L,
            qualityLevel = QualityLevel.Medium,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-hfc_female-medium.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-hfc_female-medium.tokens.txt",
            ),
        ),
        CatalogEntry(
            id = "piper_hfc_male_en_US_medium",
            displayName = "Hfc Male (Medium)",
            language = "en_US",
            sizeBytes = 63149198L,
            qualityLevel = QualityLevel.Medium,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-hfc_male-medium.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-hfc_male-medium.tokens.txt",
            ),
        ),
        CatalogEntry(
            id = "piper_joe_en_US_medium",
            displayName = "Joe (Medium)",
            language = "en_US",
            sizeBytes = 63149198L,
            qualityLevel = QualityLevel.Medium,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-joe-medium.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-joe-medium.tokens.txt",
            ),
        ),
        CatalogEntry(
            id = "piper_john_en_US_medium",
            displayName = "John (Medium)",
            language = "en_US",
            sizeBytes = 63152970L,
            qualityLevel = QualityLevel.Medium,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-john-medium.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-john-medium.tokens.txt",
            ),
        ),
        CatalogEntry(
            id = "piper_kathleen_en_US_low",
            displayName = "Kathleen (Low)",
            language = "en_US",
            sizeBytes = 63052430L,
            qualityLevel = QualityLevel.Low,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-kathleen-low.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-kathleen-low.tokens.txt",
            ),
        ),
        CatalogEntry(
            id = "piper_kristin_en_US_medium",
            displayName = "Kristin (Medium)",
            language = "en_US",
            sizeBytes = 63531507L,
            qualityLevel = QualityLevel.Medium,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-kristin-medium.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-kristin-medium.tokens.txt",
            ),
        ),
        CatalogEntry(
            id = "piper_kusal_en_US_medium",
            displayName = "Kusal (Medium)",
            language = "en_US",
            sizeBytes = 63201425L,
            qualityLevel = QualityLevel.Medium,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-kusal-medium.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-kusal-medium.tokens.txt",
            ),
        ),
        CatalogEntry(
            id = "piper_l2arctic_en_US_medium",
            displayName = "L2Arctic (Medium)",
            language = "en_US",
            sizeBytes = 76778805L,
            qualityLevel = QualityLevel.Medium,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-l2arctic-medium.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-l2arctic-medium.tokens.txt",
            ),
        ),
        CatalogEntry(
            id = "piper_lessac_en_US_low",
            displayName = "Lessac (Low)",
            language = "en_US",
            sizeBytes = 63149198L,
            qualityLevel = QualityLevel.Low,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-lessac-low.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-lessac-low.tokens.txt",
            ),
        ),
        CatalogEntry(
            id = "piper_lessac_en_US_medium",
            displayName = "Lessac (Medium)",
            language = "en_US",
            sizeBytes = 63149198L,
            qualityLevel = QualityLevel.Medium,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-lessac-medium.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-lessac-medium.tokens.txt",
            ),
        ),
        CatalogEntry(
            id = "piper_libritts_en_US_high",
            displayName = "Libritts (High)",
            language = "en_US",
            sizeBytes = 129438513L,
            qualityLevel = QualityLevel.High,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-libritts-high.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-libritts-high.tokens.txt",
            ),
        ),
        CatalogEntry(
            id = "piper_libritts_r_en_US_medium",
            displayName = "Libritts R (Medium)",
            language = "en_US",
            sizeBytes = 78529840L,
            qualityLevel = QualityLevel.Medium,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-libritts_r-medium.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-libritts_r-medium.tokens.txt",
            ),
        ),
        CatalogEntry(
            id = "piper_ljspeech_en_US_medium",
            displayName = "Ljspeech (Medium)",
            language = "en_US",
            sizeBytes = 63531507L,
            qualityLevel = QualityLevel.Medium,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-ljspeech-medium.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-ljspeech-medium.tokens.txt",
            ),
        ),
        CatalogEntry(
            id = "piper_ljspeech_en_US_high",
            displayName = "Ljspeech (High)",
            language = "en_US",
            sizeBytes = 114199139L,
            qualityLevel = QualityLevel.High,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-ljspeech-high.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-ljspeech-high.tokens.txt",
            ),
        ),
        CatalogEntry(
            id = "piper_miro_en_US_high",
            displayName = "Miro (High)",
            language = "en_US",
            sizeBytes = 63511169L,
            qualityLevel = QualityLevel.High,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-miro-high.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-miro-high.tokens.txt",
            ),
        ),
        CatalogEntry(
            id = "piper_norman_en_US_medium",
            displayName = "Norman (Medium)",
            language = "en_US",
            sizeBytes = 63531507L,
            qualityLevel = QualityLevel.Medium,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-norman-medium.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-norman-medium.tokens.txt",
            ),
        ),
        CatalogEntry(
            id = "piper_reza_ibrahim_en_US_medium",
            displayName = "Reza Ibrahim (Medium)",
            language = "en_US",
            sizeBytes = 63511169L,
            qualityLevel = QualityLevel.Medium,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-reza_ibrahim-medium.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-reza_ibrahim-medium.tokens.txt",
            ),
        ),
        CatalogEntry(
            id = "piper_ryan_en_US_low",
            displayName = "Ryan (Low)",
            language = "en_US",
            sizeBytes = 63052430L,
            qualityLevel = QualityLevel.Low,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-ryan-low.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-ryan-low.tokens.txt",
            ),
        ),
        CatalogEntry(
            id = "piper_ryan_en_US_medium",
            displayName = "Ryan (Medium)",
            language = "en_US",
            sizeBytes = 63149198L,
            qualityLevel = QualityLevel.Medium,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-ryan-medium.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-ryan-medium.tokens.txt",
            ),
        ),
        CatalogEntry(
            id = "piper_sam_en_US_medium",
            displayName = "Sam (Medium)",
            language = "en_US",
            sizeBytes = 62946438L,
            qualityLevel = QualityLevel.Medium,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-sam-medium.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-sam-medium.tokens.txt",
            ),
        ),
    )

    /** Kokoro entries — 53 speakers all sharing one bundled model.
     *  Quality tier is **derived** from voice id via [VoiceTier.forKokoroId]
     *  rather than hardcoded inline so the rule lives in one place and
     *  stays unit-testable. The vast majority resolve to [QualityLevel.High];
     *  a curated few (see [VoiceTier.STUDIO_KOKORO_IDS]) get bumped to
     *  [QualityLevel.Studio]. */
    private fun kokoroEntries(): List<CatalogEntry> {
        fun kokoro(id: String, displayName: String, language: String, speakerId: Int): CatalogEntry =
            CatalogEntry(
                id = id,
                displayName = displayName,
                language = language,
                sizeBytes = 0L,
                qualityLevel = VoiceTier.forKokoroId(id),
                engineType = EngineType.Kokoro(speakerId = speakerId),
                piper = null,
            )
        return listOf(
            kokoro("kokoro_alloy_en_US_0", "🇺🇸 Alloy (English Female)", "en_US", 0),
            kokoro("kokoro_aoede_en_US_1", "🇺🇸 Aoede (English Female)", "en_US", 1),
            kokoro("kokoro_bella_en_US_2", "🇺🇸 Bella (English Female)", "en_US", 2),
            kokoro("kokoro_heart_en_US_3", "🇺🇸 Heart (English Female)", "en_US", 3),
            kokoro("kokoro_jessica_en_US_4", "🇺🇸 Jessica (English Female)", "en_US", 4),
            kokoro("kokoro_kore_en_US_5", "🇺🇸 Kore (English Female)", "en_US", 5),
            kokoro("kokoro_nicole_en_US_6", "🇺🇸 Nicole (English Female)", "en_US", 6),
            kokoro("kokoro_nova_en_US_7", "🇺🇸 Nova (English Female)", "en_US", 7),
            kokoro("kokoro_river_en_US_8", "🇺🇸 River (English Female)", "en_US", 8),
            kokoro("kokoro_sarah_en_US_9", "🇺🇸 Sarah (English Female)", "en_US", 9),
            kokoro("kokoro_sky_en_US_10", "🇺🇸 Sky (English Female)", "en_US", 10),
            kokoro("kokoro_adam_en_US_11", "🇺🇸 Adam (English Male)", "en_US", 11),
            kokoro("kokoro_echo_en_US_12", "🇺🇸 Echo (English Male)", "en_US", 12),
            kokoro("kokoro_eric_en_US_13", "🇺🇸 Eric (English Male)", "en_US", 13),
            kokoro("kokoro_fenrir_en_US_14", "🇺🇸 Fenrir (English Male)", "en_US", 14),
            kokoro("kokoro_liam_en_US_15", "🇺🇸 Liam (English Male)", "en_US", 15),
            kokoro("kokoro_michael_en_US_16", "🇺🇸 Michael (English Male)", "en_US", 16),
            kokoro("kokoro_onyx_en_US_17", "🇺🇸 Onyx (English Male)", "en_US", 17),
            kokoro("kokoro_puck_en_US_18", "🇺🇸 Puck (English Male)", "en_US", 18),
            kokoro("kokoro_santa_en_US_19", "🇺🇸 Santa (English Male)", "en_US", 19),
            kokoro("kokoro_alice_en_GB_20", "🇬🇧 Alice (English Female)", "en_GB", 20),
            kokoro("kokoro_emma_en_GB_21", "🇬🇧 Emma (English Female)", "en_GB", 21),
            kokoro("kokoro_isabella_en_GB_22", "🇬🇧 Isabella (English Female)", "en_GB", 22),
            kokoro("kokoro_lily_en_GB_23", "🇬🇧 Lily (English Female)", "en_GB", 23),
            kokoro("kokoro_daniel_en_GB_24", "🇬🇧 Daniel (English Male)", "en_GB", 24),
            kokoro("kokoro_fable_en_GB_25", "🇬🇧 Fable (English Male)", "en_GB", 25),
            kokoro("kokoro_george_en_GB_26", "🇬🇧 George (English Male)", "en_GB", 26),
            kokoro("kokoro_lewis_en_GB_27", "🇬🇧 Lewis (English Male)", "en_GB", 27),
            kokoro("kokoro_dora_es_ES_28", "🇪🇸 Dora (Spanish Female)", "es_ES", 28),
            kokoro("kokoro_alex_es_ES_29", "🇪🇸 Alex (Spanish Male)", "es_ES", 29),
            kokoro("kokoro_siwis_fr_FR_30", "🇫🇷 Siwis (French Female)", "fr_FR", 30),
            kokoro("kokoro_alpha_hi_IN_31", "🇮🇳 Alpha (Hindi Female)", "hi_IN", 31),
            kokoro("kokoro_beta_hi_IN_32", "🇮🇳 Beta (Hindi Female)", "hi_IN", 32),
            kokoro("kokoro_omega_hi_IN_33", "🇮🇳 Omega (Hindi Male)", "hi_IN", 33),
            kokoro("kokoro_psi_hi_IN_34", "🇮🇳 Psi (Hindi Male)", "hi_IN", 34),
            kokoro("kokoro_sara_it_IT_35", "🇮🇹 Sara (Italian Female)", "it_IT", 35),
            kokoro("kokoro_nicola_it_IT_36", "🇮🇹 Nicola (Italian Male)", "it_IT", 36),
            kokoro("kokoro_alpha_ja_JP_37", "🇯🇵 Alpha (Japanese Female)", "ja_JP", 37),
            kokoro("kokoro_gongitsune_ja_JP_38", "🇯🇵 Gongitsune (Japanese Female)", "ja_JP", 38),
            kokoro("kokoro_nezumi_ja_JP_39", "🇯🇵 Nezumi (Japanese Female)", "ja_JP", 39),
            kokoro("kokoro_tebukuro_ja_JP_40", "🇯🇵 Tebukuro (Japanese Female)", "ja_JP", 40),
            kokoro("kokoro_kumo_ja_JP_41", "🇯🇵 Kumo (Japanese Male)", "ja_JP", 41),
            kokoro("kokoro_dora_pt_PT_42", "🇵🇹 Dora (Portuguese Female)", "pt_PT", 42),
            kokoro("kokoro_alex_pt_PT_43", "🇵🇹 Alex (Portuguese Male)", "pt_PT", 43),
            kokoro("kokoro_santa_pt_PT_44", "🇵🇹 Santa (Portuguese Male)", "pt_PT", 44),
            kokoro("kokoro_xiaobei_zh_CN_45", "🇨🇳 Xiaobei (Chinese Female)", "zh_CN", 45),
            kokoro("kokoro_xiaoni_zh_CN_46", "🇨🇳 Xiaoni (Chinese Female)", "zh_CN", 46),
            kokoro("kokoro_xiaoxiao_zh_CN_47", "🇨🇳 Xiaoxiao (Chinese Female)", "zh_CN", 47),
            kokoro("kokoro_xiaoyi_zh_CN_48", "🇨🇳 Xiaoyi (Chinese Female)", "zh_CN", 48),
            kokoro("kokoro_yunjian_zh_CN_49", "🇨🇳 Yunjian (Chinese Male)", "zh_CN", 49),
            kokoro("kokoro_yunxi_zh_CN_50", "🇨🇳 Yunxi (Chinese Male)", "zh_CN", 50),
            kokoro("kokoro_yunxia_zh_CN_51", "🇨🇳 Yunxia (Chinese Male)", "zh_CN", 51),
            kokoro("kokoro_yunyang_zh_CN_52", "🇨🇳 Yunyang (Chinese Male)", "zh_CN", 52),
        )
    }

    /**
     * Azure Speech Services HD voices — cloud-rendered TTS over HTTPS,
     * BYOK (user pastes their Azure resource key into Settings →
     * Sources → Azure). [Solara's spec](docs/superpowers/specs/2026-05-08-azure-hd-voices-design.md)
     * recommends a hardcoded curated list for v1; the full ~400-voice
     * Azure roster is fetchable via `voices/list` but most users only
     * want the obvious picks.
     *
     * Curated set: Dragon HD voices (the 2025 generative tier — best
     * quality Azure offers) + a handful of HD Neural multilingual
     * voices for accent variety. en-US dominates because that's the
     * primary user locale; en-GB Sonia covers British English.
     *
     * `sizeBytes = 0` because there's no per-voice download — the model
     * lives on Azure's side.
     *
     * `region = "eastus"` is the default region. Per Solara's open
     * question #3 the user can change it in Settings; the catalog
     * entry's region is the activation default. The actual region
     * used at runtime is read from `AzureCredentials.region()`, not
     * from this catalog field — the catalog default just seeds the
     * `EngineType.Azure` discriminator with a non-empty region for
     * code paths that key on it before the user opens Settings.
     *
     * Pricing: $30/1M chars (3000¢) for both Dragon HD and HD Neural
     * voices. Azure's F0 free tier covers 500K chars/month for HD.
     * Pricing-page estimate as of 2026-05-08; flagged for verification
     * at GA. If pricing churns, edit one constant — the picker chip,
     * the cost-disclosure modal, and the per-chapter hint all read
     * from `cost`.
     *
     * **PR-1 (this PR) ships the catalog entries.** The picker
     * surfaces them in a "Cloud — Azure" section but rows are
     * unselectable until PR-4 lands the engine wiring (Solara's plan).
     * That keeps the layout reviewable in isolation without
     * exercising the cloud round-trip path.
     */
    private fun azureEntries(): List<CatalogEntry> {
        val cost = VoiceCost(centsPer1MChars = 3000, billedBy = "Azure")
        val defaultRegion = "eastus"
        fun azure(id: String, displayName: String, language: String, voiceName: String) =
            CatalogEntry(
                id = id,
                displayName = displayName,
                language = language,
                sizeBytes = 0L,
                qualityLevel = QualityLevel.Studio,
                engineType = EngineType.Azure(voiceName, defaultRegion),
                piper = null,
                cost = cost,
            )
        return listOf(
            // Dragon HD — Azure's 2025 generative tier. Highest quality.
            azure(
                "azure_ava_en_US_dragon_hd",
                "☁️ Ava (Dragon HD)",
                "en_US",
                "en-US-AvaDragonHDLatestNeural",
            ),
            azure(
                "azure_andrew_en_US_dragon_hd",
                "☁️ Andrew (Dragon HD)",
                "en_US",
                "en-US-AndrewDragonHDLatestNeural",
            ),
            // HD Neural multilingual — broad accent + cross-language coverage.
            azure(
                "azure_aria_en_US_hd",
                "☁️ Aria (HD Neural)",
                "en_US",
                "en-US-AriaNeural",
            ),
            azure(
                "azure_jenny_en_US_hd",
                "☁️ Jenny (HD Neural)",
                "en_US",
                "en-US-JennyMultilingualNeural",
            ),
            azure(
                "azure_sonia_en_GB_hd",
                "☁️ Sonia (HD Neural, British)",
                "en_GB",
                "en-GB-SoniaNeural",
            ),
        )
    }
}

data class CatalogEntry(
    val id: String,
    val displayName: String,
    val language: String,
    val sizeBytes: Long,
    val qualityLevel: QualityLevel,
    val engineType: EngineType,
    val piper: PiperPaths?,
    /**
     * Per-voice billing rate for cloud engines. `null` for local
     * engines (Piper, Kokoro) — no per-character cost.
     *
     * Surfaces in the picker as a small annotation chip beneath the
     * display name (e.g. "$30 / 1M chars · Azure"), and feeds the
     * first-time cost-disclosure modal that fires when a user picks
     * an Azure voice for the first time. Single source of truth for
     * cost numbers — bumping a provider's published price is a
     * one-line change here, not a UI hunt.
     */
    val cost: VoiceCost? = null,
)

data class PiperPaths(val onnxUrl: String, val tokensUrl: String)

/**
 * Per-million-character billing for a cloud TTS voice. Stored as
 * integer cents to avoid floating-point cost arithmetic — the picker
 * formats the chip ("$30 / 1M chars · $billedBy") and the per-chapter
 * estimate computes `chars × centsPer1MChars / 1_000_000` cents, which
 * stays an integer until the final display.
 *
 * [billedBy] surfaces the provider name in the cost disclosure modal
 * ("You pay $billedBy directly — storyvox does not bill you.") so the
 * trust-boundary statement reads naturally regardless of which cloud
 * provider the entry routes to.
 */
data class VoiceCost(
    val centsPer1MChars: Int,
    val billedBy: String,
)
