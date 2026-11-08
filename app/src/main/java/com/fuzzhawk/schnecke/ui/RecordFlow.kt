package com.fuzzhawk.schnecke.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fuzzhawk.schnecke.HandsStage
import com.fuzzhawk.schnecke.MainViewModel
import com.fuzzhawk.schnecke.TrimState
import com.fuzzhawk.schnecke.UiState
import com.fuzzhawk.schnecke.audio.AudioEngine
import com.fuzzhawk.schnecke.core.model.Layer
import kotlin.math.abs
import kotlin.math.roundToInt

/** "1 Record · 2 Set loop · 3 Detect" */
@Composable
fun StepHeader(stage: HandsStage) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        val steps = listOf(HandsStage.CAPTURE to "Record", HandsStage.TRIM to "Set loop", HandsStage.LAB to "Detect")
        steps.forEachIndexed { i, (s, label) ->
            val on = s == stage
            val done = s.ordinal < stage.ordinal
            Box(
                Modifier.size(22.dp).clip(CircleShape).background(if (on) Palette.amber else if (done) Palette.surfaceHigh else Palette.surface)
                    .border(1.dp, if (on) Palette.amber else Palette.outline, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text("${i + 1}", fontSize = 12.sp, color = if (on) MaterialTheme.colorScheme.onPrimary else Palette.textDim, fontWeight = FontWeight.Bold)
            }
            Text(label, style = MaterialTheme.typography.labelLarge, color = if (on) Palette.text else Palette.textDim)
            if (i < steps.size - 1) Box(Modifier.weight(1f).height(1.dp).background(Palette.outline))
        }
    }
}

private fun clock(seconds: Double, decimals: Boolean = true): String {
    val m = (seconds / 60).toInt()
    val s = seconds - m * 60
    return if (decimals) "%d:%04.1f".format(m, s) else "%d:%02d".format(m, s.toInt())
}

// ---------------------------------------------------------------------------------- step 1

@Composable
fun CaptureScreen(state: UiState, vm: MainViewModel, host: Host) {
    val cap = state.capture
    val hasLoop = state.project.layer(Layer.HANDS).take != null
    val profile = state.project.layer(Layer.HANDS).profile
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        StepHeader(HandsStage.CAPTURE)
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Palette.surface)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Hands", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text(
                        clock(cap.seconds),
                        style = MaterialTheme.typography.titleLarge,
                        color = if (cap.recording) Palette.record else Palette.textDim,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                LiveWaveform(cap.peaks, cap.recording, Modifier.fillMaxWidth().height(120.dp))
                Box(Modifier.fillMaxWidth().height(230.dp), contentAlignment = Alignment.Center) {
                    // The record button sits in the middle of the shell; the shell turns while recording.
                    SnailSpiral(Modifier.size(230.dp), animate = cap.recording, turns = 1.7f, strokeWidth = 6.dp, innerRatio = 0.45f)
                    Box(
                        Modifier.size(88.dp).clip(CircleShape).background(Palette.background).border(3.dp, Palette.record, CircleShape)
                            .clickable { if (cap.recording) vm.stopCapture() else host.withMicPermission { vm.startCapture() } },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (cap.recording) {
                            Box(Modifier.size(30.dp).clip(RoundedCornerShape(6.dp)).background(Palette.record))
                        } else {
                            Box(Modifier.size(62.dp).clip(CircleShape).background(Palette.record))
                        }
                    }
                }
                Text(
                    if (cap.recording) "Recording… tap your groove a few times through, then stop."
                    else "Tap your beat freely on any surface, two or more times through. No metronome: you mark the loop next.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (cap.recording) Palette.text else Palette.textDim,
                )
                Hint("Fingertip thumps become kicks, fingernail flicks become snares. Stops by itself after ${MainViewModel.MAX_CAPTURE_SECONDS.toInt()} s.")
            }
        }
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Palette.surface)) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Teach your sounds", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Hint(
                        if (profile != null) "Taught ✓ — detection matches your own kick and snare examples."
                        else "Optional: record a few kicks and snares on this surface first so detection knows them.",
                    )
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { host.withMicPermission { vm.startTeach(Layer.HANDS) } }, enabled = !cap.recording) {
                    Icon(Icons.Filled.School, null, Modifier.size(18.dp))
                    Text(if (profile != null) " Redo" else " Teach")
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (hasLoop) TextButton(onClick = { vm.backToLab() }, enabled = !cap.recording) { Text("Back to the lab") }
            else TextButton(onClick = { vm.skipToLab() }, enabled = !cap.recording) { Text("Skip: program beats by hand") }
        }
    }
}

/** Voice-recorder style waveform: newest audio at the right, scrolling left. */
@Composable
private fun LiveWaveform(peaks: FloatArray, recording: Boolean, modifier: Modifier) {
    Canvas(modifier.clip(RoundedCornerShape(10.dp)).background(Palette.background)) {
        val bar = 3.dp.toPx()
        val gap = 1.dp.toPx()
        val slots = (size.width / (bar + gap)).toInt()
        val mid = size.height / 2
        drawLine(Palette.outline, Offset(0f, mid), Offset(size.width, mid), strokeWidth = 1f)
        if (peaks.isEmpty()) return@Canvas
        // Each slot shows the loudest of several 5 ms blocks (about 21 ms per bar).
        val per = 4
        val count = peaks.size / per
        val first = (count - slots).coerceAtLeast(0)
        for (k in first until count) {
            var m = 0f
            for (j in 0 until per) m = maxOf(m, peaks[k * per + j])
            // Square-root scale so quiet taps still show.
            val h = (kotlin.math.sqrt(m.coerceIn(0f, 1f)) * (size.height * 0.47f)).coerceAtLeast(1f)
            val x = size.width - (count - k) * (bar + gap)
            drawRect(
                if (recording) Palette.record.copy(alpha = 0.85f) else Palette.textDim,
                topLeft = Offset(x, mid - h),
                size = Size(bar, h * 2),
            )
        }
    }
}

// ---------------------------------------------------------------------------------- step 2

@Composable
fun TrimScreen(state: UiState, vm: MainViewModel, position: HeardPosition) {
    val t = state.trim ?: return
    val sr = AudioEngine.SAMPLE_RATE
    val bpm = t.bpm(sr)
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        StepHeader(HandsStage.TRIM)
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Palette.surface)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Mark one loop", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Hint("Drag IN to the first beat of your groove and OUT to where it starts again. Handles snap to your hits.")
                TrimWaveform(t, vm, position, state.playing, Modifier.fillMaxWidth().height(150.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Nudge("In", t.inFrame, sr, { vm.nudgeLoopPoint(true, -5) }, { vm.nudgeLoopPoint(true, 5) }, Modifier.weight(1f))
                    Nudge("Out", t.outFrame, sr, { vm.nudgeLoopPoint(false, -5) }, { vm.nudgeLoopPoint(false, 5) }, Modifier.weight(1f))
                }
                Text(
                    "Loop length %.3f s".format((t.outFrame - t.inFrame).toDouble() / sr),
                    style = MaterialTheme.typography.bodySmall, color = Palette.textDim,
                )
            }
        }
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Palette.surface)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("The loop is", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Segmented(listOf("1" to 1, "2" to 2, "4" to 4, "8" to 8), t.bars, { vm.setTrimBars(it) })
                    Text(if (t.bars == 1) "bar of" else "bars of", style = MaterialTheme.typography.bodyMedium)
                    DropChip("${t.beatsPerBar}/4", (2..7).map { "$it/4" to it }) { vm.setTrimBeatsPerBar(it) }
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        "%.1f".format(bpm),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (t.bpmOk(sr)) Palette.text else Palette.record,
                    )
                    Text(" bpm", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 6.dp))
                }
                if (!t.bpmOk(sr)) {
                    Hint(if (bpm > 240) "Too fast: choose more bars, or a longer loop." else "Too slow: choose fewer bars, or a shorter loop.")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { vm.togglePreview() }, enabled = t.bpmOk(sr)) {
                        Icon(if (t.previewing) Icons.Filled.Stop else Icons.Filled.PlayArrow, null, Modifier.size(18.dp))
                        Text(if (t.previewing) " Stop" else " Preview")
                    }
                    Toggle("Click", t.click) { vm.toggleTrimClick() }
                    Toggle("Snap", t.snap) { vm.toggleTrimSnap() }
                    IconButton(onClick = { vm.autoTrim() }) { Icon(Icons.Filled.AutoFixHigh, "Find the loop for me", tint = Palette.amber) }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { vm.reRecordHands() }) { Text("Re-record") }
            Spacer(Modifier.weight(1f))
            Button(onClick = { vm.detectHits() }, enabled = t.bpmOk(sr), modifier = Modifier.height(52.dp)) {
                Text("Detect hits ")
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null)
            }
        }
    }
}

@Composable
private fun Toggle(label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(10.dp)).background(if (on) Palette.amber.copy(alpha = 0.2f) else Palette.surfaceHigh)
            .border(1.dp, if (on) Palette.amber else Palette.surfaceHigh, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 9.dp),
    ) { Text(label, color = if (on) Palette.amber else Palette.textDim, style = MaterialTheme.typography.labelLarge) }
}

@Composable
private fun Nudge(label: String, frame: Int, sr: Int, back: () -> Unit, forward: () -> Unit, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = back, modifier = Modifier.size(36.dp)) { Icon(Icons.Filled.ChevronLeft, "$label earlier") }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = Palette.amber)
            Text("%.3f s".format(frame.toDouble() / sr), style = MaterialTheme.typography.bodyMedium)
        }
        IconButton(onClick = forward, modifier = Modifier.size(36.dp)) { Icon(Icons.Filled.ChevronRight, "$label later") }
    }
}

@Composable
private fun TrimWaveform(t: TrimState, vm: MainViewModel, position: HeardPosition, playing: Boolean, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val snapPx = with(density) { 24.dp.toPx() }
    val labelStyle = TextStyle(color = Color(0xFF231A00), fontSize = 10.sp, fontWeight = FontWeight.Bold)
    var dragIn by remember { mutableStateOf(true) }
    var dragFrame by remember { mutableIntStateOf(0) }
    var widthPx by remember { mutableIntStateOf(1) }
    fun frameAt(x: Float) = (x / widthPx * t.totalFrames).roundToInt().coerceIn(0, t.totalFrames)
    fun xOf(frame: Int) = frame.toFloat() / t.totalFrames * widthPx
    val snapFrames = (snapPx / widthPx * t.totalFrames).roundToInt()
    Canvas(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Palette.background)
            .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
            .pointerInput(t.totalFrames, t.inFrame, t.outFrame, t.snap) {
                detectTapGestures { o ->
                    val isIn = abs(o.x - xOf(t.inFrame)) <= abs(o.x - xOf(t.outFrame))
                    vm.setLoopPoint(isIn, frameAt(o.x), snapFrames)
                }
            }
            .pointerInput(t.totalFrames, t.snap) {
                detectDragGestures(
                    onDragStart = { o ->
                        val cur = vm.state.value.trim ?: return@detectDragGestures
                        dragIn = abs(o.x - xOf(cur.inFrame)) <= abs(o.x - xOf(cur.outFrame))
                        dragFrame = frameAt(o.x)
                        vm.setLoopPoint(dragIn, dragFrame, snapFrames, dragging = true)
                    },
                    onDrag = { change, _ ->
                        dragFrame = frameAt(change.position.x)
                        vm.setLoopPoint(dragIn, dragFrame, snapFrames, dragging = true)
                    },
                    onDragEnd = { vm.setLoopPoint(dragIn, dragFrame, snapFrames) },
                )
            }
            .drawWithContent {
                drawContent()
                // Playhead while previewing, inside the loop.
                val f = position.frame
                if (t.previewing && playing && f >= 0) {
                    val len = t.outFrame - t.inFrame
                    val x = xOf(t.inFrame + (f % len).toInt())
                    drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.5f)
                }
            },
    ) {
        val w = size.width
        val h = size.height
        val xi = xOf(t.inFrame)
        val xo = xOf(t.outFrame)
        drawRect(Palette.surfaceHigh, Offset(xi, 0f), Size(xo - xi, h))
        val n = t.peaks.size
        val bw = w / n
        val mid = h / 2
        for (i in 0 until n) {
            val a = kotlin.math.sqrt(t.peaks[i]) * h * 0.42f
            val x = i * bw
            val inside = x in xi..xo
            drawRect(if (inside) Palette.text.copy(alpha = 0.75f) else Palette.textDim.copy(alpha = 0.35f), Offset(x, mid - a), Size(bw.coerceAtLeast(1f), a * 2 + 1f))
        }
        for (o in t.onsets) {
            val x = xOf(o)
            drawLine(Palette.sky.copy(alpha = 0.8f), Offset(x, h - 8.dp.toPx()), Offset(x, h), strokeWidth = 2f)
        }
        for ((x, label) in listOf(xi to "IN", xo to "OUT")) {
            drawLine(Palette.amber, Offset(x, 0f), Offset(x, h), strokeWidth = 3f)
            val text = measurer.measure(label, labelStyle)
            val tw = text.size.width + 10.dp.toPx()
            val left = if (label == "IN") x else x - tw
            drawRect(Palette.amber, Offset(left, 0f), Size(tw, text.size.height + 4.dp.toPx()))
            drawText(text, topLeft = Offset(left + 5.dp.toPx(), 2.dp.toPx()))
        }
    }
}
