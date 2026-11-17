package com.varizpardaz.listener.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.varizpardaz.listener.app.R

// Tokens follow the "Neutral Trust" design system in docs/design/stitch-index.md: teal primary, slate
// neutrals, 12 dp radius, light only (no dark mode in phase 1).
private val Teal = Color(0xFF0F766E)
private val Slate900 = Color(0xFF0F172A)
private val Slate600 = Color(0xFF475569)
private val Slate100 = Color(0xFFF1F5F9)

private val LightColors = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    background = Color.White,
    onBackground = Slate900,
    surface = Color.White,
    onSurface = Slate900,
    surfaceVariant = Slate100,
    onSurfaceVariant = Slate600,
)

val Vazirmatn = FontFamily(
    Font(R.font.vazirmatn_regular, FontWeight.Normal),
    Font(R.font.vazirmatn_medium, FontWeight.Medium),
    Font(R.font.vazirmatn_bold, FontWeight.Bold),
)

private fun Typography.withFont(family: FontFamily) = copy(
    displayLarge = displayLarge.withFont(family),
    displayMedium = displayMedium.withFont(family),
    displaySmall = displaySmall.withFont(family),
    headlineLarge = headlineLarge.withFont(family),
    headlineMedium = headlineMedium.withFont(family),
    headlineSmall = headlineSmall.withFont(family),
    titleLarge = titleLarge.withFont(family),
    titleMedium = titleMedium.withFont(family),
    titleSmall = titleSmall.withFont(family),
    bodyLarge = bodyLarge.withFont(family),
    bodyMedium = bodyMedium.withFont(family),
    bodySmall = bodySmall.withFont(family),
    labelLarge = labelLarge.withFont(family),
    labelMedium = labelMedium.withFont(family),
    labelSmall = labelSmall.withFont(family),
)

private fun TextStyle.withFont(family: FontFamily) = copy(fontFamily = family)

private val CornerShape = RoundedCornerShape(12.dp)

/** The app is Persian-only, so layout is always right-to-left whatever the phone's language is. */
@Composable
fun ListenerTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(
            colorScheme = LightColors,
            typography = Typography().withFont(Vazirmatn),
            shapes = Shapes(small = CornerShape, medium = CornerShape, large = CornerShape),
            content = content,
        )
    }
}
