package com.alrasin.jarvis.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
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
    val phase by vm.phase.collectAsStateWithLifecycle()
    val heard by vm.heard.collectAsStateWithLifecycle()

    var showLog by remember { mutableStateOf(false) }
    val c = counts.associate { it.verdict to it.n }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { PageTitle("Wake word") }
        item {
            // big live score: the one number that matters here
            RowsCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("%.2f".format(score), style = MaterialTheme.typography.displaySmall, color = JimiColors.Text)
                        Meta(if (running) "Live score · listening" else "Not listening")
                    }
                    Chip(if (running) "Stop" else "Start", selected = !running) { if (running) vm.stop() else vm.start() }
                }
                LinearProgressIndicator(
                    progress = { score }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    color = JimiColors.Accent, trackColor = JimiColors.Surface3, drawStopIndicator = {},
                )
                if (heard.isNotEmpty()) Hint("You said: $heard", Modifier.padding(top = 10.dp))
                if (model.isNotEmpty() && !model.startsWith("hey_jimi")) Text(
                    "Old \"Hey Jarvis\" model in use — Hey Jimi model missing", Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodySmall, color = JimiColors.Error,
                )
                ChipRow(Modifier.padding(top = 8.dp)) { Chip("I said it, missed", enabled = running, onClick = vm::missed) }
            }
        }
        item {
            RowsCard("Sensitivity") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Threshold", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text("%.2f".format(threshold), style = MaterialTheme.typography.bodySmall)
                }
                JimiSlider(threshold, { vm.setThreshold(it) }, 0.2f..0.95f)
                Meta("Lower = wakes easier · ✓ ${c[WakeEvent.VERDICT_YES] ?: 0}  ✗ ${c[WakeEvent.VERDICT_NO] ?: 0}  missed ${c[WakeEvent.VERDICT_MISSED] ?: 0}")
            }
        }
        item { ListCard("Recent wakes", events.take(30), "No wakes yet.") { e -> EventRow(e, vm) } }
        item {
            RowsCard {
                Row(Modifier.fillMaxWidth().clickable { showLog = !showLog }.padding(vertical = 4.dp)) {
                    Text("Log", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Meta(if (showLog) "Hide" else "Show")
                }
                if (showLog) log.takeLast(30).reversed().forEach { Text(it, style = MaterialTheme.typography.bodySmall.copy(fontFamily = JimiMono)) }
            }
        }
    }
}

/** One wake: time and result; tap to play it and say if it was you. */
@Composable
private fun EventRow(e: WakeEvent, vm: HomeViewModel) {
    OpenRow(
        WakeFeedback.label(e.verdict).replaceFirstChar { it.uppercase() },
        null,
        "${shortTime(e.timeMs)} · ${"%.2f".format(e.score)}",
    ) {
        Meta(timeFormat.format(Date(e.timeMs)))
        ChipRow {
            Chip("Play") { vm.play(e.clipPath) }
            Chip("It was me", selected = e.verdict == WakeEvent.VERDICT_YES) { vm.setVerdict(e.id, WakeEvent.VERDICT_YES) }
            Chip("Not me", selected = e.verdict == WakeEvent.VERDICT_NO) { vm.setVerdict(e.id, WakeEvent.VERDICT_NO) }
        }
    }
}
