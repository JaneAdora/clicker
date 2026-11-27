package com.janeadora.clicker.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.janeadora.clicker.core.ThemeMode

/** Shapes: controls fully round, panels and sheets 22dp, fields 8dp. Borders are 1dp [ClickerColors.outline]. */
object ClickerShapes {
    val control = CircleShape
    val panel = RoundedCornerShape(22.dp)
    val field = RoundedCornerShape(8.dp)
    val border = 1.dp
}

/**
 * The Rep Cap Cultivated theme. [mode] picks light or dark; System follows the
 * phone. Material components inherit the palette through MaterialTheme.
 * Dynamic (wallpaper) color is never used.
 */
@Composable
fun ClickerTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val colors = if (dark) DarkColors else LightColors
    CompositionLocalProvider(LocalClickerColors provides colors) {
        MaterialTheme(
            colorScheme = colors.toColorScheme(dark),
            typography = ClickerTypography,
            shapes = MaterialShapes,
            content = content,
        )
    }
}

private val MaterialShapes = Shapes(
    extraSmall = ClickerShapes.field,
    small = ClickerShapes.field,
    medium = ClickerShapes.panel,
    large = ClickerShapes.panel,
    extraLarge = ClickerShapes.panel,
)

private fun ClickerColors.toColorScheme(dark: Boolean): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = surfaceRaised,
        onPrimaryContainer = heading,
        inversePrimary = accentPressed,
        secondary = accentPressed,
        onSecondary = onAccent,
        secondaryContainer = surfaceRaised,
        onSecondaryContainer = heading,
        tertiary = accentPressed,
        onTertiary = onAccent,
        tertiaryContainer = surfaceRaised,
        onTertiaryContainer = heading,
        background = surface,
        onBackground = onSurface,
        surface = surface,
        onSurface = onSurface,
        surfaceVariant = surfaceContainer,
        onSurfaceVariant = muted,
        // No tint: depth comes from the tonal steps below, not from elevation.
        surfaceTint = Color.Transparent,
        inverseSurface = onSurface,
        inverseOnSurface = surface,
        // Errors stay in the brand palette; the message text carries the meaning.
        error = accent,
        onError = onAccent,
        errorContainer = surfaceRaised,
        onErrorContainer = heading,
        outline = outlineStrong,
        outlineVariant = outline,
        surfaceBright = surfaceRaised,
        surfaceDim = surface,
        surfaceContainerLowest = surface,
        surfaceContainerLow = surfaceContainer,
        surfaceContainer = surfaceContainer,
        surfaceContainerHigh = surfaceRaised,
        surfaceContainerHighest = surfaceRaised,
    )
}
