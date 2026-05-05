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

/** Plum accent — used for secondary affordances (follows badges, highlight chips). */
object PlumRamp {
    val Plum700 = Color(0xFF3A2A3A)
    val Plum500 = Color(0xFF7A5A7A)
    val Plum300 = Color(0xFFC9A8C9)
    val Plum100 = Color(0xFFEFDFEF)
}

/** Surfaces — warm dark and paper cream. */
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
    val OutlineVariantDark = Color(0xFF3A3530)

    // Light (paper-cream)
    val SurfaceLight = Color(0xFFF4EDE2)
    val SurfaceContainerLowLight = Color(0xFFEFE7DA)
    val SurfaceContainerLight = Color(0xFFEBE2D3)
    val SurfaceContainerHighLight = Color(0xFFE5DCCB)
    val SurfaceContainerHighestLight = Color(0xFFDFD5C2)
    val OnSurfaceLight = Color(0xFF1A1614)
    val OnSurfaceVariantLight = Color(0xFF4A4338)
    val OutlineLight = Color(0xFF7A7060)
    val OutlineVariantLight = Color(0xFFC8BEA9)
}

/** Semantic — error / status. Brass-warm tones rather than the M3 default crimson. */
object StatusTokens {
    val ErrorDark = Color(0xFFE07A6A)
    val OnErrorDark = Color(0xFF1A1614)
    val ErrorContainerDark = Color(0xFF5A2A22)
    val OnErrorContainerDark = Color(0xFFFFD9D2)

    val ErrorLight = Color(0xFF9A3A2A)
    val OnErrorLight = Color(0xFFFFFFFF)
    val ErrorContainerLight = Color(0xFFFFD9D2)
    val OnErrorContainerLight = Color(0xFF3A1208)
}
