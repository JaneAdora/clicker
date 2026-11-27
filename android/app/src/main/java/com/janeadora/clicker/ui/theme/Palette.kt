package com.janeadora.clicker.ui.theme

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Color roles for Rep Cap Cultivated. No hex outside this file.
 *
 * [dotGround] holds the ground dot inks from small to large. [dotMark] holds
 * the inks for marks (chevrons, the link-state dot, press feedback).
 *
 * The remote body adds roles built from the same hexes: [shell]
 * is the body's face, [shellBack] the offset shell behind it and
 * [shellBackLine] that shell's hairline, [bay] a
 * recessed compartment, [key] a raised key's face. In both modes the key is
 * the lightest of the three and the compartment the darkest. The night
 * display stays dark in both modes: [night] ground, [nightLine] for meter
 * bars that are not lit, [onNight] text, [nightMuted] labels and
 * [nightAccent] for the live figure and the lit bars.
 */
data class ClickerColors(
    val surface: Color,
    val surfaceContainer: Color,
    val surfaceRaised: Color,
    val onSurface: Color,
    val heading: Color,
    val muted: Color,
    val accent: Color,
    val accentPressed: Color,
    val onAccent: Color,
    val outline: Color,
    val outlineStrong: Color,
    val focus: Color,
    val dotGround: List<Color>,
    val dotMark: List<Color>,
    val shell: Color,
    val shellBack: Color,
    val shellBackLine: Color,
    val bay: Color,
    val key: Color,
    val night: Color = Color(0xFF0E0917),
    val nightLine: Color = Color(0xFF3A2E52),
    val onNight: Color = Color(0xFFF0EAF8),
    val nightMuted: Color = Color(0xFFB8ADD0),
    val nightAccent: Color = Color(0xFFEE9ED9),
)

val DarkColors: ClickerColors = ClickerColors(
    surface = Color(0xFF0E0917),
    surfaceContainer = Color(0xFF1E1730),
    surfaceRaised = Color(0xFF2A2040),
    onSurface = Color(0xFFF0EAF8),
    heading = Color(0xFFF0EAF8),
    muted = Color(0xFFB8ADD0),
    accent = Color(0xFFEE9ED9),
    accentPressed = Color(0xFFCF9DF2),
    onAccent = Color(0xFF151020),
    outline = Color(0xFF3A2E52),
    outlineStrong = Color(0xFF6B5C88),
    focus = Color(0xFFCF9DF2),
    dotGround = listOf(Color(0xFF2A2040), Color(0xFF3A2350), Color(0xFF5E2E73)),
    dotMark = listOf(Color(0xFFC084D8), Color(0xFFCF9DF2), Color(0xFFEE9ED9)),
    shell = Color(0xFF151020),
    shellBack = Color(0xFF151020),
    shellBackLine = Color(0xFF3A2E52),
    bay = Color(0xFF0E0917),
    key = Color(0xFF1E1730),
)

val LightColors: ClickerColors = ClickerColors(
    surface = Color(0xFFF3EEF9),
    surfaceContainer = Color(0xFFE9E1F3),
    surfaceRaised = Color(0xFFDDD2EB),
    onSurface = Color(0xFF1A1326),
    heading = Color(0xFF3B2A55),
    muted = Color(0xFF4E4064),
    accent = Color(0xFF6F3A9A),
    accentPressed = Color(0xFF5E2E73),
    onAccent = Color(0xFFF3EEF9),
    outline = Color(0xFFCFC2E0),
    outlineStrong = Color(0xFF85769C),
    focus = Color(0xFF5E2E73),
    dotGround = listOf(Color(0xFFE4DAF0), Color(0xFFD6C9E8), Color(0xFFCFC2E0)),
    dotMark = listOf(Color(0xFFC084D8), Color(0xFF6F3A9A), Color(0xFF5E2E73)),
    shell = Color(0xFFE9E1F3),
    shellBack = Color(0xFFDDD2EB),
    shellBackLine = Color(0xFFCFC2E0),
    bay = Color(0xFFDDD2EB),
    key = Color(0xFFF3EEF9),
)

val LocalClickerColors: ProvidableCompositionLocal<ClickerColors> =
    staticCompositionLocalOf { DarkColors }

/** WCAG 2.x contrast ratio between two opaque sRGB colors, from 1 to 21. */
fun contrast(a: Color, b: Color): Double {
    val la = relativeLuminance(a)
    val lb = relativeLuminance(b)
    return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
}

private fun relativeLuminance(c: Color): Double {
    fun channel(v: Float): Double {
        val x = v.toDouble()
        return if (x <= 0.04045) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
}
