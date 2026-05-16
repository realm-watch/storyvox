package `in`.jphe.storyvox.baselineprofile

/**
 * Issue #618 — cold-launch performance thresholds enforced by CI.
 *
 * The numbers below are the *failure thresholds*: a cold-launch
 * median above these values means the v1.0 perf budget has been blown
 * and the build should fail. Read off the StartupBenchmark
 * `StartupTimingMetric` median across 5 iterations.
 *
 * Targets:
 *  - **tablet (Tab A7 Lite, R83W80CAFZB)**: 1500 ms
 *  - **phone (Pixel-class, R5CRB0W66MK / Flip3)**: 500 ms
 *
 * The phone budget is tighter because the v0.5.45 baseline-profile
 * work already pulled phone cold-launch down to ~280 ms; we don't
 * want a regression to silently consume that headroom. The tablet
 * budget is more generous because the Tab A7 Lite is single-CPU and
 * the engine warmup overhead dominates anything < 1 s.
 *
 * **How the guard runs in CI**: PR runs assemble the release APK on
 * the katana self-hosted runner; release tag runs additionally invoke
 * `:baselineprofile:connectedBenchmarkAndroidTest`, which writes JSON
 * reports under `baselineprofile/build/outputs/connected_android_test_additional_output/`.
 * The `scripts/check-cold-launch.sh` script reads those reports and
 * exits non-zero if any threshold is exceeded. The script is wired
 * into android.yml as a release-only step (so PRs don't need an
 * attached device).
 *
 * Exposed as compile-time constants so [ColdLaunchThresholdsTest] can
 * pin them, and so the CI script doesn't have to hard-code the
 * numbers in two places — it parses them out of this file.
 */
object ColdLaunchThresholds {
    /** Tablet baseline (Tab A7 Lite). Cold-launch median must be < this. */
    const val TABLET_BUDGET_MS: Long = 1_500L

    /** Phone baseline (Flip3-class). Cold-launch median must be < this. */
    const val PHONE_BUDGET_MS: Long = 500L
}
