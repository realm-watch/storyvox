package `in`.jphe.storyvox.ui.component

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * v0.5.59 — book-cover fallback style mirror, kept in `:core-ui` so
 * [FictionCoverThumb] can branch on the user preference without
 * pulling in `:feature/api`.
 *
 * Values are kept in lock-step with
 * `in.jphe.storyvox.feature.api.CoverStyle`. MainActivity provides
 * this CompositionLocal from `pref_cover_style`; the default is
 * [Monogram] — the JP-preferred classic minimalist sigil tile, which
 * matches the pre-v0.5.51 visual and is the new install default.
 *
 * If you add a value here, add it to `feature.api.CoverStyle` too (and
 * to the String mapping in `SettingsRepositoryUiImpl.coverStyle`).
 */
enum class CoverStyleLocal {
    /** Classic minimalist sigil + author initial on dark. JP's
     *  preference, the new install default, and the visual revert
     *  for users upgrading from v0.5.51..v0.5.58. */
    Monogram,

    /** v0.5.51 BrandedCoverTile — warm gradient, sun-disk watermark,
     *  EB-Garamond title, brass border. Bold and magical; some
     *  users (and JP's audit) found it visually overwrites the
     *  "this is a fallback, not the real cover" affordance. */
    Branded,

    /** Real cover when one loads; a dim brass-ring outline placeholder
     *  otherwise. No title, no monogram. For users who want a strict
     *  "show the cover or show nothing salient" mode. */
    CoverOnly,
}

/**
 * Effective cover-style for the current composition. Defaults to
 * [CoverStyleLocal.Monogram] for previews + tests that don't wire a
 * provider — same default the DataStore-backed pref has.
 */
val LocalCoverStyle = staticCompositionLocalOf { CoverStyleLocal.Monogram }
