package `in`.jphe.storyvox.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

private val DarkColors = darkColorScheme(
    primary = BrassRamp.Brass500,
    onPrimary = SurfaceTokens.SurfaceDark,
    primaryContainer = BrassRamp.Brass800,
    onPrimaryContainer = BrassRamp.Brass200,
    inversePrimary = BrassRamp.Brass400,

    secondary = PlumRamp.Plum500,
    onSecondary = SurfaceTokens.SurfaceDark,
    secondaryContainer = PlumRamp.Plum700,
    onSecondaryContainer = PlumRamp.Plum300,

    tertiary = BrassRamp.Brass400,
    onTertiary = SurfaceTokens.SurfaceDark,
    tertiaryContainer = BrassRamp.Brass700,
    onTertiaryContainer = BrassRamp.Brass200,

    background = SurfaceTokens.SurfaceDark,
    onBackground = SurfaceTokens.OnSurfaceDark,

    surface = SurfaceTokens.SurfaceDark,
    onSurface = SurfaceTokens.OnSurfaceDark,
    surfaceVariant = SurfaceTokens.SurfaceContainerHighDark,
    onSurfaceVariant = SurfaceTokens.OnSurfaceVariantDark,
    surfaceTint = BrassRamp.Brass500,
    inverseSurface = SurfaceTokens.SurfaceLight,
    inverseOnSurface = SurfaceTokens.OnSurfaceLight,

    surfaceContainerLowest = SurfaceTokens.SurfaceDark,
    surfaceContainerLow = SurfaceTokens.SurfaceContainerLowDark,
    surfaceContainer = SurfaceTokens.SurfaceContainerDark,
    surfaceContainerHigh = SurfaceTokens.SurfaceContainerHighDark,
    surfaceContainerHighest = SurfaceTokens.SurfaceContainerHighestDark,

    outline = SurfaceTokens.OutlineDark,
    outlineVariant = SurfaceTokens.OutlineVariantDark,

    error = StatusTokens.ErrorDark,
    onError = StatusTokens.OnErrorDark,
    errorContainer = StatusTokens.ErrorContainerDark,
    onErrorContainer = StatusTokens.OnErrorContainerDark,

    scrim = SurfaceTokens.SurfaceDark,
)

private val LightColors = lightColorScheme(
    primary = BrassRamp.Brass550,
    onPrimary = SurfaceTokens.SurfaceLight,
    primaryContainer = BrassRamp.Brass200,
    onPrimaryContainer = BrassRamp.Brass900,
    inversePrimary = BrassRamp.Brass500,

    secondary = PlumRamp.Plum500,
    onSecondary = SurfaceTokens.SurfaceLight,
    secondaryContainer = PlumRamp.Plum100,
    onSecondaryContainer = PlumRamp.Plum700,

    tertiary = BrassRamp.Brass550,
    onTertiary = SurfaceTokens.SurfaceLight,
    tertiaryContainer = BrassRamp.Brass200,
    onTertiaryContainer = BrassRamp.Brass900,

    background = SurfaceTokens.SurfaceLight,
    onBackground = SurfaceTokens.OnSurfaceLight,

    surface = SurfaceTokens.SurfaceLight,
    onSurface = SurfaceTokens.OnSurfaceLight,
    surfaceVariant = SurfaceTokens.SurfaceContainerHighLight,
    onSurfaceVariant = SurfaceTokens.OnSurfaceVariantLight,
    surfaceTint = BrassRamp.Brass550,
    inverseSurface = SurfaceTokens.SurfaceDark,
    inverseOnSurface = SurfaceTokens.OnSurfaceDark,

    surfaceContainerLowest = SurfaceTokens.SurfaceLight,
    surfaceContainerLow = SurfaceTokens.SurfaceContainerLowLight,
    surfaceContainer = SurfaceTokens.SurfaceContainerLight,
    surfaceContainerHigh = SurfaceTokens.SurfaceContainerHighLight,
    surfaceContainerHighest = SurfaceTokens.SurfaceContainerHighestLight,

    outline = SurfaceTokens.OutlineLight,
    outlineVariant = SurfaceTokens.OutlineVariantLight,

    error = StatusTokens.ErrorLight,
    onError = StatusTokens.OnErrorLight,
    errorContainer = StatusTokens.ErrorContainerLight,
    onErrorContainer = StatusTokens.OnErrorContainerLight,

    scrim = SurfaceTokens.OnSurfaceDark,
)

/**
 * Library Nocturne — the bookish, brass-on-warm-dark theme.
 *
 * Wraps [MaterialTheme] and provides Library Nocturne spacing/motion CompositionLocals.
 */
@Composable
fun LibraryNocturneTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    CompositionLocalProvider(
        LocalSpacing provides Spacing(),
        LocalMotion provides Motion(),
    ) {
        MaterialTheme(
            colorScheme = colors,
            typography = LibraryNocturneTypography,
            shapes = LibraryNocturneShapes,
            content = content,
        )
    }
}
