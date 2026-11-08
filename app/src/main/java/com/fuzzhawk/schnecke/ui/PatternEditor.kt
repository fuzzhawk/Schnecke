package com.fuzzhawk.schnecke.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fuzzhawk.schnecke.MainViewModel
import com.fuzzhawk.schnecke.RecordPhase
import com.fuzzhawk.schnecke.UiState
import com.fuzzhawk.schnecke.audio.AudioEngine
import com.fuzzhawk.schnecke.core.model.LayerMode
import com.fuzzhawk.schnecke.core.model.Note
import com.fuzzhawk.schnecke.core.model.Project
import com.fuzzhawk.schnecke.core.model.Timing
import com.fuzzhawk.schnecke.core.model.Voice
import kotlin.math.abs
import kotlin.math.max

enum class PatternTool(val label: String, val icon: ImageVector) {
    DRAW("Draw", Icons.Filled.Edit),
    SNAP("Snap", Icons.Filled.Brush),
    NUDGE("Nudge", Icons.Filled.SwapHoriz),
}

private val gridRows = listOf(Voice.KICK, Voice.SNARE, Voice.HIHAT, Voice.OPEN_HAT, Voice.PERC)

/** Name of a grid resolution, e.g. 4 steps per beat is "1/16". */
fun gridName(stepsPerBeat: Int): String = when (stepsPerBeat) {
    2 -> "1/8"
    3 -> "1/8T"
    4 -> "1/16"
    6 -> "1/16T"
    8 -> "1/32"
    else -> "1/${stepsPerBeat * 4}"
}

/** Offset of a hit in milliseconds at the project's tempo. */
private fun offsetMs(p: Project, offsetBeats: Double) = offsetBeats * 60000.0 / p.bpm

@Composable
fun PatternEditor(state: UiState, vm: MainViewModel, position: HeardPosition) {
    var tool by rememberSaveable { mutableStateOf(PatternTool.DRAW) }
    var strength by rememberSaveable { mutableFloatStateOf(1f) }
    var selected by rememberSaveable { mutableStateOf<Long?>(null) }
    val p = state.project
    val selectedNote = selected?.let { id -> p.notes.firstOrNull { it.id == id } }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("Pattern", Modifier.weight(1f))
            Hint("grid ${gridName(p.stepsPerBeat)}  ")
            IconButton(onClick = { vm.undo() }, enabled = state.undoDepth > 0) {
                Icon(Icons.AutoMirrored.Filled.Undo, "Undo")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (t in PatternTool.entries) ToolChip(t, t == tool) { tool = t; if (t != PatternTool.NUDGE) selected = null }
        }
        when (tool) {
            PatternTool.DRAW -> Hint("Tap a cell to add or remove a hit. Hold a hit to change its sound.")
            PatternTool.SNAP -> Row(verticalAlignment = Alignment.CenterVertically) {
                Hint("Brush over hits to snap them to the grid.", Modifier.weight(1f))
                Segmented(listOf("50%" to 0.5f, "100%" to 1f), strength, { strength = it })
                TextButton(onClick = { vm.snapAll(strength.toDouble()) }) { Text("All") }
            }
            PatternTool.NUDGE -> Hint("Tap a hit to select it, then drag it or use the slider to move it off the grid.")
        }
        PatternGrid(state, vm, position, tool, strength.toDouble(), selectedNote?.id) { selected = it }
        if (tool == PatternTool.NUDGE && selectedNote != null) {
            NudgePanel(p, selectedNote, vm) { selected = null }
        }
        Hint("Outlined hits are detected but not played (raw-audio layer or muted). Hits off the grid sit off their cell's edge.")
    }
}

@Composable
private fun ToolChip(tool: PatternTool, on: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.testTag("tool-${tool.name}").clip(RoundedCornerShape(10.dp))
            .background(if (on) Palette.amber else Palette.surfaceHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val color = if (on) MaterialTheme.colorScheme.onPrimary else Palette.text
        Icon(tool.icon, null, Modifier.size(18.dp), tint = color)
        Text(" ${tool.label}", color = color, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun PatternGrid(
    state: UiState,
    vm: MainViewModel,
    position: HeardPosition,
    tool: PatternTool,
    strength: Double,
    selectedId: Long?,
    onSelect: (Long?) -> Unit,
) {
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

    fun cellAt(o: Offset): Pair<Int, Int>? {
        val c = (o.x / cellPx).toInt()
        val r = (o.y / rowPx).toInt()
        return if (o.x >= 0 && c in 0 until total && r in gridRows.indices) r to c else null
    }

    /** The hit drawn under [o] (hits are drawn shifted by their offset), else the one in that cell. */
    fun noteAt(o: Offset): Note? {
        val cur = vm.state.value.project
        val r = (o.y / rowPx).toInt()
        val voice = gridRows.getOrNull(r) ?: return null
        val inRow = cur.notes.filter { it.voice == voice }
        val drawn = inRow.firstOrNull { n ->
            val x = (cur.stepOf(n) + cur.offsetOf(n) * cur.stepsPerBeat) * cellPx
            o.x in x..(x + cellPx)
        }
        return drawn ?: cellAt(o)?.let { (_, c) -> inRow.firstOrNull { cur.stepOf(it) == c } }
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
                val gestures = when (tool) {
                    PatternTool.DRAW -> Modifier.pointerInput(tool, total, p.stepsPerBeat) {
                        detectTapGestures(
                            onTap = { o -> cellAt(o)?.let { (r, c) -> vm.toggleStep(gridRows[r], c) } },
                            onLongPress = { o ->
                                val cur = vm.state.value.project
                                cellAt(o)?.let { (r, c) ->
                                    if (cur.notes.any { it.voice == gridRows[r] && cur.stepOf(it) == c }) vm.cycleVoiceAt(gridRows[r], c)
                                }
                            },
                        )
                    }
                    PatternTool.SNAP -> Modifier
                        .pointerInput(tool, total, p.stepsPerBeat, strength) {
                            detectTapGestures { o ->
                                cellAt(o)?.let { (r, c) -> vm.checkpoint(); vm.snapAt(gridRows[r], c, strength) }
                            }
                        }
                        .pointerInput(tool, total, p.stepsPerBeat, strength) {
                            var last = Offset.Zero
                            detectDragGestures(
                                onDragStart = { o ->
                                    vm.checkpoint()
                                    last = o
                                    cellAt(o)?.let { (r, c) -> vm.snapAt(gridRows[r], c, strength) }
                                },
                                onDrag = { change, _ ->
                                    // Visit every cell between the last and current point, so fast strokes skip nothing.
                                    val to = change.position
                                    val steps = max(1, ((to - last).getDistance() / (cellPx / 3)).toInt())
                                    for (i in 1..steps) {
                                        val pt = last + (to - last) * (i / steps.toFloat())
                                        cellAt(pt)?.let { (r, c) -> vm.snapAt(gridRows[r], c, strength) }
                                    }
                                    last = to
                                    change.consume()
                                },
                            )
                        }
                    PatternTool.NUDGE -> Modifier
                        .pointerInput(tool, total, p.stepsPerBeat) {
                            detectTapGestures { o -> onSelect(noteAt(o)?.id) }
                        }
                        .pointerInput(tool, total, p.stepsPerBeat) {
                            var dragId: Long? = null
                            var startOffset = 0.0
                            var dx = 0f
                            detectDragGestures(
                                onDragStart = { o ->
                                    val n = noteAt(o)
                                    dragId = n?.id
                                    if (n != null) {
                                        onSelect(n.id)
                                        vm.checkpoint()
                                        startOffset = vm.state.value.project.offsetOf(n)
                                        dx = 0f
                                    }
                                },
                                onDrag = { change, amount ->
                                    val id = dragId ?: return@detectDragGestures
                                    dx += amount.x
                                    val stepBeats = vm.state.value.project.stepBeats
                                    vm.setOffset(id, startOffset + dx / cellPx * stepBeats)
                                    change.consume()
                                },
                            )
                        }
                }
                Canvas(
                    Modifier
                        .width(cellW * total)
                        .height(rowH * gridRows.size)
                        .testTag("pattern-grid")
                        .then(gestures)
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
                    for (note in p.notes) {
                        val r = gridRows.indexOf(note.voice)
                        if (r < 0) continue
                        val c = p.stepOf(note)
                        val shift = (p.offsetOf(note) * p.stepsPerBeat).toFloat()
                        val layer = p.layer(note.voice.layer)
                        val dimmed = layer.mode == LayerMode.RAW || layer.muted || p.voice(note.voice).muted
                        val color = Palette.voice(note.voice)
                        val alpha = 0.35f + 0.65f * note.velocity / 127f
                        val sz = Size(cellPx - 6f, rowPx - 6f)
                        val grid = Offset(c * cellPx + 3f, r * rowPx + 3f)
                        val tl = Offset(grid.x + shift * cellPx, grid.y)
                        if (abs(shift) > 0.03f) {
                            // Where the grid is, faintly, so the offset reads at a glance.
                            drawRoundRect(color.copy(alpha = 0.22f), grid, sz, CornerRadius(5f, 5f), style = Stroke(1.5f))
                        }
                        if (dimmed) {
                            drawRoundRect(color.copy(alpha = alpha * 0.8f), tl, sz, CornerRadius(5f, 5f), style = Stroke(2.5f))
                        } else {
                            drawRoundRect(color.copy(alpha = alpha), tl, sz, CornerRadius(5f, 5f))
                        }
                        if (note.id == selectedId) {
                            drawRoundRect(Color.White, Offset(tl.x - 2f, tl.y - 2f), Size(sz.width + 4f, sz.height + 4f), CornerRadius(6f, 6f), style = Stroke(3f))
                        }
                    }
                }
            }
        }
    }
}

/** The beat-offset parameter of one hit. */
@Composable
private fun NudgePanel(p: Project, note: Note, vm: MainViewModel, onClose: () -> Unit) {
    val step = p.stepOf(note)
    val stepsPerBar = p.beatsPerBar * p.stepsPerBeat
    val where = "bar ${step / stepsPerBar + 1} · beat ${(step % stepsPerBar) / p.stepsPerBeat + 1} · step ${step % p.stepsPerBeat + 1}"
    val offset = p.offsetOf(note)
    val ms = offsetMs(p, offset)
    val pct = offset / p.stepBeats * 100
    var sliding by remember { mutableStateOf(false) }
    val oneMsBeats = p.bpm / 60000.0
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.surfaceHigh)
            .border(1.dp, Palette.outline, RoundedCornerShape(12.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(Palette.voice(note.voice)))
            Text("  ${note.voice.label}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text("  $where", style = MaterialTheme.typography.bodySmall, color = Palette.textDim, modifier = Modifier.weight(1f))
            IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.Close, "Close") }
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text("Offset ", style = MaterialTheme.typography.bodyMedium)
            Text(
                "%+.1f ms".format(ms),
                style = MaterialTheme.typography.titleLarge,
                color = if (abs(ms) < 0.05) Palette.text else Palette.amber,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "  ${if (abs(pct) < 0.5) "on the grid" else "%+.0f%% of a %s step, %s".format(pct, gridName(p.stepsPerBeat), if (offset < 0) "early" else "late")}",
                style = MaterialTheme.typography.bodySmall, color = Palette.textDim, modifier = Modifier.padding(bottom = 3.dp),
            )
        }
        val limit = Timing.MAX_OFFSET_STEPS.toFloat()
        Slider(
            value = (offset / p.stepBeats).toFloat().coerceIn(-limit, limit),
            onValueChange = { v ->
                if (!sliding) { vm.checkpoint(); sliding = true }
                vm.setOffset(note.id, v * p.stepBeats)
            },
            onValueChangeFinished = { sliding = false },
            valueRange = -limit..limit,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { vm.checkpoint(); vm.setOffset(note.id, offset - oneMsBeats) }) { Text("−1 ms") }
            OutlinedButton(onClick = { vm.checkpoint(); vm.setOffset(note.id, offset + oneMsBeats) }) { Text("+1 ms") }
            Spacer(Modifier.weight(1f))
            Button(onClick = { vm.checkpoint(); vm.setOffset(note.id, 0.0) }, enabled = abs(ms) >= 0.05) { Text("Snap") }
        }
        TextButton(onClick = { vm.deleteNote(note.id); onClose() }) { Text("Delete this hit", color = Palette.record) }
    }
}
