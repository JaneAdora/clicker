package com.janeadora.clicker.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.janeadora.clicker.R

// Both families ship as variable fonts. Each weight sets the wght axis
// itself, so 400, 500 and 600 render as distinct weights.
private fun anek(weight: Int) = Font(
    R.font.anek_gurmukhi,
    FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight), FontVariation.width(100f)),
)

private fun fira(weight: Int) = Font(
    R.font.fira_code,
    FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val AnekGurmukhi: FontFamily = FontFamily(anek(400), anek(500), anek(600))
val FiraCode: FontFamily = FontFamily(fira(400), fira(500), fira(600))

/**
 * Anek sits high in its box: in a centred label the capitals read about
 * 0.19em above centre (measured on the emulator at 17sp). Add twice this,
 * times the font size, as top padding to centre them optically.
 */
const val ANEK_CAP_LIFT_EM = 0.19f

private val centred = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

object ClickerType {
    val title = TextStyle(
        fontFamily = AnekGurmukhi,
        fontWeight = FontWeight(600),
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.012).em,
        lineHeightStyle = centred,
    )
    val button = TextStyle(
        fontFamily = AnekGurmukhi,
        fontWeight = FontWeight(600),
        fontSize = 17.sp,
        lineHeight = 22.sp,
        lineHeightStyle = centred,
    )
    val body = TextStyle(
        fontFamily = AnekGurmukhi,
        fontWeight = FontWeight(400),
        fontSize = 18.sp,
        lineHeight = 26.sp,
        lineHeightStyle = centred,
    )
    val label = TextStyle(
        fontFamily = AnekGurmukhi,
        fontWeight = FontWeight(600),
        fontSize = 14.sp,
        lineHeight = 18.sp,
        lineHeightStyle = centred,
    )
    val status = TextStyle(
        fontFamily = FiraCode,
        fontWeight = FontWeight(400),
        fontSize = 13.sp,
        lineHeight = 18.sp,
        lineHeightStyle = centred,
    )
    /** The night display's readout: mono capitals, spaced. */
    val readout = TextStyle(
        fontFamily = FiraCode,
        fontWeight = FontWeight(500),
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.06.em,
        lineHeightStyle = centred,
    )
    /** The nameplate and labels on keys: small mono capitals, spaced. */
    val plate = TextStyle(
        fontFamily = FiraCode,
        fontWeight = FontWeight(500),
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.08.em,
        lineHeightStyle = centred,
    )
    val pin = TextStyle(
        fontFamily = FiraCode,
        fontWeight = FontWeight(500),
        fontSize = 28.sp,
        lineHeight = 34.sp,
        lineHeightStyle = centred,
    )
}

/** Material's type scale on Anek Gurmukhi, so stock components match. */
internal val ClickerTypography: Typography = Typography().let { t ->
    Typography(
        displayLarge = t.displayLarge.copy(fontFamily = AnekGurmukhi),
        displayMedium = t.displayMedium.copy(fontFamily = AnekGurmukhi),
        displaySmall = t.displaySmall.copy(fontFamily = AnekGurmukhi),
        headlineLarge = t.headlineLarge.copy(fontFamily = AnekGurmukhi),
        headlineMedium = t.headlineMedium.copy(fontFamily = AnekGurmukhi),
        headlineSmall = t.headlineSmall.copy(fontFamily = AnekGurmukhi),
        titleLarge = ClickerType.title,
        titleMedium = t.titleMedium.copy(fontFamily = AnekGurmukhi, fontWeight = FontWeight(600)),
        titleSmall = t.titleSmall.copy(fontFamily = AnekGurmukhi, fontWeight = FontWeight(600)),
        bodyLarge = ClickerType.body,
        bodyMedium = t.bodyMedium.copy(fontFamily = AnekGurmukhi),
        bodySmall = t.bodySmall.copy(fontFamily = AnekGurmukhi),
        labelLarge = ClickerType.button,
        labelMedium = ClickerType.label,
        labelSmall = ClickerType.status,
    )
}
