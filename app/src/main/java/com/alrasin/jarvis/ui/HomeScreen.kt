package com.alrasin.jarvis.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
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

    Surface(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text("Jarvis", style = MaterialTheme.typography.headlineMedium)
                Text(if (running) "Listening" else "Not listening")
                if (model.isNotEmpty()) {
                    val jimi = model.startsWith("hey_jimi")
                    Text(
                        if (jimi) "Model: Hey Jimi ($model)" else "Model: OLD fallback \"Hey Jarvis\" ($model) — Hey Jimi model missing",
                        color = if (jimi) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = vm::start, enabled = !running) { Text("Start") }
                    OutlinedButton(onClick = vm::stop, enabled = running) { Text("Stop") }
                }
            }
            item {
                Button(
                    onClick = vm::missed,
                    enabled = running,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Missed it — I said \"Hey Jimi\" (tap within 3 s)") }
            }
            item {
                Text("Live score ${"%.2f".format(score)}")
                LinearProgressIndicator(progress = { score }, modifier = Modifier.fillMaxWidth())
                Text("Wake threshold ${"%.2f".format(threshold)} (lower = wakes easier, more false wakes)")
                Slider(value = threshold, onValueChange = { vm.setThreshold(it) }, valueRange = 0.2f..0.95f)
            }
            item {
                val c = counts.associate { it.verdict to it.n }
                Text(
                    "Feedback: ✓ ${c[WakeEvent.VERDICT_YES] ?: 0}   ✗ ${c[WakeEvent.VERDICT_NO] ?: 0}   " +
                        "missed ${c[WakeEvent.VERDICT_MISSED] ?: 0}   near ${c[WakeEvent.VERDICT_NEAR] ?: 0}   " +
                        "no answer ${c[WakeEvent.VERDICT_NONE] ?: 0}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            items(events, key = { it.id }) { e -> EventRow(e, vm) }
            item {
                Text("Log", style = MaterialTheme.typography.titleSmall)
                log.takeLast(30).reversed().forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
private fun EventRow(e: WakeEvent, vm: HomeViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(8.dp)) {
            Text(
                "${timeFormat.format(Date(e.timeMs))}  score ${"%.2f".format(e.score)}  — ${WakeFeedback.label(e.verdict)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { vm.play(e.clipPath) }) { Text("▶ Play") }
                TextButton(onClick = { vm.setVerdict(e.id, WakeEvent.VERDICT_YES) }) { Text("✓ Me") }
                TextButton(onClick = { vm.setVerdict(e.id, WakeEvent.VERDICT_NO) }) { Text("✗ Not me") }
            }
        }
    }
}
