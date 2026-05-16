package `in`.jphe.storyvox.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Issue #609 (v1.0 blocker) — TalkBack used to read the raw catalog
 * id ("piper_vctk_en_GB_medium") literally, one syllable per
 * underscore. The new `humanizeVoiceLabel` helper rewrites it for
 * the screen-reader-only contentDescription on the voice chip while
 * the visible chip label keeps using [formatVoiceLabel] (power-user
 * identifier preview).
 *
 * The tests pin specific rewrites end-to-end so a future regression
 * (someone changing the rule order, dropping a locale, etc.) fails
 * fast with the exact string that broke.
 */
class HumanizeVoiceLabelTest {

    @Test
    fun `bare blank input yields a neutral fallback`() {
        assertEquals("no voice selected", humanizeVoiceLabel(""))
    }

    @Test
    fun `engine prefix is humanized even with empty body`() {
        assertEquals("Piper", humanizeVoiceLabel("piper:"))
    }

    @Test
    fun `piper en_GB medium reads as English UK with quality tier`() {
        // The motivating example from issue #609. The pre-fix readout
        // was: "piper underscore vctk underscore en underscore GB
        // underscore medium" — 12 syllables for a 5-word phrase.
        assertEquals(
            "Piper Vctk English (United Kingdom) Medium quality",
            humanizeVoiceLabel("piper:vctk_en_GB_medium"),
        )
    }

    @Test
    fun `piper en_US amy medium reads in human English`() {
        assertEquals(
            "Piper English (United States) Amy Medium quality",
            humanizeVoiceLabel("piper:en_US_amy_medium"),
        )
    }

    @Test
    fun `azure aria multilingual is rewritten without underscore reads`() {
        assertEquals(
            "Azure Aria Multilingual",
            humanizeVoiceLabel("azure:aria-multilingual"),
        )
    }

    @Test
    fun `voxsherpa kokoro is humanized via the alias map`() {
        // The engine id `voxsherpa` aliases `kokoro` and `sherpa` —
        // all three should resolve to "VoxSherpa" engine prefix.
        assertEquals(
            "VoxSherpa Tier3 Narrator Warm",
            humanizeVoiceLabel("voxsherpa:tier3-narrator-warm"),
        )
    }

    @Test
    fun `dragon HD voice variants land as separate words`() {
        // Azure ships Dragon HD voices; the catalog id often looks
        // like `aria-dragon-hd`. Both `dragon` and `hd` should
        // surface as readable words, not initialisms.
        assertEquals(
            "Azure Aria Dragon HD",
            humanizeVoiceLabel("azure:aria-dragon-hd"),
        )
    }

    @Test
    fun `pt_BR locale resolves to Portuguese Brazil`() {
        assertEquals(
            "Piper Portuguese (Brazil) Faber Medium quality",
            humanizeVoiceLabel("piper:pt_BR-faber-medium"),
        )
    }

    @Test
    fun `Default sentinel passes through unchanged`() {
        // "Default" is the special sentinel AppBindings.voiceLabel
        // emits before a real voice has resolved (cold launch). It
        // shouldn't be tokenized — the user reads "Default" as the
        // English word, not "Default" tokenized.
        assertEquals("Default", humanizeVoiceLabel("Default"))
    }

    @Test
    fun `flat catalog id with engine prefix as first token is humanized`() {
        // Bug observed on R83W80CAFZB 2026-05-16: voiceLabel arrives
        // as `piper_lessac_en_US_low` (no colon — AppBindings pipes
        // voiceId through verbatim). We need to recognise the
        // `piper_` prefix and humanize the rest.
        assertEquals(
            "Piper Lessac English (United States) Low quality",
            humanizeVoiceLabel("piper_lessac_en_US_low"),
        )
    }

    @Test
    fun `flat catalog id without known engine prefix is still tokenized`() {
        // If the engine prefix isn't recognised, we still split on
        // separators and humanize what we can. Better than literal
        // underscore-by-underscore TalkBack readout.
        assertEquals(
            "Mysteryengine Aria Medium quality",
            humanizeVoiceLabel("mysteryengine_aria_medium"),
        )
    }
}
