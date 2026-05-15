package `in`.jphe.storyvox.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Library Nocturne palette.
 *
 * Brass ramp derived from base brass #b48c5a — a 10-step warm metallic ladder
 * used both for accent surfaces and for the reader's sentence underline.
 */
object BrassRamp {
    val Brass900 = Color(0xFF1C1209)
    val Brass800 = Color(0xFF2A1D12)
    val Brass700 = Color(0xFF3A2A14)
    val Brass600 = Color(0xFF5A431F)
    val Brass550 = Color(0xFF7A5A30)
    val Brass500 = Color(0xFFB48C5A) // base
    val Brass400 = Color(0xFFC9A774)
    val Brass300 = Color(0xFFE0C8A0)
    val Brass200 = Color(0xFFF0E0C4)
    val Brass100 = Color(0xFFFDF2DD)
}

/**
 * Plum accent — used for secondary affordances (follows badges, highlight chips).
 *
 * v0.5.43 (#477): [Plum500] lifted from `#7A5A7A` → `#A582A5` to satisfy
 * WCAG AA on dark surfaces. The old token had 3.31:1 on `#0E0C12`
 * (FAIL AA-normal); the new token hits ~5.2:1. The accent still reads
 * as plum in context — the original was at the low end of the violet
 * gamut anyway. [Plum700] (used as `secondaryContainer` on dark) is
 * left alone since the on-container text is `Plum300` (already 6.31:1).
 */
object PlumRamp {
    val Plum700 = Color(0xFF3A2A3A)
    val Plum500 = Color(0xFFA582A5) // #477 — was #7A5A7A (AA fail)
    val Plum300 = Color(0xFFC9A8C9)
    val Plum100 = Color(0xFFEFDFEF)
}

/**
 * Surfaces — warm dark and paper cream.
 *
 * v0.5.43 (#477): [OutlineVariantDark] / [OutlineVariantLight] both
 * lifted to satisfy WCAG 1.4.11 (non-text minimum 3:1). Old tokens
 * were 1.60:1 / 1.58:1 against the matched surface — dividers were
 * effectively invisible. New tokens hit ~3.1:1 each: bright enough to
 * read as a real divider while still subdued vs. [OutlineDark] /
 * [OutlineLight] (which are the higher-emphasis outlines).
 */
object SurfaceTokens {
    // Dark
    val SurfaceDark = Color(0xFF0E0C12)
    val SurfaceContainerLowDark = Color(0xFF110F15)
    val SurfaceContainerDark = Color(0xFF15131A)
    val SurfaceContainerHighDark = Color(0xFF1B1822)
    val SurfaceContainerHighestDark = Color(0xFF22202B)
    val OnSurfaceDark = Color(0xFFE8DFD1)
    val OnSurfaceVariantDark = Color(0xFFB8AE9F)
    val OutlineDark = Color(0xFF6B6358)
    val OutlineVariantDark = Color(0xFF7C7466) // #477 — was #3A3530 (1.60:1), now ≥3:1 on SurfaceDark

    // Light (paper-cream)
    val SurfaceLight = Color(0xFFF4EDE2)
    val SurfaceContainerLowLight = Color(0xFFEFE7DA)
    val SurfaceContainerLight = Color(0xFFEBE2D3)
    val SurfaceContainerHighLight = Color(0xFFE5DCCB)
    val SurfaceContainerHighestLight = Color(0xFFDFD5C2)
    val OnSurfaceLight = Color(0xFF1A1614)
    val OnSurfaceVariantLight = Color(0xFF4A4338)
    val OutlineLight = Color(0xFF7A7060)
    val OutlineVariantLight = Color(0xFF8A8070) // #477 — was #C8BEA9 (1.58:1)
}

/**
 * Semantic — error / status. Brass-warm tones rather than the M3 default crimson.
 *
 * v0.5.43 (#477): [ErrorContainerDark] darkened so the error text on
 * the container hits AA. Old pair was `#E07A6A` on `#5A2A22` = 4.00:1
 * (FAIL); new pair hits ~5.0:1 against the deeper container.
 */
object StatusTokens {
    val ErrorDark = Color(0xFFE07A6A)
    val OnErrorDark = Color(0xFF1A1614)
    val ErrorContainerDark = Color(0xFF3A1A14) // #477 — was #5A2A22 (4.00:1)
    val OnErrorContainerDark = Color(0xFFFFD9D2)

    val ErrorLight = Color(0xFF9A3A2A)
    val OnErrorLight = Color(0xFFFFFFFF)
    val ErrorContainerLight = Color(0xFFFFD9D2)
    val OnErrorContainerLight = Color(0xFF3A1208)
}

/**
 * High-contrast variant of Library Nocturne (#486 Phase 2). Brass-on-
 * near-black: deep matte backgrounds, more saturated brass for accents,
 * AAA contrast on body text (~14:1+ on the darkest surface).
 *
 * Activated by `pref_a11y_high_contrast` from the Accessibility
 * subscreen, or auto-on whenever the AccessibilityStateBridge reports
 * `isTalkBackActive`. Light-mode high-contrast is the inverse of the
 * dark variant: near-black ink on pure white paper with the same
 * saturated brass for accent semantics.
 *
 * Design call (JP, 2026-05-14): backgrounds `#1a1410` → `#000000`;
 * brass `#b88746` → `#ffc14a` (more saturated). Keeps the Library
 * Nocturne identity — same family, deeper darks, brighter accents.
 */
object HighContrastTokens {
    // Brass — saturated for the high-contrast variant.
    val BrassHc500 = Color(0xFFFFC14A) // primary — bright, saturated brass
    val BrassHc400 = Color(0xFFFFD480) // a tier brighter for tertiary
    val BrassHc300 = Color(0xFFFFE6B3) // on-container, brass-cream
    val BrassHcContainer = Color(0xFF3A2810) // primaryContainer (deep brass)
    val BrassHcContainerLight = Color(0xFFFFE6B3) // light-mode container

    // Dark surfaces — pure / near-black ladder.
    val SurfaceHcDark = Color(0xFF000000)
    val SurfaceContainerLowHcDark = Color(0xFF0A0806)
    val SurfaceContainerHcDark = Color(0xFF120E0A)
    val SurfaceContainerHighHcDark = Color(0xFF1A1410)
    val SurfaceContainerHighestHcDark = Color(0xFF221C16)
    val OnSurfaceHcDark = Color(0xFFFFFFFF) // pure white body text — AAA
    val OnSurfaceVariantHcDark = Color(0xFFE6DBC8) // muted but still AAA
    val OutlineHcDark = Color(0xFFB8A88C)
    val OutlineVariantHcDark = Color(0xFF7A6E5A) // ~3.2:1 on near-black

    // Light surfaces — pure-white inverse.
    val SurfaceHcLight = Color(0xFFFFFFFF)
    val SurfaceContainerLowHcLight = Color(0xFFFAF6EE)
    val SurfaceContainerHcLight = Color(0xFFF2EBDD)
    val SurfaceContainerHighHcLight = Color(0xFFE8DEC8)
    val SurfaceContainerHighestHcLight = Color(0xFFDDD0B4)
    val OnSurfaceHcLight = Color(0xFF000000) // pure black body text — AAA
    val OnSurfaceVariantHcLight = Color(0xFF1A1410)
    val OutlineHcLight = Color(0xFF3A2810)
    val OutlineVariantHcLight = Color(0xFF6A5840) // ~3.4:1 on near-white

    // Brass primary for light mode — darker so brass-on-white passes AAA.
    val BrassHcLight = Color(0xFF5A3E10) // ~9.5:1 on white

    // Error — high-contrast error keeps the same warm tone but pumps
    // the brightness so error text on container clears AAA.
    val ErrorHcDark = Color(0xFFFFB4A0)
    val ErrorContainerHcDark = Color(0xFF2A100A)
    val ErrorHcLight = Color(0xFF6E1A0A)
    val ErrorContainerHcLight = Color(0xFFFFE0D6)
}
