package com.fuzzhawk.schnecke.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fuzzhawk.schnecke.HandsStage
import com.fuzzhawk.schnecke.LayerView
import com.fuzzhawk.schnecke.MainViewModel
import com.fuzzhawk.schnecke.RecordPhase
import com.fuzzhawk.schnecke.UiState
import com.fuzzhawk.schnecke.core.model.Layer
import com.fuzzhawk.schnecke.core.model.LayerMode
import com.fuzzhawk.schnecke.core.model.Voice

@Composable
fun LoopScreen(state: UiState, vm: MainViewModel, host: Host, position: HeardPosition) {
    Box {
        when (state.stage) {
            HandsStage.CAPTURE -> CaptureScreen(state, vm, host)
            HandsStage.TRIM -> TrimScreen(state, vm, position)
            HandsStage.LAB -> Lab(state, vm, host, position)
        }
        if (state.analyzing) SpiralOverlay(if (state.stage == HandsStage.LAB) "Listening for hits…" else "Reading your recording…")
    }
}

/** Step 3: the detection lab. */
@Composable
private fun Lab(state: UiState, vm: MainViewModel, host: Host, position: HeardPosition) {
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // The pattern stays at the top, where it can be seen and edited while the loop plays.
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Palette.surface)) {
            Column(Modifier.padding(12.dp)) {
                PatternEditor(state, vm, position)
            }
        }
        LayerCard(Layer.HANDS, "kick & snare", "No recording: hits are programmed by hand. Record to tap your groove freely and mark its loop.", state, vm, host)
        LayerCard(Layer.BEATBOX, "hats & percussion", "Beatbox over the loop: \"ts\" hats, \"tsss\" open hats, \"k\"/\"pf\" percussion.", state, vm, host)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun LayerCard(layer: Layer, subtitle: String, help: String, state: UiState, vm: MainViewModel, host: Host) {
    val ls = state.project.layer(layer)
    val view = state.layerViews[layer]
    val rec = state.record
    val mine = rec.layer == layer && rec.phase != RecordPhase.IDLE
    val otherBusy = rec.phase != RecordPhase.IDLE && !mine
    var menu by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Palette.surface)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(layer.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Hint(subtitle)
                }
                if (layer == Layer.HANDS) {
                    // The hands loop is made in the record / set-loop steps.
                    Button(
                        onClick = { vm.reRecordHands() },
                        enabled = rec.phase == RecordPhase.IDLE,
                        colors = ButtonDefaults.buttonColors(containerColor = Palette.surfaceHigh, contentColor = Palette.text),
                    ) {
                        Icon(Icons.Filled.FiberManualRecord, null, Modifier.size(16.dp), tint = Palette.record)
                        Text(if (ls.take != null) " Re-record" else " Record")
                    }
                } else {
                    RecordButton(mine, rec.phase, enabled = !otherBusy) { host.withMicPermission { vm.record(layer) } }
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (layer == Layer.HANDS && ls.take != null) {
                            DropdownMenuItem(text = { Text("Edit loop points") }, onClick = { menu = false; vm.editLoopPoints() })
                        }
                        DropdownMenuItem(text = { Text("Clear hits") }, onClick = { menu = false; vm.clearNotes(layer) })
                        DropdownMenuItem(text = { Text("Delete recording") }, onClick = { menu = false; vm.clearLayer(layer) })
                        if (ls.profile != null) {
                            DropdownMenuItem(text = { Text("Forget taught sounds") }, onClick = { menu = false; vm.forgetProfile(layer) })
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Segmented(
                    listOf("Samples" to LayerMode.REPLACE, "Raw audio" to LayerMode.RAW),
                    ls.mode,
                    { vm.setLayerMode(layer, it) },
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { host.withMicPermission { vm.startTeach(layer) } }) {
                    Icon(Icons.Filled.School, null, Modifier.size(18.dp))
                    Text(if (ls.profile != null) " Taught ✓" else " Teach")
                }
            }
            if (layer == Layer.BEATBOX) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Play hands loop while recording", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(state.settings.playHandsWhileBeatboxing, { v -> vm.updateSettings { it.copy(playHandsWhileBeatboxing = v) } })
                }
                if (state.settings.playHandsWhileBeatboxing && !state.headphones) {
                    Hint("No headphones connected: the loop will leak from the speaker into the microphone. Plug in headphones, or keep the volume low.")
                }
            }
            if (view == null) {
                Hint(help)
            } else {
                Waveform(view, Modifier.fillMaxWidth().height(56.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Sensitivity", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(84.dp))
                    var s by remember(ls.sensitivity) { mutableFloatStateOf(ls.sensitivity) }
                    Slider(value = s, onValueChange = { s = it }, onValueChangeFinished = { vm.setSensitivity(layer, s) }, modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (v in Voice.of(layer)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(Palette.voice(v)))
                            Text(" ${view.counts[v] ?: 0} ${v.label.lowercase()}", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                if (ls.mode == LayerMode.RAW) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Hint("Raw loop plays your recording. ", Modifier.weight(1f))
                        Segmented(listOf("Gate noise" to true, "Keep all" to false), ls.gateRaw, { vm.setGate(layer, it) })
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordButton(active: Boolean, phase: RecordPhase, enabled: Boolean, onClick: () -> Unit) {
    // Only animate while actually recording, so an idle screen does not redraw every frame.
    val pulse = if (active && phase == RecordPhase.RECORDING) {
        rememberInfiniteTransition(label = "rec").animateFloat(
            0.45f, 1f, infiniteRepeatable(tween(450), RepeatMode.Reverse), label = "pulse",
        ).value
    } else 1f
    val label = when {
        !active -> "Record"
        phase == RecordPhase.PREPARING -> "Ready…"
        phase == RecordPhase.COUNT_IN -> "Count-in"
        phase == RecordPhase.RECORDING -> "Recording"
        else -> "Listening…"
    }
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (active) Palette.record.copy(alpha = if (phase == RecordPhase.RECORDING) pulse else 0.8f) else Palette.surfaceHigh,
            contentColor = Palette.text,
        ),
    ) {
        Icon(Icons.Filled.FiberManualRecord, null, Modifier.size(16.dp), tint = if (active) Palette.text else Palette.record)
        Text(" $label")
    }
}

@Composable
private fun Waveform(view: LayerView, modifier: Modifier) {
    Canvas(modifier.clip(RoundedCornerShape(8.dp)).background(Palette.background)) {
        val w = size.width
        val h = size.height
        drawRect(Palette.surfaceHigh, topLeft = Offset(view.loopStart * w, 0f), size = Size((view.loopEnd - view.loopStart) * w, h))
        val n = view.peaks.size
        val bw = w / n
        for (i in 0 until n) {
            val a = view.peaks[i] * (h * 0.45f)
            drawRect(Palette.textDim.copy(alpha = 0.6f), topLeft = Offset(i * bw, h / 2 - a), size = Size(bw.coerceAtLeast(1f), a * 2 + 1f))
        }
        for ((x, v) in view.markers) {
            drawLine(Palette.voice(v), Offset(x * w, 0f), Offset(x * w, h), strokeWidth = 2.5f)
        }
    }
}
