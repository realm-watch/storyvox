package `in`.jphe.storyvox.sigil

import `in`.jphe.storyvox.BuildConfig

/**
 * v0.5.00 milestone gate — the "graduation party" version.
 *
 * storyvox crossed from 0.4.x into 0.5.00 with a wave of UX work
 * (Voice Library, debug overlay, chapter auto-advance, settings
 * overhaul, ~13 Reeve fixes). The milestone has two paired surfaces:
 *  - a one-time "thank you" dialog on first launch of a qualifying
 *    build (gated by `KEY_V0500_MILESTONE_SEEN` in DataStore),
 *  - a one-time confetti easter-egg on the first natural chapter
 *    completion after install (gated by `KEY_V0500_CONFETTI_SHOWN`).
 *
 * Both surfaces are inert on builds *below* 0.5.00 — the version
 * comparison short-circuits and the flags never flip. A future
 * 0.6.00 / 1.0.0 milestone gets its own helper + its own DataStore
 * keys; we don't reuse this gate across versions because the copy
 * is hand-tuned per release.
 *
 * Version parsing is deliberately simple: split on `.`, parse the
 * first three integer components, default missing tails to 0. Any
 * parse failure (dev build sigil sneaks something weird in,
 * pre-release suffix appears) returns false — fail-closed so the
 * dialog never accidentally pops on a non-release artifact.
 */
object Milestone {

    /** First version that qualifies for the v0.5.00 celebration. */
    private val V0500 = Triple(0, 5, 0)

    /** True when the current build's [BuildConfig.VERSION_NAME] is
     *  greater than or equal to the v0.5.00 threshold. Cached for the
     *  process lifetime — VERSION_NAME doesn't change at runtime. */
    val isV0500OrLater: Boolean by lazy {
        qualifies(BuildConfig.VERSION_NAME)
    }

    /** Visible for testing — parse + compare without touching
     *  BuildConfig. Returns false on any parse failure. */
    internal fun qualifies(versionName: String): Boolean {
        val parts = versionName.split('.', '-', '+')
        val major = parts.getOrNull(0)?.toIntOrNull() ?: return false
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
        return compareTriple(Triple(major, minor, patch), V0500) >= 0
    }

    private fun compareTriple(a: Triple<Int, Int, Int>, b: Triple<Int, Int, Int>): Int {
        if (a.first != b.first) return a.first.compareTo(b.first)
        if (a.second != b.second) return a.second.compareTo(b.second)
        return a.third.compareTo(b.third)
    }
}
