package `in`.jphe.storyvox.playback.voice

object VoiceCatalog {
    val voices: List<CatalogEntry> = piperEntries() + kokoroEntries() + azureEntries()
    fun byId(id: String): CatalogEntry? = voices.firstOrNull { it.id == id }

    /** The three voices we hand-picked as the strongest starters. Surfaced
     *  on the first-launch [VoicePickerGate] picker so newcomers don't
     *  have to scroll the 98-voice catalog hunting for the good ones.
     *
     *  In the Voice Library these are marked with the inline ⭐ prefix
     *  on their [CatalogEntry.displayName] (kept across #128's title
     *  cleanup) — the dedicated "Featured" section was removed in #129
     *  and these voices now appear in their natural Engine → Tier home
     *  alongside every other entry, just visually marked.
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
            displayName = "⭐ Lessac",
            language = "en_US",
            sizeBytes = 113895332L,
            qualityLevel = QualityLevel.High,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-lessac-high.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-lessac-high.tokens.txt",
            ),
            gender = VoiceGender.Female,
        ),
        CatalogEntry(
            id = "piper_ryan_en_US_high",
            displayName = "⭐ Ryan",
            language = "en_US",
            sizeBytes = 120786923L,
            qualityLevel = QualityLevel.High,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-ryan-high.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-ryan-high.tokens.txt",
            ),
            gender = VoiceGender.Male,
        ),
        CatalogEntry(
            id = "piper_amy_en_US_medium",
            displayName = "⭐ Amy",
            language = "en_US",
            sizeBytes = 63201425L,
            qualityLevel = QualityLevel.Medium,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-amy-medium.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-amy-medium.tokens.txt",
            ),
            gender = VoiceGender.Female,
        ),
        CatalogEntry(
            id = "piper_alan_en_GB_low",
            displayName = "Alan",
            language = "en_GB",
            sizeBytes = 63104662L,
            qualityLevel = QualityLevel.Low,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-alan-low.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-alan-low.tokens.txt",
            ),
            gender = VoiceGender.Male,
        ),
        CatalogEntry(
            id = "piper_alan_en_GB_medium",
            displayName = "Alan",
            language = "en_GB",
            sizeBytes = 63201430L,
            qualityLevel = QualityLevel.Medium,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-alan-medium.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-alan-medium.tokens.txt",
            ),
            gender = VoiceGender.Male,
        ),
        CatalogEntry(
            id = "piper_alba_en_GB_medium",
            displayName = "Alba",
            language = "en_GB",
            sizeBytes = 63201430L,
            qualityLevel = QualityLevel.Medium,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-alba-medium.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-alba-medium.tokens.txt",
            ),
            gender = VoiceGender.Female,
        ),
        CatalogEntry(
            id = "piper_aru_en_GB_medium",
            displayName = "Aru",
            language = "en_GB",
            sizeBytes = 76754234L,
            qualityLevel = QualityLevel.Medium,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-aru-medium.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-aru-medium.tokens.txt",
            ),
            // Aru is a multi-speaker dataset — leave gender Unknown so
            // the subtitle collapses cleanly to "Piper · Medium".
        ),
        CatalogEntry(
            id = "piper_cori_en_GB_medium",
            displayName = "Cori",
            language = "en_GB",
            sizeBytes = 63531507L,
            qualityLevel = QualityLevel.Medium,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-cori-medium.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-cori-medium.tokens.txt",
            ),
            gender = VoiceGender.Female,
        ),
        CatalogEntry(
            id = "piper_cori_en_GB_high",
            displayName = "Cori",
            language = "en_GB",
            sizeBytes = 114219480L,
            qualityLevel = QualityLevel.High,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-cori-high.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-cori-high.tokens.txt",
            ),
            gender = VoiceGender.Female,
        ),
        CatalogEntry(
            id = "piper_dii_en_GB_high",
            displayName = "Dii",
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
            displayName = "Jenny Dioco",
            language = "en_GB",
            sizeBytes = 63201430L,
            qualityLevel = QualityLevel.Medium,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-jenny_dioco-medium.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-jenny_dioco-medium.tokens.txt",
            ),
            gender = VoiceGender.Female,
        ),
        CatalogEntry(
            id = "piper_miro_en_GB_high",
            displayName = "Miro",
            language = "en_GB",
            sizeBytes = 63511174L,
            qualityLevel = QualityLevel.High,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-miro-high.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-miro-high.tokens.txt",
            ),
            gender = VoiceGender.Male,
        ),
        CatalogEntry(
            id = "piper_northern_english_male_en_GB_medium",
            displayName = "Northern English",
            language = "en_GB",
            sizeBytes = 63201430L,
            qualityLevel = QualityLevel.Medium,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-northern_english_male-medium.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-northern_english_male-medium.tokens.txt",
            ),
            // Gender lived in the title before #128 ("Northern English Male");
            // it now lives in the subtitle, so the title drops the suffix.
            gender = VoiceGender.Male,
        ),
        CatalogEntry(
            id = "piper_semaine_en_GB_medium",
            displayName = "Semaine",
            language = "en_GB",
            sizeBytes = 76737847L,
            qualityLevel = QualityLevel.Medium,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-semaine-medium.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-semaine-medium.tokens.txt",
            ),
            // Semaine is a multi-speaker corpus — leave gender Unknown.
        ),
        CatalogEntry(
            id = "piper_southern_english_female_en_GB_low",
            displayName = "Southern English",
            language = "en_GB",
            sizeBytes = 63104662L,
            qualityLevel = QualityLevel.Low,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-southern_english_female-low.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-southern_english_female-low.tokens.txt",
            ),
            gender = VoiceGender.Female,
        ),
        CatalogEntry(
            id = "piper_southern_english_female_en_GB_medium",
            displayName = "Southern English",
            language = "en_GB",
            sizeBytes = 77059414L,
            qualityLevel = QualityLevel.Medium,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-southern_english_female-medium.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-southern_english_female-medium.tokens.txt",
            ),
            gender = VoiceGender.Female,
        ),
        CatalogEntry(
            id = "piper_southern_english_male_en_GB_medium",
            displayName = "Southern English",
            language = "en_GB",
            sizeBytes = 77063512L,
            qualityLevel = QualityLevel.Medium,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-southern_english_male-medium.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-southern_english_male-medium.tokens.txt",
            ),
            gender = VoiceGender.Male,
        ),
        CatalogEntry(
            id = "piper_sweetbbak_amy_en_GB_high",
            displayName = "Sweetbbak Amy",
            language = "en_GB",
            sizeBytes = 114199142L,
            qualityLevel = QualityLevel.High,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-sweetbbak-amy.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-sweetbbak-amy.tokens.txt",
            ),
            gender = VoiceGender.Female,
        ),
        CatalogEntry(
            id = "piper_vctk_en_GB_medium",
            displayName = "VCTK",
            language = "en_GB",
            sizeBytes = 76952891L,
            qualityLevel = QualityLevel.Medium,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-vctk-medium.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_GB-vctk-medium.tokens.txt",
            ),
            // VCTK is a multi-speaker corpus — leave gender Unknown.
        ),
        CatalogEntry(
            id = "piper_amy_en_US_low",
            displayName = "Amy",
            language = "en_US",
            sizeBytes = 63104657L,
            qualityLevel = QualityLevel.Low,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-amy-low.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-amy-low.tokens.txt",
            ),
            gender = VoiceGender.Female,
        ),
        CatalogEntry(
            id = "piper_arctic_en_US_medium",
            displayName = "Arctic",
            language = "en_US",
            sizeBytes = 76715309L,
            qualityLevel = QualityLevel.Medium,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-arctic-medium.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-arctic-medium.tokens.txt",
            ),
            // CMU Arctic is a multi-speaker corpus — gender Unknown.
        ),
        CatalogEntry(
            id = "piper_bryce_en_US_medium",
            displayName = "Bryce",
            language = "en_US",
            sizeBytes = 63152970L,
            qualityLevel = QualityLevel.Medium,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-bryce-medium.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-bryce-medium.tokens.txt",
            ),
            gender = VoiceGender.Male,
        ),
        CatalogEntry(
            id = "piper_danny_en_US_low",
            displayName = "Danny",
            language = "en_US",
            sizeBytes = 63052430L,
            qualityLevel = QualityLevel.Low,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-danny-low.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-danny-low.tokens.txt",
            ),
            gender = VoiceGender.Male,
        ),
        CatalogEntry(
            id = "piper_glados_en_US_high",
            displayName = "GLaDOS",
            language = "en_US",
            sizeBytes = 113800584L,
            qualityLevel = QualityLevel.High,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-glados-high.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-glados-high.tokens.txt",
            ),
            gender = VoiceGender.Female,
        ),
        CatalogEntry(
            id = "piper_glados_en_US_medium",
            displayName = "GLaDOS",
            language = "en_US",
            sizeBytes = 63511169L,
            qualityLevel = QualityLevel.Medium,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-glados.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-glados.tokens.txt",
            ),
            gender = VoiceGender.Female,
        ),
        CatalogEntry(
            id = "piper_hfc_female_en_US_medium",
            displayName = "HFC",
            language = "en_US",
            sizeBytes = 63149198L,
            qualityLevel = QualityLevel.Medium,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-hfc_female-medium.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-hfc_female-medium.tokens.txt",
            ),
            // "Female" lived in the title before #128; now it lives only in the subtitle.
            gender = VoiceGender.Female,
        ),
        CatalogEntry(
            id = "piper_hfc_male_en_US_medium",
            displayName = "HFC",
            language = "en_US",
            sizeBytes = 63149198L,
            qualityLevel = QualityLevel.Medium,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-hfc_male-medium.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-hfc_male-medium.tokens.txt",
            ),
            gender = VoiceGender.Male,
        ),
        CatalogEntry(
            id = "piper_joe_en_US_medium",
            displayName = "Joe",
            language = "en_US",
            sizeBytes = 63149198L,
            qualityLevel = QualityLevel.Medium,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-joe-medium.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-joe-medium.tokens.txt",
            ),
            gender = VoiceGender.Male,
        ),
        CatalogEntry(
            id = "piper_john_en_US_medium",
            displayName = "John",
            language = "en_US",
            sizeBytes = 63152970L,
            qualityLevel = QualityLevel.Medium,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-john-medium.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-john-medium.tokens.txt",
            ),
            gender = VoiceGender.Male,
        ),
        CatalogEntry(
            id = "piper_kathleen_en_US_low",
            displayName = "Kathleen",
            language = "en_US",
            sizeBytes = 63052430L,
            qualityLevel = QualityLevel.Low,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-kathleen-low.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-kathleen-low.tokens.txt",
            ),
            gender = VoiceGender.Female,
        ),
        CatalogEntry(
            id = "piper_kristin_en_US_medium",
            displayName = "Kristin",
            language = "en_US",
            sizeBytes = 63531507L,
            qualityLevel = QualityLevel.Medium,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-kristin-medium.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-kristin-medium.tokens.txt",
            ),
            gender = VoiceGender.Female,
        ),
        CatalogEntry(
            id = "piper_kusal_en_US_medium",
            displayName = "Kusal",
            language = "en_US",
            sizeBytes = 63201425L,
            qualityLevel = QualityLevel.Medium,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-kusal-medium.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-kusal-medium.tokens.txt",
            ),
            gender = VoiceGender.Male,
        ),
        CatalogEntry(
            id = "piper_l2arctic_en_US_medium",
            displayName = "L2 Arctic",
            language = "en_US",
            sizeBytes = 76778805L,
            qualityLevel = QualityLevel.Medium,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-l2arctic-medium.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-l2arctic-medium.tokens.txt",
            ),
            // L2-Arctic is a multi-speaker non-native-English corpus — gender Unknown.
        ),
        CatalogEntry(
            id = "piper_lessac_en_US_low",
            displayName = "Lessac",
            language = "en_US",
            sizeBytes = 63149198L,
            qualityLevel = QualityLevel.Low,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-lessac-low.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-lessac-low.tokens.txt",
            ),
            gender = VoiceGender.Female,
        ),
        CatalogEntry(
            id = "piper_lessac_en_US_medium",
            displayName = "Lessac",
            language = "en_US",
            sizeBytes = 63149198L,
            qualityLevel = QualityLevel.Medium,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-lessac-medium.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-lessac-medium.tokens.txt",
            ),
            gender = VoiceGender.Female,
        ),
        CatalogEntry(
            id = "piper_libritts_en_US_high",
            displayName = "LibriTTS",
            language = "en_US",
            sizeBytes = 129438513L,
            qualityLevel = QualityLevel.High,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-libritts-high.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-libritts-high.tokens.txt",
            ),
            // LibriTTS is a multi-speaker corpus — gender Unknown.
        ),
        CatalogEntry(
            id = "piper_libritts_r_en_US_medium",
            displayName = "LibriTTS R",
            language = "en_US",
            sizeBytes = 78529840L,
            qualityLevel = QualityLevel.Medium,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-libritts_r-medium.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-libritts_r-medium.tokens.txt",
            ),
            // LibriTTS-R is a multi-speaker corpus — gender Unknown.
        ),
        CatalogEntry(
            id = "piper_ljspeech_en_US_medium",
            displayName = "LJ Speech",
            language = "en_US",
            sizeBytes = 63531507L,
            qualityLevel = QualityLevel.Medium,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-ljspeech-medium.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-ljspeech-medium.tokens.txt",
            ),
            gender = VoiceGender.Female,
        ),
        CatalogEntry(
            id = "piper_ljspeech_en_US_high",
            displayName = "LJ Speech",
            language = "en_US",
            sizeBytes = 114199139L,
            qualityLevel = QualityLevel.High,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-ljspeech-high.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-ljspeech-high.tokens.txt",
            ),
            gender = VoiceGender.Female,
        ),
        CatalogEntry(
            id = "piper_miro_en_US_high",
            displayName = "Miro",
            language = "en_US",
            sizeBytes = 63511169L,
            qualityLevel = QualityLevel.High,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-miro-high.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-miro-high.tokens.txt",
            ),
            gender = VoiceGender.Male,
        ),
        CatalogEntry(
            id = "piper_norman_en_US_medium",
            displayName = "Norman",
            language = "en_US",
            sizeBytes = 63531507L,
            qualityLevel = QualityLevel.Medium,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-norman-medium.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-norman-medium.tokens.txt",
            ),
            gender = VoiceGender.Male,
        ),
        CatalogEntry(
            id = "piper_reza_ibrahim_en_US_medium",
            displayName = "Reza Ibrahim",
            language = "en_US",
            sizeBytes = 63511169L,
            qualityLevel = QualityLevel.Medium,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-reza_ibrahim-medium.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-reza_ibrahim-medium.tokens.txt",
            ),
            gender = VoiceGender.Male,
        ),
        CatalogEntry(
            id = "piper_ryan_en_US_low",
            displayName = "Ryan",
            language = "en_US",
            sizeBytes = 63052430L,
            qualityLevel = QualityLevel.Low,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-ryan-low.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-ryan-low.tokens.txt",
            ),
            gender = VoiceGender.Male,
        ),
        CatalogEntry(
            id = "piper_ryan_en_US_medium",
            displayName = "Ryan",
            language = "en_US",
            sizeBytes = 63149198L,
            qualityLevel = QualityLevel.Medium,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-ryan-medium.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-ryan-medium.tokens.txt",
            ),
            gender = VoiceGender.Male,
        ),
        CatalogEntry(
            id = "piper_sam_en_US_medium",
            displayName = "Sam",
            language = "en_US",
            sizeBytes = 62946438L,
            qualityLevel = QualityLevel.Medium,
            engineType = EngineType.Piper,
            piper = PiperPaths(
                onnxUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-sam-medium.onnx",
                tokensUrl = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/en_US-sam-medium.tokens.txt",
            ),
            // "Sam" is unisex — leave gender Unknown.
        ),
    )

    /** Kokoro entries — 53 speakers all sharing one bundled model.
     *  Quality tier is **derived** from voice id via [VoiceTier.forKokoroId]
     *  rather than hardcoded inline so the rule lives in one place and
     *  stays unit-testable. The vast majority resolve to [QualityLevel.High];
     *  a curated few (see [VoiceTier.STUDIO_KOKORO_IDS]) get bumped to
     *  [QualityLevel.Studio].
     *
     *  Display names are clean (no flag prefix, no "(Language Gender)"
     *  parenthetical) per #128 — the Voice Library composes the on-screen
     *  title as `<flag> <displayName>` and the subtitle as
     *  `<Engine> · <Tier> · <Gender>`. Language is already represented by
     *  the rendered flag, so we don't repeat it. */
    private fun kokoroEntries(): List<CatalogEntry> {
        fun kokoro(id: String, displayName: String, language: String, speakerId: Int, gender: VoiceGender): CatalogEntry =
            CatalogEntry(
                id = id,
                displayName = displayName,
                language = language,
                sizeBytes = 0L,
                qualityLevel = VoiceTier.forKokoroId(id),
                engineType = EngineType.Kokoro(speakerId = speakerId),
                piper = null,
                gender = gender,
            )
        val F = VoiceGender.Female
        val M = VoiceGender.Male
        return listOf(
            kokoro("kokoro_alloy_en_US_0", "Alloy", "en_US", 0, F),
            kokoro("kokoro_aoede_en_US_1", "Aoede", "en_US", 1, F),
            kokoro("kokoro_bella_en_US_2", "Bella", "en_US", 2, F),
            kokoro("kokoro_heart_en_US_3", "Heart", "en_US", 3, F),
            kokoro("kokoro_jessica_en_US_4", "Jessica", "en_US", 4, F),
            kokoro("kokoro_kore_en_US_5", "Kore", "en_US", 5, F),
            kokoro("kokoro_nicole_en_US_6", "Nicole", "en_US", 6, F),
            kokoro("kokoro_nova_en_US_7", "Nova", "en_US", 7, F),
            kokoro("kokoro_river_en_US_8", "River", "en_US", 8, F),
            kokoro("kokoro_sarah_en_US_9", "Sarah", "en_US", 9, F),
            kokoro("kokoro_sky_en_US_10", "Sky", "en_US", 10, F),
            kokoro("kokoro_adam_en_US_11", "Adam", "en_US", 11, M),
            kokoro("kokoro_echo_en_US_12", "Echo", "en_US", 12, M),
            kokoro("kokoro_eric_en_US_13", "Eric", "en_US", 13, M),
            kokoro("kokoro_fenrir_en_US_14", "Fenrir", "en_US", 14, M),
            kokoro("kokoro_liam_en_US_15", "Liam", "en_US", 15, M),
            kokoro("kokoro_michael_en_US_16", "Michael", "en_US", 16, M),
            kokoro("kokoro_onyx_en_US_17", "Onyx", "en_US", 17, M),
            kokoro("kokoro_puck_en_US_18", "Puck", "en_US", 18, M),
            kokoro("kokoro_santa_en_US_19", "Santa", "en_US", 19, M),
            kokoro("kokoro_alice_en_GB_20", "Alice", "en_GB", 20, F),
            kokoro("kokoro_emma_en_GB_21", "Emma", "en_GB", 21, F),
            kokoro("kokoro_isabella_en_GB_22", "Isabella", "en_GB", 22, F),
            kokoro("kokoro_lily_en_GB_23", "Lily", "en_GB", 23, F),
            kokoro("kokoro_daniel_en_GB_24", "Daniel", "en_GB", 24, M),
            kokoro("kokoro_fable_en_GB_25", "Fable", "en_GB", 25, M),
            kokoro("kokoro_george_en_GB_26", "George", "en_GB", 26, M),
            kokoro("kokoro_lewis_en_GB_27", "Lewis", "en_GB", 27, M),
            kokoro("kokoro_dora_es_ES_28", "Dora", "es_ES", 28, F),
            kokoro("kokoro_alex_es_ES_29", "Alex", "es_ES", 29, M),
            kokoro("kokoro_siwis_fr_FR_30", "Siwis", "fr_FR", 30, F),
            kokoro("kokoro_alpha_hi_IN_31", "Alpha", "hi_IN", 31, F),
            kokoro("kokoro_beta_hi_IN_32", "Beta", "hi_IN", 32, F),
            kokoro("kokoro_omega_hi_IN_33", "Omega", "hi_IN", 33, M),
            kokoro("kokoro_psi_hi_IN_34", "Psi", "hi_IN", 34, M),
            kokoro("kokoro_sara_it_IT_35", "Sara", "it_IT", 35, F),
            kokoro("kokoro_nicola_it_IT_36", "Nicola", "it_IT", 36, M),
            kokoro("kokoro_alpha_ja_JP_37", "Alpha", "ja_JP", 37, F),
            kokoro("kokoro_gongitsune_ja_JP_38", "Gongitsune", "ja_JP", 38, F),
            kokoro("kokoro_nezumi_ja_JP_39", "Nezumi", "ja_JP", 39, F),
            kokoro("kokoro_tebukuro_ja_JP_40", "Tebukuro", "ja_JP", 40, F),
            kokoro("kokoro_kumo_ja_JP_41", "Kumo", "ja_JP", 41, M),
            kokoro("kokoro_dora_pt_PT_42", "Dora", "pt_PT", 42, F),
            kokoro("kokoro_alex_pt_PT_43", "Alex", "pt_PT", 43, M),
            kokoro("kokoro_santa_pt_PT_44", "Santa", "pt_PT", 44, M),
            kokoro("kokoro_xiaobei_zh_CN_45", "Xiaobei", "zh_CN", 45, F),
            kokoro("kokoro_xiaoni_zh_CN_46", "Xiaoni", "zh_CN", 46, F),
            kokoro("kokoro_xiaoxiao_zh_CN_47", "Xiaoxiao", "zh_CN", 47, F),
            kokoro("kokoro_xiaoyi_zh_CN_48", "Xiaoyi", "zh_CN", 48, F),
            kokoro("kokoro_yunjian_zh_CN_49", "Yunjian", "zh_CN", 49, M),
            kokoro("kokoro_yunxi_zh_CN_50", "Yunxi", "zh_CN", 50, M),
            kokoro("kokoro_yunxia_zh_CN_51", "Yunxia", "zh_CN", 51, M),
            kokoro("kokoro_yunyang_zh_CN_52", "Yunyang", "zh_CN", 52, M),
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
            // ── Dragon HD (Azure's 2025 generative tier — highest quality) ──
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
            azure(
                "azure_brian_en_US_dragon_hd",
                "☁️ Brian (Dragon HD)",
                "en_US",
                "en-US-BrianMultilingualNeural",
            ),
            azure(
                "azure_emma_en_US_dragon_hd",
                "☁️ Emma (Dragon HD)",
                "en_US",
                "en-US-EmmaMultilingualNeural",
            ),
            // ── en-US HD Neural — broad gender/age coverage ────────────
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
                "azure_guy_en_US_hd",
                "☁️ Guy (HD Neural)",
                "en_US",
                "en-US-GuyNeural",
            ),
            azure(
                "azure_davis_en_US_hd",
                "☁️ Davis (HD Neural)",
                "en_US",
                "en-US-DavisMultilingualNeural",
            ),
            azure(
                "azure_tony_en_US_hd",
                "☁️ Tony (HD Neural)",
                "en_US",
                "en-US-TonyNeural",
            ),
            azure(
                "azure_sara_en_US_hd",
                "☁️ Sara (HD Neural)",
                "en_US",
                "en-US-SaraNeural",
            ),
            azure(
                "azure_christopher_en_US_hd",
                "☁️ Christopher (HD Neural)",
                "en_US",
                "en-US-ChristopherNeural",
            ),
            azure(
                "azure_nancy_en_US_hd",
                "☁️ Nancy (HD Neural)",
                "en_US",
                "en-US-NancyNeural",
            ),
            // ── en-GB HD Neural — UK English ───────────────────────────
            azure(
                "azure_sonia_en_GB_hd",
                "☁️ Sonia (HD Neural, British)",
                "en_GB",
                "en-GB-SoniaNeural",
            ),
            azure(
                "azure_ryan_en_GB_hd",
                "☁️ Ryan (HD Neural, British)",
                "en_GB",
                "en-GB-RyanNeural",
            ),
            azure(
                "azure_libby_en_GB_hd",
                "☁️ Libby (HD Neural, British)",
                "en_GB",
                "en-GB-LibbyNeural",
            ),
            // ── en-AU HD Neural — Australian English ──────────────────
            azure(
                "azure_natasha_en_AU_hd",
                "☁️ Natasha (HD Neural, Australian)",
                "en_AU",
                "en-AU-NatashaNeural",
            ),
            azure(
                "azure_william_en_AU_hd",
                "☁️ William (HD Neural, Australian)",
                "en_AU",
                "en-AU-WilliamNeural",
            ),
            // ── en-IN HD Neural — Indian English ──────────────────────
            azure(
                "azure_neerja_en_IN_hd",
                "☁️ Neerja (HD Neural, Indian)",
                "en_IN",
                "en-IN-NeerjaNeural",
            ),
            azure(
                "azure_prabhat_en_IN_hd",
                "☁️ Prabhat (HD Neural, Indian)",
                "en_IN",
                "en-IN-PrabhatNeural",
            ),
            // ── en-CA HD Neural — Canadian English ────────────────────
            azure(
                "azure_clara_en_CA_hd",
                "☁️ Clara (HD Neural, Canadian)",
                "en_CA",
                "en-CA-ClaraNeural",
            ),
        )
    }
}

data class CatalogEntry(
    val id: String,
    /** Clean voice name only — e.g. "Lessac", "Aoede". No tier
     *  parentheticals, no flag prefix, no engine/quality clutter. The
     *  ⭐ marker on curated entries stays inline (see [VoiceCatalog.featuredIds]).
     *  See #128 — the Voice Library composes the on-screen title as
     *  `<flag> <displayName>` and the subtitle as `<Engine> · <Tier> · <Gender>`. */
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
    /** Best-effort gender from upstream metadata. Defaults to
     *  [VoiceGender.Unknown] for Piper voices whose filenames don't
     *  encode gender (e.g. "lessac" — a name, not a gender marker). */
    val gender: VoiceGender = VoiceGender.Unknown,
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

/** Map a BCP-47-ish language code (the catalog uses POSIX-style
 *  `xx_YY`) to a country flag emoji. Falls back to a globe glyph for
 *  any code we haven't enumerated — keeps the title prefix non-empty
 *  so layout doesn't shift when a new language lands in the catalog
 *  before its flag mapping does. */
fun flagForLanguage(language: String): String = when (language) {
    "en_US" -> "🇺🇸"
    "en_GB" -> "🇬🇧"
    "es_ES" -> "🇪🇸"
    "fr_FR" -> "🇫🇷"
    "hi_IN" -> "🇮🇳"
    "it_IT" -> "🇮🇹"
    "ja_JP" -> "🇯🇵"
    "pt_PT" -> "🇵🇹"
    "zh_CN" -> "🇨🇳"
    else -> "🌐"
}
