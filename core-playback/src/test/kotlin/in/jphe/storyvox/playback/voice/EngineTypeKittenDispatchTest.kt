package `in`.jphe.storyvox.playback.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #119 — pinning the cross-engine dispatcher's discriminator
 * layout so a Kitten regression can't silently fall through into the
 * Piper branch (or vice versa).
 *
 * Background: `EnginePlayer.loadAndPlay` switches on `active.engineType`
 * to pick the right loadModel call. The switch is non-exhaustive at the
 * Kotlin compiler level only because `EngineType` is `sealed` — adding
 * a fourth variant without updating the dispatcher would be a compile
 * error in production code. But the *catalog* could still misclassify
 * (e.g. a `kitten_*` ID accidentally typed as `EngineType.Piper`),
 * which would route the load through `VoiceEngine.loadModel` and crash
 * at runtime with a missing-onnx error.
 *
 * These tests exercise the catalog's classification so that mistake
 * fails CI rather than a tablet.
 */
class EngineTypeKittenDispatchTest {

    @Test
    fun `every kitten_ catalog id is typed as EngineType Kitten`() {
        // Naming convention is load-bearing — VoiceManager strips the
        // historical `_int8` suffix before lookup but it never rewrites
        // a `kitten_` prefix, so a misclassified entry would silently
        // route through the wrong engine. Walk the catalog and confirm
        // every id with the Kitten prefix is in fact EngineType.Kitten.
        val mismatches = VoiceCatalog.voices
            .filter { it.id.startsWith("kitten_") }
            .filter { it.engineType !is EngineType.Kitten }
        assertTrue(
            "kitten_-prefixed entries must be EngineType.Kitten, " +
                "but found mismatches: ${mismatches.map { it.id }}",
            mismatches.isEmpty(),
        )
    }

    @Test
    fun `every EngineType Kitten catalog id starts with kitten_ prefix`() {
        // The converse — if EngineType.Kitten is the source of truth,
        // every voice that carries that type must also use the
        // catalog's naming convention. Storyvox's analytics + crash
        // breadcrumbs grep on the prefix, and the `featuredIds` list
        // and gate UI key on the prefix indirectly. A future entry
        // without the prefix would split-brain those paths.
        val mismatches = VoiceCatalog.voices
            .filter { it.engineType is EngineType.Kitten }
            .filter { !it.id.startsWith("kitten_") }
        assertTrue(
            "EngineType.Kitten entries must start with `kitten_`, " +
                "but found: ${mismatches.map { it.id }}",
            mismatches.isEmpty(),
        )
    }

    @Test
    fun `Kitten entries do NOT carry a Piper payload`() {
        // The CatalogEntry shape carries an Optional `piper: PiperPaths?`
        // field used only for the Piper-per-voice-onnx download path.
        // Kitten uses a shared model (resolved via VoiceManager's
        // kittenSharedDir()), so the piper field must stay null on
        // every Kitten entry — a non-null value would let the download
        // path route through the wrong branch in a future refactor
        // that accidentally collapses the `when (engineType)` dispatch.
        VoiceCatalog.voices
            .filter { it.engineType is EngineType.Kitten }
            .forEach { entry ->
                assertEquals(
                    "Kitten entry ${entry.id} should have null piper paths",
                    null, entry.piper,
                )
            }
    }

    @Test
    fun `Piper Kokoro Kitten and Azure are all distinct EngineType subclasses`() {
        // Sanity: the four engine families need to remain distinguishable
        // by `is`-check. If a future refactor merges Kitten into Kokoro
        // (e.g. as a shared `MultiSpeakerLocal` base), the dispatcher
        // sites in EnginePlayer would need restructuring — pin the
        // four-way distinction here so that refactor surfaces in CI.
        val piper: EngineType = EngineType.Piper
        val kokoro: EngineType = EngineType.Kokoro(speakerId = 0)
        val kitten: EngineType = EngineType.Kitten(speakerId = 0)
        val azure: EngineType = EngineType.Azure(voiceName = "test", region = "test")

        // Piper is data object — same instance.
        assertNotNull(piper)
        assertTrue("Piper is EngineType.Piper", piper is EngineType.Piper)

        // The three data classes are distinct types.
        assertTrue("Kokoro is EngineType.Kokoro", kokoro is EngineType.Kokoro)
        assertTrue("Kitten is EngineType.Kitten", kitten is EngineType.Kitten)
        assertTrue("Azure is EngineType.Azure", azure is EngineType.Azure)

        // Cross-checks: a Kitten instance is NOT a Kokoro instance,
        // even though both wrap a speakerId int. This is the property
        // that makes EnginePlayer's `is EngineType.Kitten ->` branch
        // safe to add alongside `is EngineType.Kokoro ->`.
        @Suppress("USELESS_IS_CHECK")
        assertTrue("Kitten is not Kokoro", kitten !is EngineType.Kokoro)
        @Suppress("USELESS_IS_CHECK")
        assertTrue("Kokoro is not Kitten", kokoro !is EngineType.Kitten)
    }
}
