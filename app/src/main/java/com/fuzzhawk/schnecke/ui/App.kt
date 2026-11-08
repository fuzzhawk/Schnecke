package com.fuzzhawk.schnecke.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fuzzhawk.schnecke.HandsStage
import com.fuzzhawk.schnecke.MainViewModel
import com.fuzzhawk.schnecke.RecordPhase
import com.fuzzhawk.schnecke.audio.AudioEngine
import com.fuzzhawk.schnecke.UiState
import java.text.DateFormat
import java.util.Date
import kotlin.math.floor
import kotlin.math.roundToInt

/** Platform actions the UI asks the activity for (permissions, file pickers, sharing). */
interface Host {
    fun withMicPermission(action: () -> Unit)
    fun pickWav(onPicked: (android.net.Uri, String) -> Unit)
    fun saveExport()
    fun shareExport()
}

enum class Tab(val label: String) { LOOP("Loop"), SOUNDS("Sounds"), EXPORT("Export"), SETTINGS("Settings") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchneckeApp(vm: MainViewModel, host: Host) {
    val state by vm.state.collectAsState()
    var tab by rememberSaveable { mutableStateOf(Tab.LOOP) }
    var showProjects by remember { mutableStateOf(false) }
    var intro by rememberSaveable { mutableStateOf(showIntroOnLaunch) }
    val snackbar = remember { SnackbarHostState() }
    val position = rememberHeardPosition(vm, active = state.playing || state.record.phase != RecordPhase.IDLE)

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            vm.dismissMessage()
        }
    }

    Scaffold(
        containerColor = Palette.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column(Modifier.background(Palette.background).padding(top = 8.dp)) {
                ProjectTitle(state, onClick = { vm.refreshProjects(); showProjects = true })
                // While recording or marking the hands loop there is no tempo yet to show.
                if (tab != Tab.LOOP || state.stage == HandsStage.LAB) TransportBar(state, vm, position)
            }
        },
        bottomBar = {
            NavigationBar(containerColor = Palette.surface) {
                for (t in Tab.entries) {
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = {
                            Icon(
                                when (t) {
                                    Tab.LOOP -> Icons.Filled.GraphicEq
                                    Tab.SOUNDS -> Icons.Filled.LibraryMusic
                                    Tab.EXPORT -> Icons.Filled.IosShare
                                    Tab.SETTINGS -> Icons.Filled.Settings
                                },
                                contentDescription = null,
                            )
                        },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                Tab.LOOP -> LoopScreen(state, vm, host, position)
                Tab.SOUNDS -> SoundsScreen(state, vm, host)
                Tab.EXPORT -> ExportScreen(state, vm)
                Tab.SETTINGS -> SettingsScreen(state, vm)
            }
            state.busy?.let { SpiralOverlay(it) }
        }
    }

    if (showProjects) {
        ModalBottomSheet(onDismissRequest = { showProjects = false }, containerColor = Palette.surface) {
            ProjectsSheet(state, vm, onDone = { showProjects = false })
        }
    }
    state.teach?.let { TeachDialog(it, vm, host) }
    state.calibration?.let { CalibrationDialog(it, vm, host) }
    state.exportResult?.let { ExportReadyDialog(it, vm, host) }
    AnimatedVisibility(intro, enter = fadeIn(), exit = fadeOut()) { IntroSplash { intro = false } }
}

/** Turned off in tests, which start straight in the app. */
var showIntroOnLaunch = true

/** The transport position the listener hears, refreshed every frame. */
class HeardPosition {
    var frame by mutableLongStateOf(0L)
}

@Composable
fun rememberHeardPosition(vm: MainViewModel, active: Boolean): HeardPosition {
    val pos = remember { HeardPosition() }
    LaunchedEffect(active) {
        // Follow the audio only while something plays; otherwise stay idle.
        while (active) {
            withFrameNanos { }
            pos.frame = vm.heardPosition()
        }
        pos.frame = 0L
    }
    return pos
}

@Composable
private fun ProjectTitle(state: UiState, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("🐌", fontSize = 20.sp)
        Spacer(Modifier.width(8.dp))
        Text(
            state.project.name,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Icon(Icons.Filled.ArrowDropDown, contentDescription = "Projects", tint = Palette.textDim)
    }
}

@Composable
private fun TransportBar(state: UiState, vm: MainViewModel, position: HeardPosition) {
    val p = state.project
    var bpmDialog by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val busy = state.record.phase != RecordPhase.IDLE
        FilledIconButton(
            onClick = { vm.togglePlay() },
            modifier = Modifier.size(52.dp),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = if (state.playing) Palette.surfaceHigh else Palette.amber),
        ) {
            Icon(
                if (state.playing || busy) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                contentDescription = if (state.playing) "Stop" else "Play",
                tint = if (state.playing) Palette.text else MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(30.dp),
            )
        }
        Chip("${formatBpm(p.bpm)} bpm") { bpmDialog = true }
        DropChip("${p.bars} bar${if (p.bars > 1) "s" else ""}", listOf(1, 2, 4, 8).map { "$it bar${if (it > 1) "s" else ""}" to it }) { vm.setBars(it) }
        DropChip("${p.beatsPerBar}/4", (2..7).map { "$it/4" to it }) { vm.setBeatsPerBar(it) }
        Spacer(Modifier.weight(1f))
        BeatLights(state, position)
    }
    if (bpmDialog) BpmDialog(p.bpm, vm) { bpmDialog = false }
}

fun formatBpm(bpm: Double): String = if (bpm == floor(bpm)) bpm.toInt().toString() else "%.1f".format(bpm)

@Composable
fun Chip(text: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = Palette.surfaceHigh,
    ) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun <T> DropChip(text: String, options: List<Pair<String, T>>, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Chip(text) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for ((label, v) in options) DropdownMenuItem(text = { Text(label) }, onClick = { open = false; onPick(v) })
        }
    }
}

/** Beat lights; during the count-in shows the beats left in big type. */
@Composable
private fun BeatLights(state: UiState, position: HeardPosition) {
    val p = state.project
    val fpb = p.framesPerBeat(AudioEngine.SAMPLE_RATE)
    val active = state.playing || state.record.phase != RecordPhase.IDLE
    val frame = position.frame
    val beat = floor(frame / fpb).toLong()
    if (active && frame < 0 && state.record.phase != RecordPhase.IDLE) {
        Text("${-beat}", style = MaterialTheme.typography.headlineMedium, color = Palette.record, fontWeight = FontWeight.Bold)
        return
    }
    val inBar = if (active) Math.floorMod(beat, p.beatsPerBar.toLong()).toInt() else -1
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        for (i in 0 until p.beatsPerBar) {
            val on = i == inBar
            Box(
                Modifier.size(if (i == 0) 12.dp else 9.dp).clip(CircleShape).background(
                    when {
                        on && state.record.phase == RecordPhase.RECORDING -> Palette.record
                        on -> Palette.amber
                        else -> Palette.outline
                    },
                ),
            )
        }
    }
}

@Composable
private fun BpmDialog(bpm: Double, vm: MainViewModel, onDismiss: () -> Unit) {
    var value by remember { mutableFloatStateOf(bpm.roundToInt().toFloat()) }
    val taps = remember { mutableStateListOf<Long>() }
    var lastTap by remember { mutableLongStateOf(0L) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tempo") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${value.roundToInt()} bpm", style = MaterialTheme.typography.displaySmall)
                Slider(value = value, onValueChange = { value = it }, valueRange = 50f..200f, steps = 149)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { value = (value - 1).coerceAtLeast(40f) }) { Text("−1") }
                    OutlinedButton(onClick = { value = (value + 1).coerceAtMost(240f) }) { Text("+1") }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = {
                        val now = System.currentTimeMillis()
                        if (lastTap != 0L && now - lastTap < 2000) taps.add(now - lastTap) else taps.clear()
                        lastTap = now
                        if (taps.size >= 2) {
                            val sorted = taps.takeLast(6).sorted()
                            value = (60000f / sorted[sorted.size / 2]).coerceIn(40f, 240f)
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                ) { Text(if (taps.isEmpty()) "Tap tempo" else "Tap… (${taps.size + 1})") }
            }
        },
        confirmButton = { TextButton(onClick = { vm.setBpm(value.roundToInt().toDouble()); onDismiss() }) { Text("Set") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ProjectsSheet(state: UiState, vm: MainViewModel, onDone: () -> Unit) {
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.newProject(); onDone() }) { Icon(Icons.Filled.Add, null); Text(" New") }
            OutlinedButton(onClick = { renaming = true }) { Icon(Icons.Filled.Edit, null); Text(" Rename") }
            OutlinedButton(onClick = { vm.duplicate(); onDone() }) { Icon(Icons.Filled.ContentCopy, null); Text(" Copy") }
        }
        Spacer(Modifier.height(12.dp))
        LazyColumn {
            items(state.projects, key = { it.id }) { p ->
                Row(
                    Modifier.fillMaxWidth().clickable { vm.open(p.id); onDone() }.padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            p.name,
                            fontWeight = if (p.id == state.project.id) FontWeight.Bold else FontWeight.Normal,
                            color = if (p.id == state.project.id) Palette.amber else Palette.text,
                        )
                        Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(p.updatedAt)), color = Palette.textDim, fontSize = 12.sp)
                    }
                    IconButton(onClick = { confirmDelete = p.id }) { Icon(Icons.Filled.Delete, "Delete", tint = Palette.textDim) }
                }
                HorizontalDivider(color = Palette.outline)
            }
        }
    }
    if (renaming) {
        var name by remember { mutableStateOf(state.project.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { vm.rename(name); renaming = false; vm.refreshProjects() }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
    confirmDelete?.let { id ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete this loop?") },
            text = { Text("Its recordings are deleted too. This can't be undone.") },
            confirmButton = { TextButton(onClick = { vm.delete(id); confirmDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(top = 4.dp, bottom = 6.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, color = Palette.textDim, style = MaterialTheme.typography.bodySmall)
}

/** A small segmented selector. */
@Composable
fun <T> Segmented(options: List<Pair<String, T>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.clip(RoundedCornerShape(10.dp)).background(Palette.surfaceHigh).padding(3.dp)) {
        for ((label, v) in options) {
            val on = v == selected
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (on) Palette.amber else Palette.surfaceHigh)
                    .clickable { onSelect(v) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            ) {
                Text(label, color = if (on) MaterialTheme.colorScheme.onPrimary else Palette.text, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
