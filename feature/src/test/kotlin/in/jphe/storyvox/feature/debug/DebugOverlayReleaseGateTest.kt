package `in`.jphe.storyvox.feature.debug

import `in`.jphe.storyvox.feature.BuildConfig
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #529 — DebugOverlay should never render in release builds.
 *
 * The fix is two-layered:
 *  1. The legacy DataStore flag `pref_show_debug_overlay` (defaults false)
 *     still gates per-user opt-in inside debug builds.
 *  2. A compile-time `BuildConfig.DEBUG` gate at the mount site
 *     ([HybridReaderScreen.debugOverlayVisible]) ensures a stale DataStore
 *     value from a developer build can never bleed into a release shipment.
 *
 * This test pins the BuildConfig contract: when run under the `debug`
 * variant (the only variant where unit tests execute by default —
 * `testDebugUnitTest`), `BuildConfig.DEBUG` MUST be true. If a future
 * Gradle refactor accidentally sets `isDebuggable = false` on the
 * library's debug variant or drops the BuildConfig generation, this
 * test will catch it before #529 silently regresses.
 *
 * Why we don't test the `release` path here: AGP doesn't run the test
 * task against the release variant for an Android library unless you
 * explicitly add `testReleaseUnitTest` (default for libraries is debug-
 * only). The verification surface for "release gates the overlay out"
 * is the device-install step in the PR (audit must confirm the strip
 * is absent on a release APK).
 */
class DebugOverlayReleaseGateTest {

    /**
     * #529 prerequisite: BuildConfig must be generated for this module.
     * If `buildConfig = true` gets removed from `feature/build.gradle.kts`,
     * the import at the top of this file won't compile — but if AGP
     * silently changes the default + the field stops being generated,
     * this assertion is the secondary alarm.
     */
    @Test
    fun `BuildConfig DEBUG is true under debug variant unit tests`() {
        assertTrue(
            "BuildConfig.DEBUG must be true when running `testDebugUnitTest`. " +
                "If this fails, the #529 release gate (BuildConfig.DEBUG && " +
                "debugEnabled in HybridReaderScreen) is broken — release " +
                "builds would silently show the on-reader debug overlay.",
            BuildConfig.DEBUG,
        )
    }
}
