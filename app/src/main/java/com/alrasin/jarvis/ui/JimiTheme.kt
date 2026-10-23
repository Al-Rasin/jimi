package com.alrasin.jarvis.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.LocalContentColor
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.lightColorScheme
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

/** Light premium palette (D-057): off-white page, white cards, ink text, one indigo accent ("Cyan" kept as the name). */
object JimiColors {
    val Background = Color(0xFFF6F7FB)
    val Surface1 = Color(0xFFFFFFFF)
    val Surface2 = Color(0xFFF0F2F7)
    val Surface3 = Color(0xFFE8EBF2)
    val Outline = Color(0xFFE4E7EE)
    val OutlineStrong = Color(0xFFD3D8E2)
    val Cyan = Color(0xFF5B5BF0)
    val OnCyan = Color(0xFFFFFFFF)
    val AccentSoft = Color(0xFFECECFE)
    val OrbBlue = Color(0xFF6A8DFF)
    val Violet = Color(0xFF8E7BFF)
    val Text = Color(0xFF14161F)
    val SecondaryText = Color(0xFF5B6272)
    val TertiaryText = Color(0xFF8A91A0)
    val Error = Color(0xFFD93A4A)
    val Success = Color(0xFF1E9E6A)
    val Warning = Color(0xFFC98500)
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

private val displayStyle = TextStyle(fontFamily = JimiDisplay, fontWeight = FontWeight.Medium, fontSize = 34.sp, lineHeight = 40.sp)
private val titleStyle = TextStyle(fontFamily = JimiDisplay, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp)
private val bodyStyle = TextStyle(fontFamily = JimiBody, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp)
private val labelStyle = TextStyle(fontFamily = JimiBody, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 20.sp, letterSpacing = 0.2.sp)
private val jimiTypography = Typography(
    displayLarge = displayStyle, displayMedium = displayStyle, displaySmall = displayStyle,
    headlineLarge = displayStyle, headlineMedium = titleStyle, headlineSmall = titleStyle,
    titleLarge = titleStyle, titleMedium = titleStyle, titleSmall = titleStyle,
    bodyLarge = bodyStyle, bodyMedium = bodyStyle, bodySmall = bodyStyle,
    labelLarge = labelStyle, labelMedium = labelStyle, labelSmall = labelStyle,
)

@Composable
fun JimiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = JimiColors.Cyan,
            onPrimary = JimiColors.OnCyan,
            primaryContainer = JimiColors.AccentSoft,
            onPrimaryContainer = JimiColors.Cyan,
            inversePrimary = JimiColors.AccentSoft,
            secondary = JimiColors.Cyan,
            onSecondary = JimiColors.OnCyan,
            secondaryContainer = JimiColors.AccentSoft,
            onSecondaryContainer = JimiColors.Text,
            tertiary = JimiColors.Cyan,
            onTertiary = JimiColors.OnCyan,
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
            onError = Color.White,
            errorContainer = Color(0xFFFDECEE),
            onErrorContainer = JimiColors.Error,
            scrim = Color(0x66000000),
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

val EnergyBrush = Brush.linearGradient(listOf(JimiColors.Cyan, JimiColors.Cyan))

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
            .background(JimiColors.Surface1, RoundedCornerShape(24.dp))
            .border(1.dp, JimiColors.Outline, RoundedCornerShape(24.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (title != null) GradientTitle(title, size = 17)
        CompositionLocalProvider(LocalContentColor provides JimiColors.Text) { content() }
    }
}
