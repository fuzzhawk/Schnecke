package com.fuzzhawk.schnecke.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fuzzhawk.schnecke.LayerView
import com.fuzzhawk.schnecke.MainViewModel
import com.fuzzhawk.schnecke.RecordPhase
import com.fuzzhawk.schnecke.UiState
import com.fuzzhawk.schnecke.audio.AudioEngine
import com.fuzzhawk.schnecke.core.model.Layer
import com.fuzzhawk.schnecke.core.model.LayerMode
import com.fuzzhawk.schnecke.core.model.Voice

@Composable
fun LoopScreen(state: UiState, vm: MainViewModel, host: Host, position: HeardPosition) {
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // The pattern stays at the top, where it can be seen and edited while the loop plays.
        Card(colors = CardDefaults.cardColors(containerColor = Palette.surface)) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle("Pattern", Modifier.weight(1f))
                    Hint("tap = add/remove · hold = change sound")
                }
                StepGrid(state, vm, position)
            }
        }
        LayerCard(Layer.HANDS, "kick & snare", "Tap the beat: fingertip thumps are kicks, fingernail flicks are snares.", state, vm, host)
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
    Card(colors = CardDefaults.cardColors(containerColor = Palette.surface)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(layer.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Hint(subtitle)
                }
                RecordButton(mine, rec.phase, enabled = !otherBusy) { host.withMicPermission { vm.record(layer) } }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
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

private val gridRows = listOf(Voice.KICK, Voice.SNARE, Voice.HIHAT, Voice.OPEN_HAT, Voice.PERC)

@Composable
private fun StepGrid(state: UiState, vm: MainViewModel, position: HeardPosition) {
    val p = state.project
    val total = p.totalSteps
    val stepsPerBar = p.beatsPerBar * p.stepsPerBeat
    val cellW = 26.dp
    val rowH = 34.dp
    val labelW = 64.dp
    val density = LocalDensity.current
    val cellPx = with(density) { cellW.toPx() }
    val rowPx = with(density) { rowH.toPx() }
    val loopFrames = p.loopFrames(AudioEngine.SAMPLE_RATE)
    val notesByCell = remember(p.notes, p.stepsPerBeat, p.bars, p.beatsPerBar) {
        p.notes.associateBy { gridRows.indexOf(it.voice) to p.stepOf(it) }
    }
    Row {
        Column(Modifier.width(labelW).padding(top = 18.dp)) {
            for (v in gridRows) {
                Row(Modifier.height(rowH), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(Palette.voice(v)))
                    Text(" ${v.label}", fontSize = 12.sp, maxLines = 1)
                }
            }
        }
        Box(Modifier.horizontalScroll(rememberScrollState())) {
            Column {
                Row(Modifier.height(18.dp)) {
                    for (bar in 0 until p.bars) {
                        Text("${bar + 1}", Modifier.width(cellW * stepsPerBar), fontSize = 11.sp, color = Palette.textDim)
                    }
                }
                Canvas(
                    Modifier
                        .width(cellW * total)
                        .height(rowH * gridRows.size)
                        .pointerInput(p.stepsPerBeat, total, notesByCell) {
                            detectTapGestures(
                                onTap = { o -> cell(o, cellPx, rowPx, total)?.let { (r, c) -> vm.toggleStep(gridRows[r], c) } },
                                onLongPress = { o ->
                                    cell(o, cellPx, rowPx, total)?.let { (r, c) ->
                                        if (notesByCell.containsKey(r to c)) vm.cycleVoiceAt(gridRows[r], c)
                                    }
                                },
                            )
                        }
                        .drawWithContent {
                            drawContent()
                            // Playhead: read in the draw phase so it redraws without recomposing.
                            val f = position.frame
                            if ((state.playing || state.record.phase != RecordPhase.IDLE) && f >= 0 && loopFrames > 0) {
                                val x = (f % loopFrames).toFloat() / loopFrames * size.width
                                drawLine(Color.White.copy(alpha = 0.8f), Offset(x, 0f), Offset(x, size.height), strokeWidth = 3f)
                            }
                        },
                ) {
                    for (c in 0 until total) {
                        val beatShade = (c / p.stepsPerBeat) % 2 == 0
                        for (r in gridRows.indices) {
                            drawRoundRect(
                                if (beatShade) Palette.surfaceHigh else Color(0xFF212127),
                                topLeft = Offset(c * cellPx + 1.5f, r * rowPx + 1.5f),
                                size = Size(cellPx - 3f, rowPx - 3f),
                                cornerRadius = CornerRadius(5f, 5f),
                            )
                        }
                        if (c % stepsPerBar == 0 && c > 0) {
                            drawLine(Palette.textDim, Offset(c * cellPx, 0f), Offset(c * cellPx, size.height), strokeWidth = 2f)
                        }
                    }
                    for ((key, note) in notesByCell) {
                        val (r, c) = key
                        if (r < 0) continue
                        val layer = p.layer(note.voice.layer)
                        val dimmed = layer.mode == LayerMode.RAW || layer.muted || p.voice(note.voice).muted
                        val color = Palette.voice(note.voice)
                        val alpha = 0.35f + 0.65f * note.velocity / 127f
                        val tl = Offset(c * cellPx + 3f, r * rowPx + 3f)
                        val sz = Size(cellPx - 6f, rowPx - 6f)
                        if (dimmed) {
                            drawRoundRect(color.copy(alpha = alpha * 0.8f), tl, sz, CornerRadius(5f, 5f), style = Stroke(2.5f))
                        } else {
                            drawRoundRect(color.copy(alpha = alpha), tl, sz, CornerRadius(5f, 5f))
                        }
                    }
                }
            }
        }
    }
    Hint(
        "Outlined hits are detected but not triggered (the layer is in raw mode or muted). " +
            "Grid and swing are in Settings.",
        Modifier.padding(top = 6.dp),
    )
}

private fun cell(o: Offset, cellPx: Float, rowPx: Float, total: Int): Pair<Int, Int>? {
    val c = (o.x / cellPx).toInt()
    val r = (o.y / rowPx).toInt()
    return if (c in 0 until total && r in gridRows.indices) r to c else null
}
