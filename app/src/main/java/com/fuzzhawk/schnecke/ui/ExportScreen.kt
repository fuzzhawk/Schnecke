package com.fuzzhawk.schnecke.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fuzzhawk.schnecke.ExportKind
import com.fuzzhawk.schnecke.MainViewModel
import com.fuzzhawk.schnecke.UiState
import com.fuzzhawk.schnecke.audio.AudioEngine
import com.fuzzhawk.schnecke.core.export.ProjectExporter
import kotlin.math.roundToInt

@Composable
fun ExportScreen(state: UiState, vm: MainViewModel) {
    val p = state.project
    val busy = state.busy != null
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (p.notes.isEmpty() && p.layers.values.all { it.take == null }) {
            Hint("Record something on the Loop tab first.")
        }

        ExportCard("MIDI file (.mid)", "Your hits as a General MIDI drum pattern (channel 10), one track per layer, at ${formatBpm(p.bpm)} bpm.") {
            var repeats by rememberSaveable { mutableIntStateOf(1) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Repeat the loop", Modifier.weight(1f))
                OutlinedButton(onClick = { repeats = (repeats - 1).coerceAtLeast(1) }) { Text("−") }
                Text("  ${repeats}×  ")
                OutlinedButton(onClick = { repeats = (repeats + 1).coerceAtMost(64) }) { Text("+") }
            }
            Button(onClick = { vm.export(ExportKind.MIDI, midiRepeats = repeats) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Export MIDI") }
        }

        ExportCard("Audio file (.wav)", "The mix, repeated for as long as you like. 48 kHz, 16-bit stereo.") {
            var seconds by rememberSaveable { mutableFloatStateOf(60f) }
            var whole by rememberSaveable { mutableStateOf(true) }
            var fade by rememberSaveable { mutableStateOf(true) }
            val frames = ProjectExporter.audioFrames(p, AudioEngine.SAMPLE_RATE, seconds.toDouble(), whole)
            val actual = frames / AudioEngine.SAMPLE_RATE.toDouble()
            val loops = frames / p.loopFrames(AudioEngine.SAMPLE_RATE).toDouble()
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((label, s) in listOf("30 s" to 30f, "1 min" to 60f, "2 min" to 120f, "5 min" to 300f, "10 min" to 600f)) {
                    Chip(label) { seconds = s }
                }
            }
            Slider(seconds, { seconds = it.roundToInt().toFloat() }, valueRange = 5f..900f)
            Text("Length ${duration(actual)} · ${"%.1f".format(loops)} loops", style = MaterialTheme.typography.bodyMedium)
            SwitchRow("End on a whole loop", whole) { whole = it }
            SwitchRow("Fade out at the end", fade) { fade = it }
            Button(onClick = { vm.export(ExportKind.WAV, seconds = seconds.toDouble(), wholeLoops = whole, fadeOut = fade) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text("Export audio")
            }
        }

        ExportCard(
            "Ableton Live 10 project (.zip)",
            "A Live set (opens in Live 10.0.6 and newer) with a looping audio clip per sound, your raw recordings, " +
                "MIDI clips of the pattern, and every one-shot sample for building a Drum Rack. " +
                "Unzip it on your computer and open the .als inside the folder.",
        ) {
            Button(onClick = { vm.export(ExportKind.ABLETON) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Export Live set") }
        }
    }
}

private fun duration(s: Double): String {
    val t = s.roundToInt()
    return if (t >= 60) "%d:%02d".format(t / 60, t % 60) else "$t s"
}

@Composable
private fun ExportCard(title: String, text: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Palette.surface)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle(title)
            Hint(text)
            content()
        }
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onChange)
    }
}

/** A label above its control, so wide segmented controls fit on narrow phones. */
@Composable
fun LabeledRow(label: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        content()
    }
}
