package `in`.jphe.storyvox.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

/** Library Nocturne motion — slow, warm, deliberate. */
@Immutable
data class Motion(
    /** Standard 280ms with cubic in-out — page transitions, surface swaps. */
    val standardEasing: Easing = CubicBezierEasing(0.4f, 0.0f, 0.2f, 1.0f),
    val standardDurationMs: Int = 280,

    /** Sentence-highlight 180ms — must keep up with TTS rate. */
    val sentenceEasing: Easing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f),
    val sentenceDurationMs: Int = 180,

    /** Reader<->Audiobook swipe — warmer, with overshoot tolerance. */
    val swipeEasing: Easing = CubicBezierEasing(0.32f, 0.72f, 0.0f, 1.0f),
    val swipeDurationMs: Int = 360,
)

val LocalMotion = staticCompositionLocalOf { Motion() }

/**
 * Whether the user has "Remove animations" / "Reduce motion" enabled in
 * system settings. Provided at the app root by reading
 * `Settings.Global.ANIMATOR_DURATION_SCALE`.
 *
 * This is a runtime user-preference flag, deliberately kept separate from
 * [Motion]: [Motion] is `@Immutable` and describes the realm's motion
 * vocabulary, while this flag tells consumers whether to play any motion at
 * all right now. Nesting it inside [Motion] would silently break stable-
 * snapshot semantics.
 *
 * Reduced motion means *absent* motion, not shorter motion — consumers
 * should branch to instant transitions, not faster ones.
 *
 * Default `false` is a safe fallback for previews and tests that don't wire
 * a provider.
 */
val LocalReducedMotion = staticCompositionLocalOf { false }
