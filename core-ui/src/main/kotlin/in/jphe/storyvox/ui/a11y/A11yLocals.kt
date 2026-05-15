package `in`.jphe.storyvox.ui.a11y

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Accessibility scaffold Phase 2 (#486, v0.5.43) — chapter-header
 * read-out preference, mirrored into `:core-ui` so [ChapterCard] (and
 * any other widget that builds a "Chapter N, title, duration"
 * content-description) can branch without depending on `:feature`.
 *
 * MainActivity provides this from `pref_a11y_speak_chapter_mode`.
 * Defaults to [Both] for previews and tests that don't wire a
 * provider — same default the DataStore-backed pref has.
 *
 * The values are kept in sync with `feature.api.SpeakChapterMode`;
 * see [`AccessibilitySettingsScreen`] for the user-facing radio that
 * writes this. If you add a value here, add it there too (and to the
 * String mapping in `SettingsRepositoryUiImpl.a11ySpeakChapterMode`).
 */
enum class A11ySpeakChapterMode {
    Both,
    NumbersOnly,
    TitlesOnly,
}

val LocalA11ySpeakChapterMode = staticCompositionLocalOf { A11ySpeakChapterMode.Both }

/**
 * Whether the user has TalkBack (or any equivalent screen-reader)
 * active right now. Mirrored from `AccessibilityStateBridge` by
 * MainActivity. Defaults to false so non-wiring contexts (previews,
 * tests) skip the screen-reader branches and render the default
 * audiobook-tuned content descriptions.
 *
 * Use this for content-description branching where the long-form
 * description is helpful for TalkBack users but verbose for a sighted
 * user reading the same row in the regular Compose UI.
 */
val LocalIsTalkBackActive = staticCompositionLocalOf { false }
