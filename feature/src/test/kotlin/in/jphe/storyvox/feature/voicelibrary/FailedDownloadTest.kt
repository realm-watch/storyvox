package `in`.jphe.storyvox.feature.voicelibrary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Issue #541 / #548 — regression guards for the per-voice failed-download
 * state. The data shape carries the upstream reason verbatim and the
 * last-seen progress fraction so the row tile can render
 * "Tap to retry · stopped at X%" subtitle copy.
 *
 * The ViewModel's actual download() coroutine is mid-loop suspend-call
 * heavy and not easily isolated for a JVM test; this file tests the
 * data-shape invariants the screen relies on.
 */
class FailedDownloadTest {

    @Test
    fun `default state has empty failed map`() {
        val state = VoiceLibraryUiState()
        assertEquals(emptyMap<String, FailedDownload>(), state.failedDownloads)
    }

    @Test
    fun `failed entry survives currentDownload going back to null`() {
        // The user-visible bug from #541: when the download terminates
        // in Failed, `_currentDownload = null` cleared the row's
        // progress bar but left no record of what went wrong. The new
        // shape stores the failure in a separate map so the row
        // continues to render the diagnostic + retry tile even after
        // currentDownload reverts to null.
        val failed = FailedDownload(
            voiceId = "piper-en_US-amy",
            reason = "Read timed out",
            lastProgress = 0.37f,
        )
        val state = VoiceLibraryUiState(
            currentDownload = null,
            failedDownloads = mapOf("piper-en_US-amy" to failed),
        )
        assertEquals(failed, state.failedDownloads["piper-en_US-amy"])
        assertNull(state.currentDownload)
    }

    @Test
    fun `lastProgress can be null for failures that never received a determinate fraction`() {
        // Resolving → Failed (manifest fetch died with no HEAD probe
        // response) — lastProgress is null because we never saw a
        // Downloading frame with a Content-Length. The tile must still
        // render gracefully.
        val failed = FailedDownload(
            voiceId = "kokoro-shared",
            reason = "HTTP 404 fetching https://example.com/model.onnx",
            lastProgress = null,
        )
        assertNull(failed.lastProgress)
        assertEquals("kokoro-shared", failed.voiceId)
    }

    @Test
    fun `multiple voices can fail independently`() {
        // Each voice has its own slot in the map — a failure on one
        // doesn't displace the failure record on another. Important
        // because the user may try voice A, see it fail, then try
        // voice B before retrying A. Both rows should still surface
        // their respective diagnostics.
        val a = FailedDownload("piper-amy", "HTTP 503", 0.5f)
        val b = FailedDownload("kokoro", "Read timed out", null)
        val state = VoiceLibraryUiState(
            failedDownloads = mapOf(a.voiceId to a, b.voiceId to b),
        )
        assertEquals(2, state.failedDownloads.size)
        assertEquals(a, state.failedDownloads["piper-amy"])
        assertEquals(b, state.failedDownloads["kokoro"])
    }
}
