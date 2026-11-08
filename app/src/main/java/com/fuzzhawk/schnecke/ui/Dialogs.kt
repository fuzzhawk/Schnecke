package com.fuzzhawk.schnecke.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fuzzhawk.schnecke.CalibrationPhase
import com.fuzzhawk.schnecke.CalibrationState
import com.fuzzhawk.schnecke.ExportKind
import com.fuzzhawk.schnecke.ExportResult
import com.fuzzhawk.schnecke.MainViewModel
import com.fuzzhawk.schnecke.TeachState
import com.fuzzhawk.schnecke.core.model.Voice
import kotlin.math.roundToInt

private fun teachHint(v: Voice): String = when (v) {
    Voice.KICK -> "Thump the surface with your fingertips, the way you would for a kick."
    Voice.SNARE -> "Flick the surface with your fingernail, the way you would for a snare."
    Voice.HIHAT -> "Beatbox short hi-hats: \"ts\", \"t\"."
    Voice.OPEN_HAT -> "Beatbox long open hats: \"tsss\"."
    Voice.PERC -> "Beatbox your percussion sound: \"k\", \"pf\", a click."
}

@Composable
fun TeachDialog(t: TeachState, vm: MainViewModel, host: Host) {
    val v = t.voice
    val have = t.examples[v]?.size ?: 0
    AlertDialog(
        onDismissRequest = { vm.teachCancel() },
        title = { Text("Teach ${t.layer.label.lowercase()} sounds (${t.index + 1}/${t.voices.size})") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(v.label, style = MaterialTheme.typography.headlineSmall, color = Palette.voice(v), fontWeight = FontWeight.Bold)
                Text(teachHint(v))
                Hint("Press record, make the sound 4–8 times with gaps in between, then press stop. Use the same surface you'll record on.")
                Button(
                    onClick = { host.withMicPermission { vm.teachToggleRecord() } },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (t.recording) Palette.record else Palette.record.copy(alpha = 0.18f),
                        contentColor = Palette.text,
                    ),
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) {
                    Icon(if (t.recording) Icons.Filled.Stop else Icons.Filled.FiberManualRecord, null, tint = if (t.recording) Palette.text else Palette.record)
                    Text(if (t.recording) "  Stop" else if (have > 0) "  Record again" else "  Record")
                }
                Text(
                    t.voices.joinToString("   ") { "${it.label}: ${t.examples[it]?.size ?: 0}" },
                    style = MaterialTheme.typography.labelMedium,
                    color = Palette.textDim,
                )
            }
        },
        confirmButton = {
            Row {
                if (t.index > 0) TextButton(onClick = { vm.teachStep(-1) }) { Text("Back") }
                if (t.index < t.voices.size - 1) {
                    TextButton(onClick = { vm.teachStep(1) }) { Text(if (have > 0) "Next" else "Skip") }
                } else {
                    TextButton(onClick = { vm.teachFinish() }) { Text("Done") }
                }
            }
        },
        dismissButton = { TextButton(onClick = { vm.teachCancel() }) { Text("Cancel") } },
    )
}

@Composable
fun CalibrationDialog(c: CalibrationState, vm: MainViewModel, host: Host) {
    AlertDialog(
        onDismissRequest = { vm.closeCalibration() },
        title = { Text("Calibrate timing") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (c.phase) {
                    CalibrationPhase.READY -> {
                        Text("Put on the headphones you record with. After four count-in clicks, tap a surface exactly on each of the next eight clicks.")
                        Hint("This measures the delay between hearing the click and the microphone hearing your tap.")
                    }
                    CalibrationPhase.RUNNING -> Text("Tap along with every click…", style = MaterialTheme.typography.titleMedium)
                    CalibrationPhase.DONE -> {
                        if (c.resultMs != null) {
                            val ms = c.resultMs.roundToInt()
                            Text(
                                if (ms >= 0) "Your taps landed $ms ms late." else "Your taps landed ${-ms} ms early.",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Hint("Apply it so new recordings line up with the grid.")
                        } else {
                            Text(c.error ?: "Something went wrong.")
                        }
                    }
                }
            }
        },
        confirmButton = {
            when (c.phase) {
                CalibrationPhase.READY -> TextButton(onClick = { host.withMicPermission { vm.runCalibration() } }) { Text("Start") }
                CalibrationPhase.RUNNING -> {}
                CalibrationPhase.DONE -> Row {
                    TextButton(onClick = { host.withMicPermission { vm.runCalibration() } }) { Text("Again") }
                    if (c.resultMs != null) TextButton(onClick = { vm.applyCalibration(c.resultMs) }) { Text("Apply") }
                }
            }
        },
        dismissButton = { TextButton(onClick = { vm.closeCalibration() }) { Text("Close") } },
    )
}

@Composable
fun ExportReadyDialog(r: ExportResult, vm: MainViewModel, host: Host) {
    val size = r.file.length()
    val sizeText = if (size > 1_000_000) "%.1f MB".format(size / 1e6) else "${size / 1000} kB"
    AlertDialog(
        onDismissRequest = { vm.dismissExport() },
        title = { Text("Export ready") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(r.file.name, fontWeight = FontWeight.SemiBold)
                Hint(sizeText)
                if (r.kind == ExportKind.ABLETON) Hint("Unzip on your computer, then open the .als file inside the project folder.")
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { host.shareExport() }) { Text("Share") }
                TextButton(onClick = { host.saveExport() }) { Text("Save to…") }
            }
        },
        dismissButton = { TextButton(onClick = { vm.dismissExport() }) { Text("Close") } },
    )
}
