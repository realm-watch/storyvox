package `in`.jphe.storyvox.ui.component

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Issue #619 — pure-logic tests for [humanizeVoiceLabel]. Pins the
 * shape of the output for each engine family so a future regression
 * (the chip showing `piper:en_US-amy-medium` again) is caught at
 * unit-test time rather than on a tablet pass.
 */
class HumanizeVoiceLabelTest {

    @Test
    fun `blank input returns blank`() {
        assertEquals("", humanizeVoiceLabel(""))
    }

    @Test
    fun `piper amy medium`() {
        assertEquals("Amy · medium quality", humanizeVoiceLabel("piper:en_US-amy-medium"))
    }

    @Test
    fun `piper lessac low`() {
        assertEquals("Lessac · low quality", humanizeVoiceLabel("piper:en_US-lessac-low"))
    }

    @Test
    fun `piper alan medium GB`() {
        assertEquals("Alan · medium quality", humanizeVoiceLabel("piper:en_GB-alan-medium"))
    }

    @Test
    fun `azure brian US english`() {
        assertEquals("Brian · US English", humanizeVoiceLabel("azure:en-US-BrianNeural"))
    }

    @Test
    fun `azure ava multilingual`() {
        assertEquals(
            "Ava · multilingual",
            humanizeVoiceLabel("azure:en-US-AvaMultilingualNeural"),
        )
    }

    @Test
    fun `azure ryan GB`() {
        assertEquals("Ryan · UK English", humanizeVoiceLabel("azure:en-GB-RyanNeural"))
    }

    @Test
    fun `azure denise French`() {
        assertEquals("Denise · French", humanizeVoiceLabel("azure:fr-FR-DeniseNeural"))
    }

    @Test
    fun `unknown azure locale falls back to lang-region`() {
        // Unmapped locale stays legible without truncation.
        assertEquals("Bob · sw-KE", humanizeVoiceLabel("azure:sw-KE-BobNeural"))
    }

    @Test
    fun `voxsherpa tier prefix stripped`() {
        assertEquals("narrator-warm", humanizeVoiceLabel("voxsherpa:tier3/narrator-warm"))
    }

    @Test
    fun `kokoro af_bella`() {
        assertEquals("Bella · Kokoro", humanizeVoiceLabel("kokoro:af_bella"))
    }

    @Test
    fun `bare voice id without engine prefix returned untouched`() {
        // No `:` — engine-less voice id (legacy callers, test fixtures).
        // We can't extract an engine context, so surface what we have.
        assertEquals("Brian · US English", humanizeVoiceLabel("en-US-BrianNeural"))
    }

    @Test
    fun `default label`() {
        // Pre-engine state in UiPlaybackState renders "Default" — should
        // pass through since there's no `:` separator.
        assertEquals("Default", humanizeVoiceLabel("Default"))
    }
}
