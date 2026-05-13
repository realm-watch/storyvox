package `in`.jphe.storyvox.feature.voicelibrary

import `in`.jphe.storyvox.playback.voice.EngineType
import `in`.jphe.storyvox.playback.voice.QualityLevel
import `in`.jphe.storyvox.playback.voice.UiVoiceInfo
import `in`.jphe.storyvox.playback.voice.VoiceCatalog
import `in`.jphe.storyvox.playback.voice.VoiceGender
import `in`.jphe.storyvox.playback.voice.flagForLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spot-checks for the #128 voice-naming normalization.
 *
 * The contract: [VoiceCatalog] entries' [displayName] is the **clean
 * voice name** only — no `(Tier)` suffix, no `(Language Gender)`
 * parenthetical, no flag prefix. The Voice Library composes the
 * on-screen title as `<flag> <displayName>` and the subtitle as
 * `<Engine> · <Tier> · <Gender>` via [voiceSubtitle].
 *
 * Test coverage:
 *  - 5 catalog spot-checks across Piper/Kokoro/curated/multi-speaker.
 *  - End-to-end title + subtitle composition.
 *  - Subtitle gracefully drops an unknown gender segment.
 */
class VoiceNamingTest {

    @Test
    fun `Piper Lessac high has no star marker and drops tier paren`() {
        val entry = VoiceCatalog.byId("piper_lessac_en_US_high")
        requireNotNull(entry) { "piper_lessac_en_US_high must exist in catalog" }
        // ⭐ marker removed 2026-05-13 — favorites in the Voice Library
        // now own the star glyph; double-using it for "featured" was
        // reading to users as a stale favorite. The starter triplet
        // surfacing is driven by [VoiceCatalog.featuredIds] alone now.
        assertEquals("Lessac", entry.displayName)
        assertFalse(
            "displayName must not contain '(High)' parenthetical",
            entry.displayName.contains("(High)"),
        )
        assertFalse(
            "displayName must not carry the ⭐ marker (favorites own that glyph now)",
            entry.displayName.contains("⭐"),
        )
        assertEquals(VoiceGender.Female, entry.gender)
    }

    @Test
    fun `Piper Cori high has no flag prefix in displayName`() {
        // Cori is the en_GB curated featured voice (referenced from
        // VoiceCatalog.featuredIds) — flag belongs at render time.
        val entry = VoiceCatalog.byId("piper_cori_en_GB_high")
        requireNotNull(entry)
        assertEquals("Cori", entry.displayName)
        assertFalse(
            "Piper displayName must not embed a flag emoji",
            entry.displayName.contains("🇬🇧"),
        )
    }

    @Test
    fun `Kokoro Aoede has no flag prefix and no language-gender parenthetical`() {
        val entry = VoiceCatalog.byId("kokoro_aoede_en_US_1")
        requireNotNull(entry)
        assertEquals("Aoede", entry.displayName)
        assertFalse(
            "Kokoro displayName must not embed a flag emoji",
            entry.displayName.contains("🇺🇸"),
        )
        assertFalse(
            "Kokoro displayName must not carry '(English Female)' parenthetical",
            entry.displayName.contains("("),
        )
        assertEquals(VoiceGender.Female, entry.gender)
    }

    @Test
    fun `Piper VCTK gets a clean name and Unknown gender for multi-speaker corpus`() {
        // VCTK is a multi-speaker Piper corpus — gender shouldn't be
        // claimed; the subtitle must collapse to "Piper · Medium".
        val entry = VoiceCatalog.byId("piper_vctk_en_GB_medium")
        requireNotNull(entry)
        assertEquals("VCTK", entry.displayName)
        assertEquals(VoiceGender.Unknown, entry.gender)
    }

    @Test
    fun `Piper hfc female keeps gender out of title and into the gender field`() {
        // Pre-#128 the title was "Hfc Female (Medium)" — gender baked
        // into the title. Post-#128 the title is just the carrier name
        // and the gender lives on the subtitle.
        val entry = VoiceCatalog.byId("piper_hfc_female_en_US_medium")
        requireNotNull(entry)
        assertEquals("HFC", entry.displayName)
        assertEquals(VoiceGender.Female, entry.gender)
        assertFalse(
            "Title must not redundantly carry 'Female' once it lives in the subtitle",
            entry.displayName.contains("Female"),
        )
    }

    @Test
    fun `subtitle composes Engine Tier Gender for a Kokoro entry`() {
        val voice = UiVoiceInfo(
            id = "kokoro_aoede_en_US_1",
            displayName = "Aoede",
            language = "en_US",
            sizeBytes = 0L,
            isInstalled = true,
            qualityLevel = QualityLevel.High,
            engineType = EngineType.Kokoro(speakerId = 1),
            gender = VoiceGender.Female,
        )
        assertEquals("Kokoro  ·  High  ·  Female", voiceSubtitle(voice))
    }

    @Test
    fun `subtitle drops gender segment when Unknown`() {
        // VCTK multi-speaker corpus — gender Unknown — subtitle should
        // collapse to "Piper · Medium" with no trailing dot.
        val voice = UiVoiceInfo(
            id = "piper_vctk_en_GB_medium",
            displayName = "VCTK",
            language = "en_GB",
            sizeBytes = 76952891L,
            isInstalled = false,
            qualityLevel = QualityLevel.Medium,
            engineType = EngineType.Piper,
            gender = VoiceGender.Unknown,
        )
        assertEquals("Piper  ·  Medium", voiceSubtitle(voice))
    }

    @Test
    fun `flag mapping covers the languages used by the catalog`() {
        // Pin the mappings the catalog actually relies on — a future
        // refactor that drops one of these silently regresses to the
        // 🌐 fallback for every voice in that language.
        assertEquals("🇺🇸", flagForLanguage("en_US"))
        assertEquals("🇬🇧", flagForLanguage("en_GB"))
        assertEquals("🇨🇳", flagForLanguage("zh_CN"))
        // Unknown language falls back to a globe — never empty.
        assertTrue(flagForLanguage("xx_YY").isNotEmpty())
    }

    @Test
    fun `every Piper title is free of tier parenthetical`() {
        // Sweep across the whole catalog — the shape of the contract
        // is "no `(Tier)` suffix anywhere". Catches any future entry
        // that gets pasted in with the old format by accident.
        val offenders = VoiceCatalog.voices.filter { entry ->
            entry.engineType is EngineType.Piper &&
                (entry.displayName.contains("(High)") ||
                    entry.displayName.contains("(Medium)") ||
                    entry.displayName.contains("(Low)"))
        }
        assertTrue(
            "Piper voices retaining a tier parenthetical: ${offenders.map { it.id }}",
            offenders.isEmpty(),
        )
    }
}
