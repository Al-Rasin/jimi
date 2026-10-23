package com.alrasin.jarvis.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp

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

/** Small rounded action. [selected] lights it cyan; used for every little action in lists. */
@Composable
fun Chip(text: String, selected: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        Modifier.height(34.dp).clip(shape)
            .background(if (selected) JimiColors.Cyan.copy(alpha = 0.14f) else JimiColors.Surface2)
            .border(1.dp, if (selected) JimiColors.Cyan.copy(alpha = 0.6f) else JimiColors.Outline, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.45f)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = if (selected) JimiColors.Cyan else JimiColors.Text, maxLines = 1)
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
            colors = SwitchDefaults.colors(uncheckedTrackColor = JimiColors.Surface3, uncheckedBorderColor = JimiColors.OutlineStrong),
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
fun RowDivider() = HorizontalDivider(color = JimiColors.Outline.copy(alpha = 0.6f), thickness = 1.dp)

/** Shown when a list has nothing in it yet. */
@Composable
fun EmptyState(title: String, text: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        MobiusStrip(OrbMood.IDLE, size = 72.dp, still = true)
        Text(title, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleSmall)
        Hint(text, Modifier.padding(top = 4.dp))
    }
}

/** Little round light: cyan when on, grey when off. */
@Composable
fun StatusDot(on: Boolean) = Box(Modifier.size(8.dp).clip(CircleShape).background(if (on) JimiColors.Cyan else JimiColors.TertiaryText))
