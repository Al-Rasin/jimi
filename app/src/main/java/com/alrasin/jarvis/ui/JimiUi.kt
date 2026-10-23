package com.alrasin.jarvis.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/*
 * Small shared pieces so every tab looks the same: one page title, one card, one chip, one row.
 * Same spacing everywhere: 20 dp page edge, 12 dp between cards, 16 dp inside a card.
 */

/** Big title at the top of a tab, with an optional quiet line under it. */
@Composable
fun PageTitle(title: String, subtitle: String? = null) {
    Column(Modifier.padding(top = 4.dp, bottom = 4.dp)) {
        GradientTitle(title, 28)
        if (subtitle != null) Hint(subtitle)
    }
}

/** Quiet helper text. */
@Composable
fun Hint(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier, style = MaterialTheme.typography.bodySmall, color = JimiColors.SecondaryText)

/** Small grey line above a card's content, like "8 Oct 14:02 · voice". */
@Composable
fun Meta(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier, style = MaterialTheme.typography.labelSmall, color = JimiColors.TertiaryText, maxLines = 1)

/** Small quiet action: grey pill, no border. [selected] turns it blue. Used for every little action in lists. */
@Composable
fun Chip(text: String, selected: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        Modifier.height(34.dp).clip(shape)
            .background(if (selected) JimiColors.Accent.copy(alpha = 0.18f) else JimiColors.Surface2)
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.45f)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = if (selected) JimiColors.AccentLight else JimiColors.SecondaryText, maxLines = 1)
    }
}

/** A row of chips that wraps to the next line when full. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChipRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) = FlowRow(
    modifier.fillMaxWidth().padding(top = 4.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
) { content() }

/** One setting: title, optional quiet line, switch on the right. */
@Composable
fun ToggleRow(title: String, value: Boolean, subtitle: String? = null, set: (Boolean) -> Unit) {
    var checked by remember(value) { mutableStateOf(value) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { checked = !checked; set(checked) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (subtitle != null) Hint(subtitle)
        }
        Switch(
            checked = checked, onCheckedChange = { checked = it; set(it) },
            colors = SwitchDefaults.colors(
                checkedThumbColor = JimiColors.OnAccent, checkedTrackColor = JimiColors.Accent, checkedBorderColor = JimiColors.Accent,
                uncheckedThumbColor = JimiColors.TertiaryText, uncheckedTrackColor = JimiColors.Surface3, uncheckedBorderColor = JimiColors.Surface3,
            ),
        )
    }
}

/** A list line inside a card: main text, quiet line under it, actions on the right. */
@Composable
fun ItemRow(text: String, meta: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            if (meta != null) Meta(meta)
        }
        actions()
    }
}

/** Thin line between rows in a card. */
@Composable
fun RowDivider() = HorizontalDivider(color = JimiColors.Outline.copy(alpha = 0.5f), thickness = 1.dp)

/** Shown when a list has nothing in it yet. */
@Composable
fun EmptyState(title: String, text: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        MobiusStrip(OrbMood.IDLE, size = 72.dp, still = true)
        Text(title, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleSmall)
        Hint(text, Modifier.padding(top = 4.dp))
    }
}

/** Little round light: blue when on, grey when off. */
@Composable
fun StatusDot(on: Boolean) = Box(Modifier.size(8.dp).clip(CircleShape).background(if (on) JimiColors.Accent else JimiColors.TertiaryText))

/** One card holding rows with thin lines between them (the Notes look, D-068). */
@Composable
fun RowsCard(title: String? = null, content: @Composable ColumnScope.() -> Unit) =
    GlassCard(title = title) { Column(content = content) }

/** [RowsCard] for a list; [empty] shows when there are no rows. */
@Composable
fun <T> ListCard(title: String?, rows: List<T>, empty: String, row: @Composable (T) -> Unit) = RowsCard(title) {
    if (rows.isEmpty()) Hint(empty)
    rows.forEachIndexed { i, r ->
        if (i > 0) RowDivider()
        row(r)
    }
}

/** Settings menu line: title, current value in grey, arrow. Opens a sub page. */
@Composable
fun NavRow(title: String, value: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        if (value != null) Text(value, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(20.dp), tint = JimiColors.TertiaryText)
    }
}

/** Full page with a back arrow; system back closes it too. */
@Composable
fun SubPage(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    BackHandler(onBack = onBack)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 20.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = JimiColors.Text)
            }
            GradientTitle(title, 24, Modifier.padding(start = 4.dp))
        }
        Column(Modifier.padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
    }
}

/** Round letter badge at the start of a row (app name, etc.). [lit] tints it blue. */
@Composable
fun Avatar(text: String, lit: Boolean = false) = Box(
    Modifier.size(36.dp).clip(CircleShape).background(if (lit) JimiColors.Accent.copy(alpha = 0.18f) else JimiColors.Surface3),
    contentAlignment = Alignment.Center,
) {
    Text(text.take(1).uppercase(), style = MaterialTheme.typography.labelLarge, color = if (lit) JimiColors.AccentLight else JimiColors.SecondaryText)
}

/** A row that opens on tap to show more ([more]) — keeps lists short. */
@Composable
fun OpenRow(
    title: String,
    sub: String? = null,
    trailing: String? = null,
    lead: (@Composable () -> Unit)? = null,
    more: @Composable ColumnScope.() -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { open = !open }.padding(vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (lead != null) { lead(); Box(Modifier.size(12.dp)) }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = if (open) 6 else 1, overflow = TextOverflow.Ellipsis)
                if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, maxLines = if (open) 20 else 1, overflow = TextOverflow.Ellipsis)
            }
            if (trailing != null) Meta(trailing, Modifier.padding(start = 12.dp))
        }
        if (open) Column(Modifier.padding(top = 8.dp, start = if (lead != null) 48.dp else 0.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = more)
    }
}

private val clockFmt = SimpleDateFormat("HH:mm", Locale.US)
private val dayFmt = SimpleDateFormat("d MMM", Locale.US)

/** "14:02" for today, "8 Oct" for older. */
fun shortTime(ms: Long): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = ms }
    val today = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    return (if (today) clockFmt else dayFmt).format(Date(ms))
}

/** Slider in the one blue, without the tick dots. */
@Composable
fun JimiSlider(value: Float, onChange: (Float) -> Unit, range: ClosedFloatingPointRange<Float>, steps: Int = 0) = Slider(
    value, onChange, valueRange = range, steps = steps,
    colors = SliderDefaults.colors(
        thumbColor = JimiColors.Accent, activeTrackColor = JimiColors.Accent, inactiveTrackColor = JimiColors.Surface3,
        activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent,
    ),
)
