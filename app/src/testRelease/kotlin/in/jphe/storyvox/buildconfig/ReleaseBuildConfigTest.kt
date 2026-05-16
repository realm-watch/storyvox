package `in`.jphe.storyvox.buildconfig

import `in`.jphe.storyvox.BuildConfig
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Issue #529 follow-up (v0.5.58) — release-variant sanity check.
 *
 * The shipped APK comes from `:app:assembleRelease`. AGP defaults
 * `BuildConfig.DEBUG` to `isDebuggable`, and the `release` build type's
 * `isDebuggable` defaults to false — so a fresh release build MUST
 * carry `BuildConfig.DEBUG = false`. If a future refactor accidentally
 * flips the release block to debuggable, or wires up a custom
 * `BuildConfig` field that overrides DEBUG, this test fails and the
 * regression is caught before the APK ships.
 *
 * This source set is `testRelease`, so it runs only for the
 * `:app:testReleaseUnitTest` task (and the umbrella `:app:test`).
 * The matching `testDebug` source set has no equivalent assertion —
 * the debug variant's BuildConfig.DEBUG=true is AGP-default behavior
 * that doesn't need pinning, and a debug-variant test would not catch
 * a release-side regression.
 */
class ReleaseBuildConfigTest {
    @Test
    fun `release variant BuildConfig DEBUG is false`() {
        assertFalse(
            "BuildConfig.DEBUG must be false in the shipped release " +
                "variant — the build type's isDebuggable defaulted to " +
                "true, or somebody flipped it. The on-reader debug " +
                "overlay defaults OFF either way (UiSettings.showDebugOverlay " +
                "= false) but the APK label / version / DEBUG flag is " +
                "what JP files #529 about: 'no debug in filenames and " +
                "versions and stuff'.",
            BuildConfig.DEBUG,
        )
    }
}
