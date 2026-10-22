package com.alrasin.jarvis.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One look for the whole app and the panel: near-black surfaces + the cyan → violet → pink energy gradient. */
@Composable
fun JimiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = PanelColors.Cyan,
            onPrimary = Color(0xFF00141C),
            secondary = PanelColors.Violet,
            tertiary = PanelColors.Pink,
            background = Color(0xFF070A10),
            onBackground = PanelColors.Text,
            surface = Color(0xFF0B0E16),
            onSurface = PanelColors.Text,
            surfaceVariant = PanelColors.Field,
            onSurfaceVariant = PanelColors.Dim,
            surfaceContainer = Color(0xFF0E121B),
            outline = Color(0xFF2A3142),
        ),
        content = content,
    )
}

val EnergyBrush = Brush.linearGradient(listOf(PanelColors.Cyan, PanelColors.Violet, PanelColors.Pink))

/** Title in the energy gradient. */
@Composable
fun GradientTitle(text: String, size: Int = 18, modifier: Modifier = Modifier) = Text(
    text,
    modifier = modifier,
    style = TextStyle(brush = EnergyBrush, fontSize = size.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp),
)

/** Rounded glass card with a faint outline, used for every section. */
@Composable
fun GlassCard(modifier: Modifier = Modifier, title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth()
            .background(Color(0xFF0E121B), RoundedCornerShape(22.dp))
            .border(1.dp, Color(0xFF1C2230), RoundedCornerShape(22.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (title != null) GradientTitle(title, 16)
        content()
    }
}
