package com.fuzzhawk.schnecke.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fuzzhawk.schnecke.MainViewModel
import com.fuzzhawk.schnecke.UiState
import com.fuzzhawk.schnecke.data.AutoMode
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(state: UiState, vm: MainViewModel) {
    val s = state.settings
    val p = state.project
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingsCard("Grid") {
            Text("Resolution", style = MaterialTheme.typography.labelLarge)
            Segmented(
                listOf("1/8" to 2, "1/8T" to 3, "1/16" to 4, "1/16T" to 6, "1/32" to 8),
                p.stepsPerBeat, { vm.setStepsPerBeat(it) },
            )
            Hint("Changing the grid re-snaps hits; turn the sensitivity slider or re-record to re-detect with it.")
            var q by remember(p.quantize) { mutableFloatStateOf(p.quantize) }
            Text("Quantize ${(q * 100).roundToInt()}%  (0% keeps your exact timing)", style = MaterialTheme.typography.labelLarge)
            Slider(q, { q = it }, onValueChangeFinished = { vm.setQuantize(q) })
            if (p.stepsPerBeat % 2 == 0) {
                var sw by remember(p.swingPercent) { mutableFloatStateOf(p.swingPercent) }
                Text("Swing ${sw.roundToInt()}%  (50% straight, 66% triplet)", style = MaterialTheme.typography.labelLarge)
                Slider(sw, { sw = it.roundToInt().toFloat() }, valueRange = 50f..75f, onValueChangeFinished = { vm.setSwing(sw) })
            }
        }

        SettingsCard("Recording") {
            LabeledRow("Count-in") {
                Segmented(listOf("None" to 0, "1 bar" to 1, "2 bars" to 2), s.countInBars, { v -> vm.updateSettings { it.copy(countInBars = v) } })
            }
            Hint("The hands layer is recorded freely, without a metronome. These apply to the beatbox layer, which is recorded over the loop.")
            LabeledRow("Click while recording beatbox") {
                Segmented(autoOptions, s.clickWhileRecording, { v -> vm.updateSettings { it.copy(clickWhileRecording = v) } })
            }
            SwitchRow("Play hands loop while recording beatbox", s.playHandsWhileBeatboxing) { v ->
                vm.updateSettings { it.copy(playHandsWhileBeatboxing = v) }
            }
            Hint(
                "Auto plays the click only when headphones are connected. Without headphones the phone's speaker " +
                    "leaks into the microphone. ${if (state.headphones) "Headphones are connected." else "No headphones connected right now."}",
            )
            SwitchRow("Vibrate on the beat", s.haptics) { v -> vm.updateSettings { it.copy(haptics = v) } }
            Hint("A silent metronome you can feel. If the phone lies on the surface you're tapping, the buzz may be picked up.")
            SwitchRow("Loudness sets velocity", s.dynamicVelocity) { v -> vm.updateSettings { it.copy(dynamicVelocity = v) } }
        }

        SettingsCard("Metronome") {
            SwitchRow("Click during playback", s.clickDuringPlayback) { v -> vm.updateSettings { it.copy(clickDuringPlayback = v) } }
            var cv by remember(s.clickVolume) { mutableFloatStateOf(s.clickVolume) }
            Text("Click volume", style = MaterialTheme.typography.labelLarge)
            Slider(cv, { cv = it }, onValueChangeFinished = { vm.updateSettings { it.copy(clickVolume = cv) } })
        }

        SettingsCard("Timing") {
            var lat by remember(s.latencyMs) { mutableFloatStateOf(s.latencyMs) }
            Text("Latency offset ${lat.roundToInt()} ms", style = MaterialTheme.typography.labelLarge)
            Slider(lat, { lat = it.roundToInt().toFloat() }, valueRange = -300f..300f, onValueChangeFinished = { vm.updateSettings { it.copy(latencyMs = lat) } })
            Hint("If your hits land consistently late on the grid, raise this; if early, lower it. Or let the app measure it:")
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { vm.openCalibration() }) { Text("Calibrate…") }
                Text("  ", Modifier.width(8.dp))
                OutlinedButton(onClick = { vm.updateSettings { it.copy(latencyMs = 0f) } }) { Text("Reset") }
            }
        }

        SettingsCard("About") {
            Hint(
                "Schnecke turns taps on any surface into drum loops. Detection works relative to each recording, " +
                    "so the darker sound you make becomes the kick and the brighter one the snare, whatever you tap on. " +
                    "Use Teach on a layer if a surface confuses it.",
            )
        }
    }
}

private val autoOptions = listOf("Auto" to AutoMode.AUTO, "On" to AutoMode.ON, "Off" to AutoMode.OFF)

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Palette.surface)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle(title)
            content()
        }
    }
}
