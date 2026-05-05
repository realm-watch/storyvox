package `in`.jphe.storyvox.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.unit.sp
import `in`.jphe.storyvox.ui.R

private val GoogleFontProvider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs,
)

private val EbGaramond = GoogleFont("EB Garamond")
private val Inter = GoogleFont("Inter")

/** Body family for chapter text — EB Garamond, optimized for long-form reading. */
val BookBodyFamily = FontFamily(
    Font(googleFont = EbGaramond, fontProvider = GoogleFontProvider, weight = FontWeight.Normal),
    Font(googleFont = EbGaramond, fontProvider = GoogleFontProvider, weight = FontWeight.Medium),
    Font(googleFont = EbGaramond, fontProvider = GoogleFontProvider, weight = FontWeight.SemiBold),
    Font(googleFont = EbGaramond, fontProvider = GoogleFontProvider, weight = FontWeight.Normal, style = FontStyle.Italic),
)

/** UI family — Inter for labels, buttons, navigation. */
val UiFamily = FontFamily(
    Font(googleFont = Inter, fontProvider = GoogleFontProvider, weight = FontWeight.Normal),
    Font(googleFont = Inter, fontProvider = GoogleFontProvider, weight = FontWeight.Medium),
    Font(googleFont = Inter, fontProvider = GoogleFontProvider, weight = FontWeight.SemiBold),
    Font(googleFont = Inter, fontProvider = GoogleFontProvider, weight = FontWeight.Bold),
)

/** Reader body — explicitly tuned for chapter text: 18sp / 28sp lh / 0.2 letter spacing. */
val ReaderBodyStyle = TextStyle(
    fontFamily = BookBodyFamily,
    fontWeight = FontWeight.Normal,
    fontSize = 18.sp,
    lineHeight = 28.sp,
    letterSpacing = 0.2.sp,
)

val LibraryNocturneTypography = Typography(
    displayLarge = TextStyle(fontFamily = BookBodyFamily, fontWeight = FontWeight.Normal, fontSize = 57.sp, lineHeight = 64.sp, letterSpacing = (-0.25).sp),
    displayMedium = TextStyle(fontFamily = BookBodyFamily, fontWeight = FontWeight.Normal, fontSize = 45.sp, lineHeight = 52.sp, letterSpacing = 0.sp),
    displaySmall = TextStyle(fontFamily = BookBodyFamily, fontWeight = FontWeight.Normal, fontSize = 36.sp, lineHeight = 44.sp, letterSpacing = 0.sp),

    headlineLarge = TextStyle(fontFamily = BookBodyFamily, fontWeight = FontWeight.SemiBold, fontSize = 32.sp, lineHeight = 40.sp, letterSpacing = 0.sp),
    headlineMedium = TextStyle(fontFamily = BookBodyFamily, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 36.sp, letterSpacing = 0.sp),
    headlineSmall = TextStyle(fontFamily = BookBodyFamily, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 32.sp, letterSpacing = 0.sp),

    titleLarge = TextStyle(fontFamily = BookBodyFamily, fontWeight = FontWeight.Medium, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = 0.sp),
    titleMedium = TextStyle(fontFamily = UiFamily, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.15.sp),
    titleSmall = TextStyle(fontFamily = UiFamily, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),

    // bodyLarge is the chapter reader style.
    bodyLarge = ReaderBodyStyle,
    bodyMedium = TextStyle(fontFamily = UiFamily, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.25.sp),
    bodySmall = TextStyle(fontFamily = UiFamily, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp),

    labelLarge = TextStyle(fontFamily = UiFamily, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontFamily = UiFamily, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp),
    labelSmall = TextStyle(fontFamily = UiFamily, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp),
)
