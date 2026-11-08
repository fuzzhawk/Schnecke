package com.fuzzhawk.schnecke.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.floor
import kotlin.math.roundToLong

/** The two recorded layers of a loop. */
@Serializable
enum class Layer(val label: String) {
    /** Hand taps on a surface: fingertip thumps (kick) and fingernail flicks (snare). */
    HANDS("Hands"),

    /** Beatboxed hi-hats and percussion, overdubbed on top of the hands layer. */
    BEATBOX("Beatbox"),
}

/** A drum voice. Each voice belongs to one layer and has a General MIDI drum note by default. */
@Serializable
enum class Voice(val layer: Layer, val label: String, val defaultNote: Int) {
    KICK(Layer.HANDS, "Kick", 36),
    SNARE(Layer.HANDS, "Snare", 38),
    HIHAT(Layer.BEATBOX, "Hi-hat", 42),
    OPEN_HAT(Layer.BEATBOX, "Open hat", 46),
    PERC(Layer.BEATBOX, "Perc", 37);

    companion object {
        fun of(layer: Layer): List<Voice> = entries.filter { it.layer == layer }
    }
}

/** How a layer sounds on playback and in exports. */
@Serializable
enum class LayerMode {
    /** Detected hits trigger samples (MIDI-style). */
    REPLACE,

    /** The recorded audio itself loops. */
    RAW,
}

/** Where a voice's one-shot sample comes from. */
@Serializable
sealed interface SampleSource {
    /** A built-in, synthesized sample. */
    @Serializable
    @SerialName("library")
    data class Library(val id: String) : SampleSource

    /**
     * A hit cut out of this voice's own recording (e.g. the fingernail flick on the surface).
     * [candidate] picks which detected hit to use, best-scoring first.
     */
    @Serializable
    @SerialName("recorded")
    data class Recorded(val candidate: Int = 0) : SampleSource

    /** A WAV file the user imported; the decoded audio is stored with the project. */
    @Serializable
    @SerialName("custom")
    data class Custom(val name: String) : SampleSource
}

@Serializable
data class VoiceSettings(
    val source: SampleSource,
    val gainDb: Float = 0f,
    val pan: Float = 0f,
    val muted: Boolean = false,
    val midiNote: Int,
)

/**
 * One drum hit in the loop. [beat] is where it lands, in beats from the loop start, in
 * `[0, loopBeats)`. Its grid step is the nearest step of the grid; its *offset* is how far it
 * sits from that step (early is negative). Detected hits keep the timing you played; snapping
 * a hit sets its offset to zero.
 */
@Serializable
data class Note(
    val id: Long,
    val voice: Voice,
    val beat: Double,
    val velocity: Int,
)

/** A trained "what does each sound look like on this surface/voice" profile, from teach recordings. */
@Serializable
data class ClassProfile(
    val voices: List<Voice>,
    val centroids: List<List<Double>>,
    val mean: List<Double>,
    val std: List<Double>,
)

/** Metadata of a recorded take. The audio itself is stored next to the project file. */
@Serializable
data class TakeInfo(
    val sampleRate: Int,
    val lengthFrames: Int,
    /** Frame in the take audio where beat 0 of the loop is (latency-compensated). */
    val loopStartFrame: Int,
    val bpm: Double,
    val loopBeats: Double,
)

@Serializable
data class LayerState(
    val mode: LayerMode,
    val sensitivity: Float = 0.5f,
    val take: TakeInfo? = null,
    /** In raw mode, silence the background noise between hits. */
    val gateRaw: Boolean = true,
    val gainDb: Float = 0f,
    val muted: Boolean = false,
    val profile: ClassProfile? = null,
)

@Serializable
data class Project(
    val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val bpm: Double = 96.0,
    val beatsPerBar: Int = 4,
    val bars: Int = 2,
    val stepsPerBeat: Int = 4,
    /**
     * Legacy global quantize strength (versions before per-hit offsets snapped every hit by this
     * much at playback). Now always 0: [migrated] bakes an old value into the hits.
     */
    val quantize: Float = 0f,
    /** MPC-style swing in percent: 50 = straight, 66 = triplet feel, 75 = dotted. */
    val swingPercent: Float = 50f,
    val notes: List<Note> = emptyList(),
    val voices: Map<Voice, VoiceSettings> = defaultVoices(),
    val layers: Map<Layer, LayerState> = defaultLayers(),
    val nextNoteId: Long = 1,
) {
    val loopBeats: Double get() = (bars * beatsPerBar).toDouble()
    val totalSteps: Int get() = bars * beatsPerBar * stepsPerBeat
    val secondsPerBeat: Double get() = 60.0 / bpm
    val loopSeconds: Double get() = loopBeats * secondsPerBeat

    fun framesPerBeat(sampleRate: Int): Double = sampleRate * secondsPerBeat
    fun loopFrames(sampleRate: Int): Long = (loopBeats * framesPerBeat(sampleRate)).roundToLong()

    fun voice(v: Voice): VoiceSettings = voices[v] ?: defaultVoices().getValue(v)
    fun layer(l: Layer): LayerState = layers[l] ?: defaultLayers().getValue(l)

    /** Position actually played for [note]: its (swung) grid step plus its offset. */
    fun playedBeat(note: Note): Double =
        Timing.playedBeat(note.beat, stepsPerBeat, swingPercent.toDouble(), loopBeats)

    /** Grid step a note belongs to, used by the step editor. */
    fun stepOf(note: Note): Int = Timing.stepIndex(note.beat, stepsPerBeat, totalSteps)

    /** How far [note] sits from its grid step, in beats (negative = early). */
    fun offsetOf(note: Note): Double = Timing.offset(note.beat, stepsPerBeat)

    /** Length of one grid step in beats. */
    val stepBeats: Double get() = 1.0 / stepsPerBeat

    /** [note] moved [strength] of the way onto its grid step (1 = exactly on the grid). */
    fun snapped(note: Note, strength: Double = 1.0): Note =
        note.copy(beat = Timing.snap(note.beat, stepsPerBeat, strength, loopBeats))

    /** [note] placed [offsetBeats] from its grid step (kept within half a step, so it stays in its cell). */
    fun withOffset(note: Note, offsetBeats: Double): Note =
        note.copy(beat = Timing.withOffset(note.beat, offsetBeats, stepsPerBeat, loopBeats))

    /** Brings a project saved by an older version up to date (keeps how it sounded). */
    fun migrated(): Project {
        if (quantize <= 0f) return this
        val q = quantize.toDouble().coerceAtMost(1.0)
        return copy(notes = notes.map { snapped(it, q) }, quantize = 0f)
    }

    companion object {
        fun defaultVoices(): Map<Voice, VoiceSettings> = mapOf(
            Voice.KICK to VoiceSettings(SampleSource.Library("kick_punch"), midiNote = Voice.KICK.defaultNote),
            Voice.SNARE to VoiceSettings(SampleSource.Recorded(), midiNote = Voice.SNARE.defaultNote),
            Voice.HIHAT to VoiceSettings(SampleSource.Library("hat_closed"), gainDb = -3f, pan = 0.15f, midiNote = Voice.HIHAT.defaultNote),
            Voice.OPEN_HAT to VoiceSettings(SampleSource.Library("hat_open"), gainDb = -4f, pan = 0.15f, midiNote = Voice.OPEN_HAT.defaultNote),
            Voice.PERC to VoiceSettings(SampleSource.Library("rim"), gainDb = -2f, pan = -0.15f, midiNote = Voice.PERC.defaultNote),
        )

        fun defaultLayers(): Map<Layer, LayerState> = mapOf(
            Layer.HANDS to LayerState(mode = LayerMode.REPLACE),
            Layer.BEATBOX to LayerState(mode = LayerMode.RAW),
        )
    }
}

/** Grid math shared by playback, editing and every exporter so they always agree. */
object Timing {
    fun stepIndex(beat: Double, stepsPerBeat: Int, totalSteps: Int): Int {
        val idx = Math.round(beat * stepsPerBeat).toInt()
        return ((idx % totalSteps) + totalSteps) % totalSteps
    }

    /** Hits can sit up to just under half a step off the grid (further would be the next step). */
    const val MAX_OFFSET_STEPS = 0.49

    private fun anchor(beat: Double, stepsPerBeat: Int): Long = Math.round(beat * stepsPerBeat)

    fun offset(beat: Double, stepsPerBeat: Int): Double = beat - anchor(beat, stepsPerBeat).toDouble() / stepsPerBeat

    /** The grid position of step [idx], moved by swing. */
    fun swungGrid(idx: Long, stepsPerBeat: Int, swingPercent: Double): Double {
        val step = 1.0 / stepsPerBeat
        var grid = idx * step
        if (stepsPerBeat % 2 == 0 && swingPercent > 50.0 && Math.floorMod(idx, 2L) == 1L) {
            // MPC swing: the second step of each pair moves from 50% to swingPercent% of the pair.
            grid += (swingPercent - 50.0) / 50.0 * step
        }
        return grid
    }

    fun playedBeat(beat: Double, stepsPerBeat: Int, swingPercent: Double, loopBeats: Double): Double {
        val idx = anchor(beat, stepsPerBeat)
        return wrap(swungGrid(idx, stepsPerBeat, swingPercent) + offset(beat, stepsPerBeat), loopBeats)
    }

    fun snap(beat: Double, stepsPerBeat: Int, strength: Double, loopBeats: Double): Double =
        wrap(beat - offset(beat, stepsPerBeat) * strength.coerceIn(0.0, 1.0), loopBeats)

    fun withOffset(beat: Double, offsetBeats: Double, stepsPerBeat: Int, loopBeats: Double): Double {
        val limit = MAX_OFFSET_STEPS / stepsPerBeat
        return wrap(anchor(beat, stepsPerBeat).toDouble() / stepsPerBeat + offsetBeats.coerceIn(-limit, limit), loopBeats)
    }

    fun wrap(beat: Double, loopBeats: Double): Double {
        val m = beat - floor(beat / loopBeats) * loopBeats
        return if (m >= loopBeats) 0.0 else m
    }
}
