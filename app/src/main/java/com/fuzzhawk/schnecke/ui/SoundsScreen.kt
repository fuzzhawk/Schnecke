package com.fuzzhawk.schnecke.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fuzzhawk.schnecke.MainViewModel
import com.fuzzhawk.schnecke.UiState
import com.fuzzhawk.schnecke.core.model.Layer
import com.fuzzhawk.schnecke.core.model.LayerMode
import com.fuzzhawk.schnecke.core.model.SampleSource
import com.fuzzhawk.schnecke.core.model.Voice
import com.fuzzhawk.schnecke.core.synth.DrumSynth
import kotlin.math.roundToInt

@Composable
fun SoundsScreen(state: UiState, vm: MainViewModel, host: Host) {
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        for (layer in Layer.entries) {
            val ls = state.project.layer(layer)
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle(layer.label, Modifier.weight(1f))
                IconButton(onClick = { vm.editLayer(layer) { it.copy(muted = !it.muted) } }) {
                    Icon(if (ls.muted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp, "Mute layer", tint = if (ls.muted) Palette.record else Palette.text)
                }
            }
            if (ls.mode == LayerMode.RAW) {
                Hint("${layer.label} is in raw-audio mode: it plays your recording. Switch to Samples on the Loop tab to use these sounds.")
                DbSlider("Raw level", ls.gainDb) { db -> vm.editLayer(layer) { it.copy(gainDb = db) } }
            }
            for (v in Voice.of(layer)) VoiceCard(v, state, vm, host)
        }
        Spacer(Modifier.size(8.dp))
    }
}

@Composable
private fun VoiceCard(v: Voice, state: UiState, vm: MainViewModel, host: Host) {
    val vs = state.project.voice(v)
    val candidates = state.recordedCandidates[v] ?: 0
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Palette.surface)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(Palette.voice(v)))
                Text("  ${v.label}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                IconButton(onClick = { vm.preview(v) }) { Icon(Icons.Filled.PlayArrow, "Preview") }
                IconButton(onClick = { vm.editVoice(v) { it.copy(muted = !it.muted) } }) {
                    Icon(if (vs.muted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp, "Mute", tint = if (vs.muted) Palette.record else Palette.text)
                }
            }
            SourcePicker(v, vs.source, candidates, vm, host)
            if (vs.source is SampleSource.Recorded) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Hint(
                        if (candidates == 0) "No ${v.label.lowercase()} in your recording yet; a built-in sound plays instead."
                        else "Cut from your ${v.layer.label.lowercase()} recording (hit ${(vs.source.candidate % candidates) + 1} of $candidates).",
                        Modifier.weight(1f),
                    )
                    if (candidates > 1) {
                        OutlinedButton(onClick = { vm.nextRecordedCandidate(v) }) { Icon(Icons.Filled.Refresh, null); Text(" Another") }
                    }
                }
            }
            DbSlider("Volume", vs.gainDb) { db -> vm.editVoice(v) { it.copy(gainDb = db) } }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Pan", Modifier.width(72.dp), style = MaterialTheme.typography.labelMedium)
                var pan by remember(vs.pan) { mutableFloatStateOf(vs.pan) }
                Slider(pan, { pan = it }, Modifier.weight(1f), valueRange = -1f..1f, onValueChangeFinished = { vm.editVoice(v) { it.copy(pan = pan) } })
                Text(panLabel(pan), Modifier.width(44.dp), style = MaterialTheme.typography.labelMedium)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("MIDI note", Modifier.width(72.dp), style = MaterialTheme.typography.labelMedium)
                OutlinedButton(onClick = { vm.editVoice(v) { it.copy(midiNote = (it.midiNote - 1).coerceAtLeast(0)) } }) { Text("−") }
                Text("  ${vs.midiNote}  ${gmDrumName(vs.midiNote)}  ", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = { vm.editVoice(v) { it.copy(midiNote = (it.midiNote + 1).coerceAtMost(127)) } }) { Text("+") }
            }
        }
    }
}

@Composable
private fun SourcePicker(v: Voice, source: SampleSource, candidates: Int, vm: MainViewModel, host: Host) {
    var open by remember { mutableStateOf(false) }
    val label = when (source) {
        is SampleSource.Library -> DrumSynth.byId(source.id)?.name ?: source.id
        is SampleSource.Recorded -> "From your recording"
        is SampleSource.Custom -> "Imported: ${source.name}"
    }
    Box {
        Surface(onClick = { open = true }, color = Palette.surfaceHigh, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, Modifier.weight(1f))
                Icon(Icons.Filled.ArrowDropDown, null)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("From your recording" + if (candidates > 0) " ($candidates hits)" else "") },
                onClick = { open = false; vm.setVoiceSource(v, SampleSource.Recorded()) },
            )
            HorizontalDivider()
            for (s in DrumSynth.choicesFor(v)) {
                DropdownMenuItem(
                    text = { Text(s.name) },
                    trailingIcon = { IconButton(onClick = { vm.previewLibrary(s.id) }) { Icon(Icons.Filled.PlayArrow, "Preview") } },
                    onClick = { open = false; vm.setVoiceSource(v, SampleSource.Library(s.id)) },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Import a WAV file…") },
                onClick = { open = false; host.pickWav { uri, name -> vm.importCustom(v, uri, name) } },
            )
        }
    }
}

@Composable
fun DbSlider(label: String, db: Float, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(72.dp), style = MaterialTheme.typography.labelMedium)
        var v by remember(db) { mutableFloatStateOf(db) }
        Slider(v, { v = it }, Modifier.weight(1f), valueRange = -30f..6f, onValueChangeFinished = { onChange(v) })
        Text(if (v <= -29.5f) "off" else "${if (v > 0) "+" else ""}${v.roundToInt()} dB", Modifier.width(44.dp), style = MaterialTheme.typography.labelMedium)
    }
}

private fun panLabel(p: Float): String {
    val n = (p * 100).roundToInt()
    return when {
        n == 0 -> "C"
        n < 0 -> "L${-n}"
        else -> "R$n"
    }
}

fun gmDrumName(note: Int): String = when (note) {
    35 -> "Acoustic Bass Drum"; 36 -> "Bass Drum"; 37 -> "Side Stick"; 38 -> "Snare"; 39 -> "Hand Clap"
    40 -> "Electric Snare"; 41 -> "Low Floor Tom"; 42 -> "Closed Hi-Hat"; 43 -> "High Floor Tom"; 44 -> "Pedal Hi-Hat"
    45 -> "Low Tom"; 46 -> "Open Hi-Hat"; 47 -> "Low-Mid Tom"; 48 -> "Hi-Mid Tom"; 49 -> "Crash"; 50 -> "High Tom"
    51 -> "Ride"; 54 -> "Tambourine"; 56 -> "Cowbell"; 62 -> "Mute Hi Conga"; 63 -> "Open Hi Conga"; 64 -> "Low Conga"
    69 -> "Cabasa"; 70 -> "Maracas"; 75 -> "Claves"; 76 -> "Hi Wood Block"; 77 -> "Low Wood Block"
    else -> ""
}
