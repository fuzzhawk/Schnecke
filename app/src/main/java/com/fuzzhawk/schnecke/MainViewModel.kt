package com.fuzzhawk.schnecke

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fuzzhawk.schnecke.audio.AudioEngine
import com.fuzzhawk.schnecke.core.audio.SnapshotBuilder
import com.fuzzhawk.schnecke.core.dsp.HitFeatures
import com.fuzzhawk.schnecke.core.dsp.HitClassifier
import com.fuzzhawk.schnecke.core.dsp.LatencyCalibrator
import com.fuzzhawk.schnecke.core.dsp.LoopFinder
import com.fuzzhawk.schnecke.core.dsp.LoopPoints
import com.fuzzhawk.schnecke.core.dsp.OnsetDetector
import com.fuzzhawk.schnecke.core.dsp.RawLoopBuilder
import com.fuzzhawk.schnecke.core.dsp.SampleCutter
import com.fuzzhawk.schnecke.core.dsp.TakeAnalysis
import com.fuzzhawk.schnecke.core.dsp.TakeAnalyzer
import com.fuzzhawk.schnecke.core.export.ExportInputs
import com.fuzzhawk.schnecke.core.export.ProjectExporter
import com.fuzzhawk.schnecke.core.export.Wav
import com.fuzzhawk.schnecke.core.model.Layer
import com.fuzzhawk.schnecke.core.model.LayerMode
import com.fuzzhawk.schnecke.core.model.LayerState
import com.fuzzhawk.schnecke.core.model.Note
import com.fuzzhawk.schnecke.core.model.Project
import com.fuzzhawk.schnecke.core.model.SampleSource
import com.fuzzhawk.schnecke.core.model.TakeInfo
import com.fuzzhawk.schnecke.core.model.Timing
import com.fuzzhawk.schnecke.core.model.Voice
import com.fuzzhawk.schnecke.core.model.VoiceSettings
import com.fuzzhawk.schnecke.core.synth.DrumSynth
import com.fuzzhawk.schnecke.data.AppSettings
import com.fuzzhawk.schnecke.data.AutoMode
import com.fuzzhawk.schnecke.data.ProjectStore
import com.fuzzhawk.schnecke.data.ProjectSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class RecordPhase { IDLE, PREPARING, COUNT_IN, RECORDING, ANALYZING }

/** The hands layer is made in three steps: record freely, mark the loop, then the detection lab. */
enum class HandsStage { CAPTURE, TRIM, LAB }

/** A free (metronome-less) recording in progress. */
data class CaptureState(
    val recording: Boolean = false,
    /** Live waveform: one peak per [AudioEngine.PEAK_BLOCK] frames. */
    val peaks: FloatArray = FloatArray(0),
    val seconds: Double = 0.0,
)

/** Loop points being set on a free recording. */
data class TrimState(
    val totalFrames: Int,
    /** Overview of the whole recording, [TRIM_BUCKETS] peaks. */
    val peaks: FloatArray,
    val onsets: IntArray,
    val inFrame: Int,
    val outFrame: Int,
    val bars: Int,
    val beatsPerBar: Int,
    val snap: Boolean = true,
    val previewing: Boolean = false,
    val click: Boolean = false,
) {
    val beats: Int get() = bars * beatsPerBar
    fun bpm(sampleRate: Int): Double = LoopFinder.bpm(inFrame, outFrame, beats, sampleRate)
    fun bpmOk(sampleRate: Int): Boolean = bpm(sampleRate) in LoopFinder.MIN_BPM..LoopFinder.MAX_BPM

    companion object {
        const val TRIM_BUCKETS = 900
    }
}

data class RecordState(val layer: Layer? = null, val phase: RecordPhase = RecordPhase.IDLE)

/** What the waveform strip of a layer shows. */
class LayerView(
    val peaks: FloatArray,
    /** Hit positions as fractions of the strip, with their voice. */
    val markers: List<Pair<Float, Voice>>,
    val loopStart: Float,
    val loopEnd: Float,
    val counts: Map<Voice, Int>,
    val usedTimestamps: Boolean,
)

data class TeachState(
    val layer: Layer,
    val index: Int = 0,
    val recording: Boolean = false,
    val examples: Map<Voice, List<HitFeatures>> = emptyMap(),
) {
    val voices: List<Voice> get() = Voice.of(layer)
    val voice: Voice get() = voices[index]
}

enum class CalibrationPhase { READY, RUNNING, DONE }

data class CalibrationState(val phase: CalibrationPhase = CalibrationPhase.READY, val resultMs: Double? = null, val error: String? = null)

enum class ExportKind(val ext: String, val mime: String) {
    MIDI("mid", "audio/midi"),
    WAV("wav", "audio/wav"),
    ABLETON("zip", "application/zip"),
}

data class ExportResult(val file: File, val kind: ExportKind)

data class UiState(
    val project: Project,
    val settings: AppSettings,
    val record: RecordState = RecordState(),
    val playing: Boolean = false,
    val layerViews: Map<Layer, LayerView> = emptyMap(),
    val recordedCandidates: Map<Voice, Int> = emptyMap(),
    val projects: List<ProjectSummary> = emptyList(),
    val message: String? = null,
    val busy: String? = null,
    val exportResult: ExportResult? = null,
    val teach: TeachState? = null,
    val calibration: CalibrationState? = null,
    val headphones: Boolean = false,
    val stage: HandsStage = HandsStage.CAPTURE,
    val capture: CaptureState = CaptureState(),
    val trim: TrimState? = null,
    /** Hits are being detected (shows the snail spiral). */
    val analyzing: Boolean = false,
    /** Pattern edits that can be undone. */
    val undoDepth: Int = 0,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val engine = AudioEngine(app)
    private val store = ProjectStore(app)
    private val sr = engine.sampleRate
    private val analyzer = TakeAnalyzer(sr)

    private val _state: MutableStateFlow<UiState>
    val state: StateFlow<UiState> get() = _state

    // Audio that belongs to the open project, kept in memory.
    private val takeAudio = HashMap<Layer, FloatArray>()
    private val analyses = HashMap<Layer, TakeAnalysis>()
    private val rawLoops = HashMap<Layer, FloatArray>()
    private val recordedSamples = HashMap<Voice, List<FloatArray>>()
    private val customSamples = HashMap<Voice, FloatArray>()
    private val libraryCache = ConcurrentHashMap<String, FloatArray>()

    private var recordJob: Job? = null
    private var saveJob: Job? = null
    private var captureJob: Job? = null

    /** While recording: the layers that should be heard (the one being recorded is left out). */
    private var recordingInclude: Set<Layer>? = null

    /** Earlier versions of the hits, for undo (declared before init, which opens a project). */
    private val undoStack = ArrayDeque<List<Note>>()

    init {
        val settings = store.loadSettings()
        val project = settings.lastProjectId?.let { store.load(it) } ?: store.newProject("Loop 1").also { store.save(it) }
        _state = MutableStateFlow(UiState(project = project, settings = settings.copy(lastProjectId = project.id)))
        engine.start()
        applyEngineSettings()
        viewModelScope.launch(Dispatchers.Default) {
            for (s in DrumSynth.library) libraryCache.getOrPut(s.id) { DrumSynth.render(s.id, sr) }
        }
        openProject(project)
        refreshProjects()
    }

    override fun onCleared() {
        saveNow()
        engine.stop()
    }

    private val project get() = _state.value.project
    private val settings get() = _state.value.settings

    // ------------------------------------------------------------------ project lifecycle

    private fun openProject(p: Project) {
        captureJob?.cancel()
        undoStack.clear()
        takeAudio.clear(); analyses.clear(); rawLoops.clear(); recordedSamples.clear(); customSamples.clear()
        val handsDone = p.layer(Layer.HANDS).take != null || p.notes.any { it.voice.layer == Layer.HANDS }
        _state.update {
            it.copy(
                project = p, layerViews = emptyMap(), recordedCandidates = emptyMap(), trim = null,
                capture = CaptureState(), stage = if (handsDone) HandsStage.LAB else HandsStage.CAPTURE,
                undoDepth = 0,
            )
        }
        updateSettings { it.copy(lastProjectId = p.id) }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                for (l in Layer.entries) store.loadTake(p.id, l)?.let { takeAudio[l] = it }
                for (v in Voice.entries) store.loadCustom(p.id, v)?.let { customSamples[v] = it }
            }
            for (l in Layer.entries) if (takeAudio[l] != null) reanalyze(l, replaceNotes = false)
            // A recording whose loop was never marked: carry on where it was left.
            if (p.layer(Layer.HANDS).take == null && takeAudio[Layer.HANDS] != null) openTrim(fresh = true)
            rebuildSnapshot()
        }
        rebuildSnapshot()
    }

    fun refreshProjects() {
        viewModelScope.launch(Dispatchers.IO) {
            val list = store.list()
            _state.update { it.copy(projects = list) }
        }
    }

    fun newProject() {
        stop()
        saveNow()
        val n = (_state.value.projects.size + 1)
        val p = store.newProject("Loop $n").copy(bpm = project.bpm, bars = project.bars, beatsPerBar = project.beatsPerBar)
        store.save(p)
        openProject(p)
        refreshProjects()
    }

    fun open(id: String) {
        if (id == project.id) return
        stop()
        saveNow()
        store.load(id)?.let { openProject(it) }
    }

    fun rename(name: String) = edit { it.copy(name = name.ifBlank { it.name }) }

    fun duplicate() {
        saveNow()
        val copy = store.duplicate(project, project.name + " copy")
        stop()
        openProject(copy)
        refreshProjects()
    }

    fun delete(id: String) {
        store.delete(id)
        if (id == project.id) {
            stop()
            val next = store.list().firstOrNull()?.let { store.load(it.id) } ?: store.newProject("Loop 1").also { store.save(it) }
            openProject(next)
        }
        refreshProjects()
    }

    private fun edit(rebuild: Boolean = true, f: (Project) -> Project) {
        _state.update { it.copy(project = f(it.project).copy(updatedAt = System.currentTimeMillis())) }
        if (rebuild) rebuildSnapshot()
        scheduleSave()
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(800)
            val p = project
            withContext(Dispatchers.IO) { store.save(p) }
            refreshProjects()
        }
    }

    fun saveNow() {
        saveJob?.cancel()
        store.save(project)
        store.saveSettings(settings)
    }

    fun updateSettings(f: (AppSettings) -> AppSettings) {
        _state.update { it.copy(settings = f(it.settings)) }
        applyEngineSettings()
        viewModelScope.launch(Dispatchers.IO) { store.saveSettings(settings) }
    }

    private fun applyEngineSettings() {
        val s = settings
        engine.renderer.clickGain = s.clickVolume
        engine.hapticsEnabled = s.haptics
        if (_state.value.record.phase == RecordPhase.IDLE) engine.renderer.clickDuringLoop = s.clickDuringPlayback
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }
    private fun message(m: String) = _state.update { it.copy(message = m) }

    // ------------------------------------------------------------------ sounds

    private fun library(id: String) = libraryCache.getOrPut(id) { DrumSynth.render(id, sr) }

    private fun fallbackLibrary(v: Voice) = when (v) {
        Voice.KICK -> "kick_punch"
        Voice.SNARE -> "snare_tight"
        Voice.HIHAT -> "hat_closed"
        Voice.OPEN_HAT -> "hat_open"
        Voice.PERC -> "rim"
    }

    /** The one-shot each voice plays right now. */
    fun soundFor(v: Voice): FloatArray? = when (val s = project.voice(v).source) {
        is SampleSource.Library -> library(s.id)
        is SampleSource.Recorded -> recordedSamples[v]?.let { it.getOrNull(s.candidate % max(1, it.size)) } ?: library(fallbackLibrary(v))
        is SampleSource.Custom -> customSamples[v] ?: library(fallbackLibrary(v))
    }

    private fun sounds(): Map<Voice, FloatArray?> = Voice.entries.associateWith { soundFor(it) }

    private fun rebuildSnapshot(include: Set<Layer> = recordingInclude ?: Layer.entries.toSet()) {
        engine.renderer.snapshot = SnapshotBuilder.build(project, sr, sounds(), rawLoops.toMap(), include)
        _state.update { it.copy(headphones = engine.headphonesConnected()) }
    }

    fun preview(v: Voice) {
        soundFor(v)?.let { engine.renderer.preview(it, SnapshotBuilder.dbToGain(project.voice(v).gainDb)) }
    }

    fun previewLibrary(id: String) = engine.renderer.preview(library(id))

    fun setVoiceSource(v: Voice, source: SampleSource) {
        editVoice(v) { it.copy(source = source) }
        preview(v)
    }

    fun nextRecordedCandidate(v: Voice) {
        val src = project.voice(v).source as? SampleSource.Recorded ?: return
        val n = recordedSamples[v]?.size ?: 0
        if (n == 0) return
        editVoice(v) { it.copy(source = SampleSource.Recorded((src.candidate + 1) % n)) }
        preview(v)
    }

    fun editVoice(v: Voice, f: (VoiceSettings) -> VoiceSettings) =
        edit { p -> p.copy(voices = p.voices + (v to f(p.voice(v)))) }

    fun importCustom(v: Voice, uri: Uri, displayName: String) {
        viewModelScope.launch {
            try {
                val audio = withContext(Dispatchers.IO) {
                    val bytes = getApplication<Application>().contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                    val d = Wav.decode(bytes)
                    val x = Wav.resample(d.mono, d.sampleRate, sr)
                    x.copyOf(min(x.size, sr * 6))
                }
                customSamples[v] = audio
                withContext(Dispatchers.IO) { store.saveCustom(project.id, v, audio) }
                setVoiceSource(v, SampleSource.Custom(displayName))
            } catch (e: Exception) {
                message("Couldn't read that file: ${e.message ?: "unsupported format"}. Use a WAV file.")
            }
        }
    }

    // ------------------------------------------------------------------ transport

    fun togglePlay() = if (engine.renderer.isPlaying || _state.value.playing) stop() else play()

    fun play() {
        if (_state.value.record.phase != RecordPhase.IDLE || _state.value.capture.recording) return
        if (_state.value.stage == HandsStage.TRIM && _state.value.trim != null) { togglePreview(); return }
        rebuildSnapshot()
        engine.renderer.clickDuringLoop = settings.clickDuringPlayback
        engine.renderer.start(0)
        _state.update { it.copy(playing = true) }
    }

    fun stop() {
        if (_state.value.record.phase != RecordPhase.IDLE) cancelRecording()
        if (_state.value.capture.recording) stopCapture()
        val t = _state.value.trim
        if (t?.previewing == true) {
            stopPreview()
            return
        }
        engine.renderer.stop()
        _state.update { it.copy(playing = false) }
    }

    /** Transport position currently heard, for the playhead and beat lights. */
    fun heardPosition(): Long = engine.heardTransportFrame()

    // ------------------------------------------------------------------ loop settings

    fun setBpm(bpm: Double) = edit { it.copy(bpm = bpm.coerceIn(40.0, 240.0)) }

    fun tapTempo(intervalsMs: List<Long>) {
        if (intervalsMs.size < 2) return
        val avg = intervalsMs.takeLast(6).sorted().let { it[it.size / 2] }
        if (avg in 250..1500) setBpm((60000.0 / avg * 10).roundToInt() / 10.0)
    }

    fun setBars(bars: Int) = edit { p ->
        val newBeats = (bars * p.beatsPerBar).toDouble()
        val oldBeats = p.loopBeats
        val notes = if (newBeats > oldBeats) {
            // Longer loop: repeat what is there.
            val reps = kotlin.math.ceil(newBeats / oldBeats).toInt()
            var id = p.nextNoteId
            (0 until reps).flatMap { r -> p.notes.map { n -> n.copy(id = id++, beat = n.beat + r * oldBeats) } }
                .filter { it.beat < newBeats }
        } else p.notes.filter { it.beat < newBeats - 0.5 / p.stepsPerBeat }
        p.copy(bars = bars, notes = notes, nextNoteId = p.nextNoteId + notes.size + 1)
    }

    fun setBeatsPerBar(n: Int) = edit { p -> p.copy(beatsPerBar = n, notes = p.notes.filter { it.beat < p.bars * n }) }
    fun setStepsPerBeat(n: Int) = edit { it.copy(stepsPerBeat = n) }
    fun setSwing(s: Float) = edit { it.copy(swingPercent = s) }

    fun editLayer(l: Layer, rebuild: Boolean = true, f: (LayerState) -> LayerState) =
        edit(rebuild) { p -> p.copy(layers = p.layers + (l to f(p.layer(l)))) }

    fun setLayerMode(l: Layer, mode: LayerMode) = editLayer(l) { it.copy(mode = mode) }

    fun setSensitivity(l: Layer, s: Float) {
        editLayer(l, rebuild = false) { it.copy(sensitivity = s) }
        if (takeAudio[l] != null) viewModelScope.launch { reanalyze(l, replaceNotes = true); rebuildSnapshot() }
    }

    fun setGate(l: Layer, on: Boolean) {
        editLayer(l, rebuild = false) { it.copy(gateRaw = on) }
        viewModelScope.launch { rebuildRaw(l); rebuildSnapshot() }
    }

    fun clearLayer(l: Layer) {
        takeAudio.remove(l); analyses.remove(l); rawLoops.remove(l)
        Voice.of(l).forEach { recordedSamples.remove(it) }
        viewModelScope.launch(Dispatchers.IO) { store.deleteTake(project.id, l) }
        edit { p -> p.copy(notes = p.notes.filter { it.voice.layer != l }, layers = p.layers + (l to p.layer(l).copy(take = null))) }
        _state.update {
            it.copy(
                layerViews = it.layerViews - l,
                stage = if (l == Layer.HANDS) HandsStage.CAPTURE else it.stage,
                trim = if (l == Layer.HANDS) null else it.trim,
            )
        }
    }

    // ------------------------------------------------------------------ step editing

    /** Remembers the hits as they are now, so the next edit can be undone. */
    fun checkpoint() {
        undoStack.addLast(project.notes)
        while (undoStack.size > 40) undoStack.removeFirst()
        _state.update { it.copy(undoDepth = undoStack.size) }
    }

    fun undo() {
        val notes = undoStack.removeLastOrNull() ?: return
        edit { it.copy(notes = notes) }
        _state.update { it.copy(undoDepth = undoStack.size) }
    }

    /** Tap on a grid cell: add a hit there, or remove the one that is there. */
    fun toggleStep(v: Voice, step: Int) {
        checkpoint()
        edit { p ->
            val existing = p.notes.filter { it.voice == v && p.stepOf(it) == step }
            if (existing.isNotEmpty()) p.copy(notes = p.notes - existing.toSet())
            else p.copy(
                notes = p.notes + Note(p.nextNoteId, v, step.toDouble() / p.stepsPerBeat, 100),
                nextNoteId = p.nextNoteId + 1,
            )
        }
    }

    /** Long-press on a hit: it was misheard, move it to the next voice of its layer. */
    fun cycleVoiceAt(v: Voice, step: Int) {
        checkpoint()
        edit { p ->
            val voices = Voice.of(v.layer)
            val next = voices[(voices.indexOf(v) + 1) % voices.size]
            val moved = p.notes.map { if (it.voice == v && p.stepOf(it) == step) it.copy(voice = next) else it }
            // Avoid two hits of the same voice on one step.
            val dedup = moved.groupBy { it.voice to p.stepOf(it) }.values.map { g -> g.maxBy { it.velocity } }
            p.copy(notes = dedup)
        }
    }

    /**
     * The snap brush passing over a cell: the hit there moves [strength] of the way onto the
     * grid. Call [checkpoint] once when a stroke starts.
     */
    fun snapAt(v: Voice, step: Int, strength: Double) {
        val p = project
        if (p.notes.none { it.voice == v && p.stepOf(it) == step && abs(p.offsetOf(it)) > 1e-9 }) return
        edit { cur -> cur.copy(notes = cur.notes.map { if (it.voice == v && cur.stepOf(it) == step) cur.snapped(it, strength) else it }) }
    }

    fun snapAll(strength: Double) {
        if (project.notes.all { abs(project.offsetOf(it)) < 1e-9 }) return
        checkpoint()
        edit { p -> p.copy(notes = p.notes.map { p.snapped(it, strength) }) }
    }

    /** Places hit [noteId] [offsetBeats] off its grid step. Call [checkpoint] before a series of these. */
    fun setOffset(noteId: Long, offsetBeats: Double) = edit { p ->
        p.copy(notes = p.notes.map { if (it.id == noteId) p.withOffset(it, offsetBeats) else it })
    }

    fun deleteNote(noteId: Long) {
        checkpoint()
        edit { p -> p.copy(notes = p.notes.filter { it.id != noteId }) }
    }

    fun clearNotes(l: Layer) {
        checkpoint()
        edit { p -> p.copy(notes = p.notes.filter { it.voice.layer != l }) }
    }

    // ------------------------------------------------------------------ recording

    private fun AutoMode.resolve(headphones: Boolean) = when (this) {
        AutoMode.ON -> true
        AutoMode.OFF -> false
        AutoMode.AUTO -> headphones
    }

    fun record(layer: Layer) {
        if (_state.value.record.phase != RecordPhase.IDLE) { cancelRecording(); return }
        recordJob = viewModelScope.launch {
            val p = project
            val s = settings
            val headphones = engine.headphonesConnected()
            _state.update { it.copy(record = RecordState(layer, RecordPhase.PREPARING), playing = true, headphones = headphones) }
            engine.renderer.stop()
            // Beatbox over the hands loop: it plays through the count-in and the take.
            val monitor = layer == Layer.BEATBOX && s.playHandsWhileBeatboxing
            recordingInclude = if (monitor) setOf(Layer.HANDS) else emptySet()
            rebuildSnapshot()
            engine.renderer.playLoopDuringCountIn = monitor
            engine.renderer.clickDuringLoop = s.clickWhileRecording.resolve(headphones)
            if (!engine.startRecording()) {
                message("The microphone isn't available.")
                finishRecording()
                return@launch
            }
            delay(250) // let both streams report timestamps
            val countIn = s.countInBars * p.beatsPerBar
            engine.renderer.start(countIn)
            val loopFrames = p.loopFrames(sr)
            val tail = (0.35 * sr).toLong()
            delay(30)
            while (engine.renderer.transportFrame < loopFrames + tail) {
                val phase = if (engine.renderer.transportFrame < 0) RecordPhase.COUNT_IN else RecordPhase.RECORDING
                if (_state.value.record.phase != phase) _state.update { it.copy(record = RecordState(layer, phase)) }
                delay(25)
            }
            val loopStartRendered = engine.renderer.loopStartRenderedFrame
            val rec = engine.stopRecording() ?: run { finishRecording(); return@launch }
            engine.renderer.clickDuringLoop = s.clickDuringPlayback
            _state.update { it.copy(record = RecordState(layer, RecordPhase.ANALYZING)) }

            val fpb = p.framesPerBeat(sr)
            val loopStartInput = rec.inputFrameOf(loopStartRendered) + s.latencyMs / 1000.0 * sr
            val keepFrom = (loopStartInput - fpb).toInt().coerceIn(0, max(0, rec.audio.size - 1))
            val audio = rec.audio.copyOfRange(keepFrom, rec.audio.size)
            val take = TakeInfo(sr, audio.size, (loopStartInput - keepFrom).roundToInt().coerceAtLeast(0), p.bpm, p.loopBeats)
            applyTake(layer, audio, take, rec.usedTimestamps)
            if (!rec.usedTimestamps) {
                message("This phone didn't report audio timing; if hits land early or late, run Settings → Calibrate.")
            }
            finishRecording()
        }
    }

    /** Store a new take for [layer], detect its hits and make it sound (recording ends here). */
    internal suspend fun applyTake(layer: Layer, audio: FloatArray, take: TakeInfo, usedTimestamps: Boolean = true) {
        takeAudio[layer] = audio
        withContext(Dispatchers.IO) { store.saveTake(project.id, layer, audio) }
        editLayer(layer, rebuild = false) { it.copy(take = take) }
        reanalyze(layer, replaceNotes = true, usedTimestamps = usedTimestamps)
        rebuildSnapshot()
    }

    private fun finishRecording() {
        recordingInclude = null
        engine.renderer.playLoopDuringCountIn = false
        _state.update { it.copy(record = RecordState()) }
        applyEngineSettings()
        rebuildSnapshot()
    }

    private fun cancelRecording() {
        recordJob?.cancel()
        recordingInclude = null
        engine.renderer.playLoopDuringCountIn = false
        engine.stopRecording()
        engine.renderer.stop()
        _state.update { it.copy(record = RecordState(), playing = false) }
        rebuildSnapshot()
    }

    /** Detect, classify and place the hits of a layer's take, then refresh samples and display. */
    private suspend fun reanalyze(layer: Layer, replaceNotes: Boolean, usedTimestamps: Boolean = true) {
        val audio = takeAudio[layer] ?: return
        val st = project.layer(layer)
        val take = st.take ?: return
        _state.update { it.copy(analyzing = true) }
        try {
            reanalyzeTake(layer, audio, st, take, replaceNotes, usedTimestamps)
        } finally {
            _state.update { it.copy(analyzing = false) }
        }
    }

    private suspend fun reanalyzeTake(
        layer: Layer, audio: FloatArray, st: LayerState, take: TakeInfo, replaceNotes: Boolean, usedTimestamps: Boolean,
    ) {
        val p = project
        val result = withContext(Dispatchers.Default) {
            analyzer.analyze(audio, take, layer, st.sensitivity, st.profile, p.stepsPerBeat, settings.dynamicVelocity)
        }
        analyses[layer] = result
        withContext(Dispatchers.Default) {
            for (v in Voice.of(layer)) {
                val hits = result.allHits.filterIndexed { i, _ -> result.allVoices[i] == v }
                recordedSamples[v] = SampleCutter.rank(hits, sr).take(6).map { SampleCutter.cut(audio, it, sr) }.filter { it.size > sr / 100 }
            }
        }
        rebuildRaw(layer)
        if (replaceNotes) {
            if (project.notes.any { it.voice.layer == layer }) checkpoint()
            edit(rebuild = false) { cur ->
                var id = cur.nextNoteId
                val reps = max(1, kotlin.math.ceil(cur.loopBeats / take.loopBeats).toInt())
                val fresh = (0 until reps).flatMap { r ->
                    result.loopHits.map { h -> Note(id++, h.voice, h.beat + r * take.loopBeats, h.velocity) }
                }.filter { it.beat < cur.loopBeats }
                cur.copy(notes = cur.notes.filter { it.voice.layer != layer } + fresh, nextNoteId = id)
            }
        }
        _state.update {
            it.copy(
                layerViews = it.layerViews + (layer to layerView(audio, take, result, usedTimestamps)),
                recordedCandidates = it.recordedCandidates + Voice.of(layer).associateWith { v -> recordedSamples[v]?.size ?: 0 },
            )
        }
    }

    private suspend fun rebuildRaw(layer: Layer) {
        val audio = takeAudio[layer] ?: return
        val take = project.layer(layer).take ?: return
        val hits = analyses[layer]?.allHits ?: return
        val gate = project.layer(layer).gateRaw
        val steps = project.stepsPerBeat
        rawLoops[layer] = withContext(Dispatchers.Default) { RawLoopBuilder.build(audio, take, hits, gate, steps) }
    }

    private fun layerView(audio: FloatArray, take: TakeInfo, a: TakeAnalysis, usedTimestamps: Boolean): LayerView {
        val fpb = take.sampleRate * 60.0 / take.bpm
        val from = max(0, (take.loopStartFrame - fpb * 0.5).toInt())
        val to = min(audio.size, (take.loopStartFrame + take.loopBeats * fpb + fpb * 0.5).toInt())
        val buckets = 480
        val span = max(1, to - from)
        val peaks = FloatArray(buckets)
        for (b in 0 until buckets) {
            val s = from + (b.toLong() * span / buckets).toInt()
            val e = from + ((b + 1).toLong() * span / buckets).toInt()
            var m = 0f
            for (i in s until min(e, audio.size)) m = max(m, abs(audio[i]))
            peaks[b] = m
        }
        val norm = peaks.maxOrNull()?.takeIf { it > 0f } ?: 1f
        for (i in peaks.indices) peaks[i] /= norm
        val markers = a.allHits.indices
            .filter { a.allHits[it].onset in from until to }
            .map { (a.allHits[it].onset - from).toFloat() / span to a.allVoices[it] }
        val counts = a.loopHits.groupingBy { it.voice }.eachCount()
        return LayerView(
            peaks, markers,
            (take.loopStartFrame - from).toFloat() / span,
            (take.loopStartFrame + take.loopBeats * fpb - from).toFloat() / span,
            counts, usedTimestamps,
        )
    }

    // ------------------------------------------------------------------ hands: record freely, mark the loop

    private var trimHits: List<HitFeatures> = emptyList()

    /** Step 1: record without a metronome, like a voice recorder. */
    fun startCapture() {
        if (_state.value.capture.recording) return
        stop()
        if (!engine.startRecording()) {
            message("The microphone isn't available.")
            return
        }
        _state.update { it.copy(stage = HandsStage.CAPTURE, trim = null, capture = CaptureState(recording = true)) }
        captureJob = viewModelScope.launch {
            var peaks = FloatArray(0)
            while (true) {
                delay(40)
                val fresh = engine.livePeaks(peaks.size)
                if (fresh.isNotEmpty()) peaks += fresh
                val seconds = engine.capturedFrames.toDouble() / sr
                _state.update { it.copy(capture = CaptureState(true, peaks, seconds)) }
                if (seconds >= MAX_CAPTURE_SECONDS) {
                    stopCapture()
                    break
                }
            }
        }
    }

    fun stopCapture() {
        if (!_state.value.capture.recording) return
        captureJob?.cancel()
        val rec = engine.stopRecording()
        _state.update { it.copy(capture = it.capture.copy(recording = false)) }
        val audio = rec?.audio ?: return
        if (audio.size < sr * 3 / 2) {
            message("That was very short. Play your groove a couple of times through.")
            _state.update { it.copy(capture = CaptureState()) }
            return
        }
        viewModelScope.launch { useCapture(audio) }
    }

    /** A finished free recording of the hands layer: keep it and go on to marking the loop. */
    internal suspend fun useCapture(audio: FloatArray) {
        takeAudio[Layer.HANDS] = audio
        analyses.remove(Layer.HANDS)
        rawLoops.remove(Layer.HANDS)
        editLayer(Layer.HANDS, rebuild = false) { it.copy(take = null) }
        withContext(Dispatchers.IO) { store.saveTake(project.id, Layer.HANDS, audio) }
        openTrim(fresh = true)
    }

    /** Go to the lab without recording, to program hits by hand. */
    fun skipToLab() {
        stop()
        _state.update { it.copy(stage = HandsStage.LAB, trim = null) }
    }

    fun reRecordHands() {
        stop()
        _state.update { it.copy(stage = HandsStage.CAPTURE, capture = CaptureState(), trim = null) }
    }

    /** Back to the lab from the record step, keeping the current hands loop. */
    fun backToLab() {
        if (project.layer(Layer.HANDS).take == null) return
        stop()
        _state.update { it.copy(stage = HandsStage.LAB, trim = null, capture = CaptureState()) }
    }

    fun editLoopPoints() {
        stop()
        viewModelScope.launch { openTrim(fresh = false) }
    }

    /** Step 2: show the whole recording with loop points (suggested, or the current ones). */
    private suspend fun openTrim(fresh: Boolean) {
        val audio = takeAudio[Layer.HANDS] ?: return
        val p = project
        val st = p.layer(Layer.HANDS)
        _state.update { it.copy(analyzing = true) }
        val (hits, peaks) = withContext(Dispatchers.Default) {
            analyzer.detect(audio, st.sensitivity) to overview(audio, TrimState.TRIM_BUCKETS)
        }
        trimHits = hits
        val take = st.take
        val points = if (!fresh && take != null) {
            val fpb = take.sampleRate * 60.0 / take.bpm
            val bars = (take.loopBeats / p.beatsPerBar).roundToInt().coerceAtLeast(1)
            LoopPoints(take.loopStartFrame, (take.loopStartFrame + take.loopBeats * fpb).roundToInt().coerceAtMost(audio.size), bars, p.beatsPerBar)
        } else {
            withContext(Dispatchers.Default) { LoopFinder.suggest(hits, audio.size, sr, p.beatsPerBar) }
        }
        _state.update {
            it.copy(
                analyzing = false,
                stage = HandsStage.TRIM,
                capture = CaptureState(),
                trim = TrimState(audio.size, peaks, hits.map { h -> h.onset }.toIntArray(), points.inFrame, points.outFrame, points.bars, points.beatsPerBar),
            )
        }
    }

    private fun overview(audio: FloatArray, buckets: Int): FloatArray {
        val out = FloatArray(buckets)
        for (b in 0 until buckets) {
            val s = (b.toLong() * audio.size / buckets).toInt()
            val e = ((b + 1).toLong() * audio.size / buckets).toInt()
            var m = 0f
            for (i in s until e) m = max(m, abs(audio[i]))
            out[b] = m
        }
        val norm = out.maxOrNull()?.takeIf { it > 0f } ?: 1f
        for (i in out.indices) out[i] /= norm
        return out
    }

    private fun editTrim(updatePreview: Boolean = true, f: (TrimState) -> TrimState) {
        val t = _state.value.trim ?: return
        val n = f(t)
        _state.update { it.copy(trim = n) }
        if (n.previewing && updatePreview) startPreview(n)
    }

    /**
     * Moves the loop start or end to [frame]. While [dragging] it follows the finger exactly;
     * otherwise (tap, end of a drag) it snaps onto a hit within [snapFrames] when snapping is on.
     */
    fun setLoopPoint(isIn: Boolean, frame: Int, snapFrames: Int, dragging: Boolean = false) = editTrim(updatePreview = !dragging) { t ->
        val f = if (t.snap && !dragging) LoopFinder.snap(frame, t.onsets, snapFrames) else frame
        val minLen = sr / 2
        if (isIn) t.copy(inFrame = f.coerceIn(0, t.outFrame - minLen))
        else t.copy(outFrame = f.coerceIn(t.inFrame + minLen, t.totalFrames))
    }

    fun nudgeLoopPoint(isIn: Boolean, ms: Int) = editTrim { t ->
        val d = ms * sr / 1000
        if (isIn) t.copy(inFrame = (t.inFrame + d).coerceIn(0, t.outFrame - sr / 2))
        else t.copy(outFrame = (t.outFrame + d).coerceIn(t.inFrame + sr / 2, t.totalFrames))
    }

    fun setTrimBars(bars: Int) = editTrim { it.copy(bars = bars) }
    fun setTrimBeatsPerBar(n: Int) = editTrim { it.copy(beatsPerBar = n) }
    fun toggleTrimSnap() = editTrim { it.copy(snap = !it.snap) }
    fun toggleTrimClick() = editTrim { it.copy(click = !it.click) }

    /** Let the app find the loop again. */
    fun autoTrim() = editTrim { t ->
        val s = LoopFinder.suggest(trimHits, t.totalFrames, sr, t.beatsPerBar)
        t.copy(inFrame = s.inFrame, outFrame = s.outFrame, bars = s.bars)
    }

    fun togglePreview() {
        val t = _state.value.trim ?: return
        if (t.previewing) stopPreview()
        else {
            val n = t.copy(previewing = true)
            _state.update { it.copy(trim = n) }
            startPreview(n)
        }
    }

    /** Loops the marked part of the recording (with an optional click to check the tempo). */
    private fun startPreview(t: TrimState) {
        val audio = takeAudio[Layer.HANDS] ?: return
        if (!t.bpmOk(sr)) return
        val bpm = t.bpm(sr)
        val take = TakeInfo(sr, audio.size, t.inFrame, bpm, t.beats.toDouble())
        val loop = RawLoopBuilder.build(audio, take, trimHits, gate = false, stepsPerBeat = 4)
        val preview = project.copy(
            bpm = bpm, bars = t.bars, beatsPerBar = t.beatsPerBar, notes = emptyList(),
            layers = mapOf(
                Layer.HANDS to LayerState(LayerMode.RAW, take = take, gateRaw = false),
                Layer.BEATBOX to LayerState(LayerMode.REPLACE),
            ),
        )
        engine.renderer.snapshot = SnapshotBuilder.build(preview, sr, emptyMap(), mapOf(Layer.HANDS to loop), setOf(Layer.HANDS))
        engine.renderer.clickDuringLoop = t.click
        if (!_state.value.playing) {
            engine.renderer.start(0)
            _state.update { it.copy(playing = true) }
        }
    }

    private fun stopPreview() {
        val t = _state.value.trim
        if (t?.previewing == true) _state.update { it.copy(trim = t.copy(previewing = false)) }
        if (_state.value.playing) {
            engine.renderer.stop()
            _state.update { it.copy(playing = false) }
        }
        engine.renderer.clickDuringLoop = settings.clickDuringPlayback
        rebuildSnapshot()
    }

    /** Step 3: the marked loop sets the tempo; detect its hits and open the lab. */
    fun detectHits() {
        val t = _state.value.trim ?: return
        val audio = takeAudio[Layer.HANDS] ?: return
        if (!t.bpmOk(sr)) return
        stopPreview()
        val bpm = t.bpm(sr)
        val take = TakeInfo(sr, audio.size, t.inFrame, bpm, t.beats.toDouble())
        edit(rebuild = false) { p ->
            p.copy(
                bpm = bpm, bars = t.bars, beatsPerBar = t.beatsPerBar,
                notes = p.notes.filter { it.voice.layer != Layer.HANDS && it.beat < t.beats },
            )
        }
        _state.update { it.copy(stage = HandsStage.LAB, trim = null) }
        viewModelScope.launch { applyTake(Layer.HANDS, audio, take) }
    }

    // ------------------------------------------------------------------ teach

    fun startTeach(layer: Layer) {
        stop()
        _state.update { it.copy(teach = TeachState(layer)) }
    }

    fun teachToggleRecord() {
        val t = _state.value.teach ?: return
        if (!t.recording) {
            if (engine.startRecording()) _state.update { it.copy(teach = t.copy(recording = true)) }
            else message("The microphone isn't available.")
            return
        }
        val rec = engine.stopRecording()
        _state.update { it.copy(teach = t.copy(recording = false)) }
        if (rec == null) return
        viewModelScope.launch {
            val hits = withContext(Dispatchers.Default) { analyzer.detect(rec.audio, 0.55f) }
            val cur = _state.value.teach ?: return@launch
            _state.update { it.copy(teach = cur.copy(examples = cur.examples + (cur.voice to hits))) }
            message(if (hits.size < 2) "Only ${hits.size} hit heard. Tap a few more times, a bit louder." else "Got ${hits.size} ${cur.voice.label.lowercase()} hits.")
        }
    }

    fun teachStep(delta: Int) {
        val t = _state.value.teach ?: return
        if (t.recording) teachToggleRecord()
        _state.update { it.copy(teach = t.copy(index = (t.index + delta).coerceIn(0, t.voices.size - 1), recording = false)) }
    }

    fun teachFinish() {
        val t = _state.value.teach ?: return
        if (t.recording) engine.stopRecording()
        val profile = HitClassifier.train(t.examples)
        _state.update { it.copy(teach = null) }
        if (profile == null) { message("Teach at least two sounds with a couple of hits each."); return }
        editLayer(t.layer, rebuild = false) { it.copy(profile = profile) }
        message("Learned ${profile.voices.joinToString { it.label.lowercase() }} for ${t.layer.label.lowercase()}.")
        viewModelScope.launch { reanalyze(t.layer, replaceNotes = true); rebuildSnapshot() }
    }

    fun teachCancel() {
        if (_state.value.teach?.recording == true) engine.stopRecording()
        _state.update { it.copy(teach = null) }
    }

    fun forgetProfile(layer: Layer) {
        editLayer(layer, rebuild = false) { it.copy(profile = null) }
        viewModelScope.launch { reanalyze(layer, replaceNotes = true); rebuildSnapshot() }
    }

    // ------------------------------------------------------------------ latency calibration

    fun openCalibration() {
        stop()
        _state.update { it.copy(calibration = CalibrationState()) }
    }

    fun closeCalibration() {
        if (_state.value.calibration?.phase == CalibrationPhase.RUNNING) cancelRecording()
        _state.update { it.copy(calibration = null) }
        rebuildSnapshot()
    }

    fun runCalibration() {
        recordJob = viewModelScope.launch {
            _state.update { it.copy(calibration = CalibrationState(CalibrationPhase.RUNNING)) }
            val clickProject = project.copy(bpm = 100.0, beatsPerBar = 4, bars = 2, notes = emptyList())
            engine.renderer.stop()
            engine.renderer.snapshot = SnapshotBuilder.build(clickProject, sr, emptyMap(), emptyMap(), emptySet())
            engine.renderer.clickDuringLoop = true
            if (!engine.startRecording()) {
                _state.update { it.copy(calibration = CalibrationState(CalibrationPhase.DONE, error = "The microphone isn't available.")) }
                return@launch
            }
            delay(250)
            engine.renderer.start(4)
            val loopFrames = clickProject.loopFrames(sr)
            while (engine.renderer.transportFrame < loopFrames + sr / 3) delay(25)
            val start = engine.renderer.loopStartRenderedFrame
            engine.renderer.stop()
            val rec = engine.stopRecording()
            applyEngineSettings()
            if (rec == null) { closeCalibration(); return@launch }
            val fpb = clickProject.framesPerBeat(sr)
            val clicks = DoubleArray(8) { rec.inputFrameOf(start + (it * fpb).toLong()) }
            val onsets = withContext(Dispatchers.Default) { OnsetDetector(sr).detect(rec.audio) }
            val ms = LatencyCalibrator.offsetMs(onsets, clicks, sr)
            _state.update {
                it.copy(
                    calibration = if (ms == null) {
                        CalibrationState(CalibrationPhase.DONE, error = "Couldn't hear enough taps. Tap along on every click, firmly.")
                    } else CalibrationState(CalibrationPhase.DONE, resultMs = ms),
                )
            }
            rebuildSnapshot()
        }
    }

    /** [ms] is how late the taps landed (raw, without the current setting), so it becomes the offset. */
    fun applyCalibration(ms: Double) {
        updateSettings { it.copy(latencyMs = ms.toFloat().coerceIn(-400f, 400f)) }
        _state.update { it.copy(calibration = null) }
        message("Latency offset set to ${ms.roundToInt()} ms. It applies to new recordings.")
    }

    // ------------------------------------------------------------------ export

    fun export(kind: ExportKind, seconds: Double = 60.0, wholeLoops: Boolean = true, fadeOut: Boolean = true, midiRepeats: Int = 1) {
        if (_state.value.busy != null) return
        _state.update { it.copy(busy = "Exporting…") }
        viewModelScope.launch {
            try {
                val p = project
                val inputs = ExportInputs(p, sr, sounds(), rawLoops.toMap())
                val file = withContext(Dispatchers.Default) {
                    val dir = File(getApplication<Application>().cacheDir, "exports").apply { mkdirs() }
                    dir.listFiles()?.forEach { it.delete() }
                    val name = ProjectExporter.safeName(p.name)
                    val f = File(dir, if (kind == ExportKind.ABLETON) "$name (Ableton Live 10).zip" else "$name.${kind.ext}")
                    f.outputStream().buffered().use { out ->
                        when (kind) {
                            ExportKind.MIDI -> out.write(ProjectExporter.midi(inputs, midiRepeats))
                            ExportKind.WAV -> ProjectExporter.audio(inputs, seconds, wholeLoops, fadeOut, out)
                            ExportKind.ABLETON -> ProjectExporter.ableton(inputs, out)
                        }
                    }
                    f
                }
                _state.update { it.copy(busy = null, exportResult = ExportResult(file, kind)) }
            } catch (e: Throwable) {
                _state.update { it.copy(busy = null) }
                message("Export failed: ${e.message}")
            }
        }
    }

    fun dismissExport() = _state.update { it.copy(exportResult = null) }

    fun copyExportTo(uri: Uri) {
        val r = _state.value.exportResult ?: return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openOutputStream(uri)!!.use { out -> r.file.inputStream().use { it.copyTo(out) } }
                }
                message("Saved ${r.file.name}")
                _state.update { it.copy(exportResult = null) }
            } catch (e: Exception) {
                message("Couldn't save: ${e.message}")
            }
        }
    }

    /** Hits of a layer's take that fall in the loop, for UI hints. */
    fun analysisFor(l: Layer): TakeAnalysis? = analyses[l]

    fun loopStepOf(n: Note): Int = Timing.stepIndex(n.beat, project.stepsPerBeat, project.totalSteps)

    companion object {
        /** A free recording stops by itself after this long. */
        const val MAX_CAPTURE_SECONDS = 90.0
    }
}
