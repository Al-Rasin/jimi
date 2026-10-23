package com.alrasin.jarvis.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.LocalContentColor
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alrasin.jarvis.R

/**
 * Warm graphite + amber (D-069): soft dark grey (not pure black), warm off-white text, one amber accent.
 * Amber is for "alive / chosen" things only (orb, glow, selected item, mic button); everything else is warm greys.
 */
object JimiColors {
    val Background = Color(0xFF121212)
    val Surface1 = Color(0xFF1C1C1E)
    val Surface2 = Color(0xFF242426)
    val Surface3 = Color(0xFF2E2D2B)
    val Outline = Color(0xFF2C2B29)
    val OutlineStrong = Color(0xFF3B3936)
    val Accent = Color(0xFFF2A541)
    val OnAccent = Color(0xFF1E1408)
    val AccentDeep = Color(0xFFC77A1C)
    val AccentLight = Color(0xFFFFD08A)
    val Text = Color(0xFFF2EFEA)
    val SecondaryText = Color(0xFF8E8A85)
    val TertiaryText = Color(0xFF64605B)
    val Error = Color(0xFFFF7A6B)
    val Success = Color(0xFF7FCF95)
    val Warning = Color(0xFFE8C468)
}

val JimiDisplay = FontFamily(
    Font(R.font.space_grotesk_medium, FontWeight.Medium),
    Font(R.font.space_grotesk_semibold, FontWeight.SemiBold),
)
val JimiBody = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
)
val JimiMono = FontFamily(Font(R.font.jetbrains_mono_regular, FontWeight.Normal))

// one font family everywhere (calmer than mixing); big text gets tighter letter spacing
private val displayStyle = TextStyle(fontFamily = JimiBody, fontWeight = FontWeight.Medium, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-0.8).sp)
private val titleStyle = TextStyle(fontFamily = JimiBody, fontWeight = FontWeight.Medium, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.3).sp)
private val bodyStyle = TextStyle(fontFamily = JimiBody, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp)
private val bodyMediumStyle = bodyStyle.copy(fontSize = 15.sp, lineHeight = 22.sp)
// small text is always helper text, so it is always the quiet colour
private val bodySmallStyle = bodyStyle.copy(fontSize = 13.sp, lineHeight = 19.sp, color = JimiColors.SecondaryText)
private val labelStyle = TextStyle(fontFamily = JimiBody, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 20.sp, letterSpacing = 0.2.sp)
private val labelSmallStyle = labelStyle.copy(fontSize = 12.sp, lineHeight = 16.sp)
private val cardTitleStyle = titleStyle.copy(fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = 0.sp)
private val jimiTypography = Typography(
    displayLarge = displayStyle, displayMedium = displayStyle, displaySmall = displayStyle,
    headlineLarge = displayStyle, headlineMedium = titleStyle, headlineSmall = titleStyle,
    titleLarge = titleStyle, titleMedium = titleStyle, titleSmall = cardTitleStyle,
    bodyLarge = bodyStyle, bodyMedium = bodyMediumStyle, bodySmall = bodySmallStyle,
    labelLarge = labelStyle, labelMedium = labelStyle, labelSmall = labelSmallStyle,
)

@Composable
fun JimiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = JimiColors.Accent,
            onPrimary = JimiColors.OnAccent,
            primaryContainer = JimiColors.AccentDeep,
            onPrimaryContainer = JimiColors.Text,
            inversePrimary = JimiColors.AccentDeep,
            secondary = JimiColors.Accent,
            onSecondary = JimiColors.OnAccent,
            secondaryContainer = JimiColors.Surface3,
            onSecondaryContainer = JimiColors.Text,
            tertiary = JimiColors.Accent,
            onTertiary = JimiColors.OnAccent,
            tertiaryContainer = JimiColors.Surface3,
            onTertiaryContainer = JimiColors.Text,
            background = JimiColors.Background,
            onBackground = JimiColors.Text,
            surface = JimiColors.Surface1,
            onSurface = JimiColors.Text,
            surfaceVariant = JimiColors.Surface3,
            onSurfaceVariant = JimiColors.SecondaryText,
            surfaceTint = Color.Transparent,
            surfaceDim = JimiColors.Background,
            surfaceBright = JimiColors.Surface3,
            surfaceContainerLowest = JimiColors.Background,
            surfaceContainerLow = JimiColors.Surface1,
            surfaceContainer = JimiColors.Surface1,
            surfaceContainerHigh = JimiColors.Surface2,
            surfaceContainerHighest = JimiColors.Surface3,
            inverseSurface = JimiColors.Text,
            inverseOnSurface = JimiColors.Background,
            outline = JimiColors.OutlineStrong,
            outlineVariant = JimiColors.Outline,
            error = JimiColors.Error,
            onError = JimiColors.Background,
            errorContainer = JimiColors.Surface3,
            onErrorContainer = JimiColors.Error,
            scrim = Color.Black,
        ),
        typography = jimiTypography,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(12.dp),
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(20.dp),
            large = RoundedCornerShape(20.dp),
            extraLarge = RoundedCornerShape(28.dp),
        ),
        content = content,
    )
}

val EnergyBrush = Brush.linearGradient(listOf(JimiColors.Accent, JimiColors.Accent))

@Composable
fun GradientTitle(text: String, size: Int = 22, modifier: Modifier = Modifier) = Text(
    text,
    modifier = modifier,
    color = JimiColors.Text,
    style = if (size >= 34) displayStyle.copy(fontSize = size.sp) else titleStyle.copy(fontSize = size.sp),
)

@Composable
fun GlassCard(modifier: Modifier = Modifier, title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth()
            .background(JimiColors.Surface1, RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (title != null) Text(title, style = cardTitleStyle, color = JimiColors.Text)
        CompositionLocalProvider(LocalContentColor provides JimiColors.Text) { content() }
    }
}
