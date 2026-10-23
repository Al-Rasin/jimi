package com.alrasin.jarvis.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alrasin.jarvis.data.WakeEvent
import com.alrasin.jarvis.service.WakeFeedback
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val timeFormat = SimpleDateFormat("dd MMM HH:mm:ss", Locale.US)

@Composable
fun HomeScreen(vm: HomeViewModel) {
    val running by vm.running.collectAsStateWithLifecycle()
    val score by vm.lastScore.collectAsStateWithLifecycle()
    val threshold by vm.threshold.collectAsStateWithLifecycle()
    val events by vm.events.collectAsStateWithLifecycle()
    val counts by vm.counts.collectAsStateWithLifecycle()
    val log by vm.log.collectAsStateWithLifecycle()
    val model by vm.modelName.collectAsStateWithLifecycle()
    val phase by vm.phase.collectAsStateWithLifecycle()
    val heard by vm.heard.collectAsStateWithLifecycle()

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            val jimi = model.startsWith("hey_jimi")
            PageTitle("Wake word", if (running) "Listening — $phase" else "Not listening")
            if (model.isNotEmpty()) Text(
                if (jimi) "Model: Hey Jimi ($model)" else "Model: OLD fallback \"Hey Jarvis\" ($model) — Hey Jimi model missing",
                style = MaterialTheme.typography.bodySmall,
                color = if (jimi) JimiColors.Cyan else JimiColors.Error,
            )
        }
        item {
            GlassCard {
                Meta("You said")
                Text(heard.ifEmpty { "—" }, style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = vm::start, enabled = !running) { Text("Start") }
                    FilledTonalButton(onClick = vm::stop, enabled = running) { Text("Stop") }
                }
                FilledTonalButton(onClick = vm::missed, enabled = running, modifier = Modifier.fillMaxWidth()) {
                    Text("Missed it — I said \"Hey Jimi\" (tap within 3 s)")
                }
            }
        }
        item {
            GlassCard(title = "Sensitivity") {
                Hint("Live score ${"%.2f".format(score)}")
                LinearProgressIndicator(progress = { score }, modifier = Modifier.fillMaxWidth(), trackColor = JimiColors.Surface3)
                Hint("Wake threshold ${"%.2f".format(threshold)} (lower = wakes easier, more false wakes)")
                Slider(value = threshold, onValueChange = { vm.setThreshold(it) }, valueRange = 0.2f..0.95f)
                val c = counts.associate { it.verdict to it.n }
                Meta(
                    "Feedback: ✓ ${c[WakeEvent.VERDICT_YES] ?: 0}   ✗ ${c[WakeEvent.VERDICT_NO] ?: 0}   " +
                        "missed ${c[WakeEvent.VERDICT_MISSED] ?: 0}   near ${c[WakeEvent.VERDICT_NEAR] ?: 0}   " +
                        "no answer ${c[WakeEvent.VERDICT_NONE] ?: 0}",
                )
            }
        }
        items(events, key = { it.id }) { e -> EventRow(e, vm) }
        item {
            GlassCard(title = "Log") {
                log.takeLast(30).reversed().forEach { Text(it, style = MaterialTheme.typography.bodySmall.copy(fontFamily = JimiMono)) }
            }
        }
    }
}

@Composable
private fun EventRow(e: WakeEvent, vm: HomeViewModel) {
    GlassCard {
        Meta("${timeFormat.format(Date(e.timeMs))} · score ${"%.2f".format(e.score)} · ${WakeFeedback.label(e.verdict)}")
        ChipRow {
            Chip("▶ Play") { vm.play(e.clipPath) }
            Chip("✓ Me", selected = e.verdict == WakeEvent.VERDICT_YES) { vm.setVerdict(e.id, WakeEvent.VERDICT_YES) }
            Chip("✗ Not me", selected = e.verdict == WakeEvent.VERDICT_NO) { vm.setVerdict(e.id, WakeEvent.VERDICT_NO) }
        }
    }
}
