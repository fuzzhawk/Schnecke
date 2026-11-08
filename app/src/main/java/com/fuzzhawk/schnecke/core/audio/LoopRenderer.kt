package com.fuzzhawk.schnecke.core.audio

import com.fuzzhawk.schnecke.core.model.Layer
import com.fuzzhawk.schnecke.core.model.LayerMode
import com.fuzzhawk.schnecke.core.model.Project
import com.fuzzhawk.schnecke.core.model.Voice
import com.fuzzhawk.schnecke.core.synth.DrumSynth
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sin

class TriggerEvent(val frame: Long, val voice: Int, val gain: Float)

/** A raw take looping under the pattern, varispeeded if the tempo changed since recording. */
class RawPlayback(
    val audio: FloatArray,
    val takeFramesPerBeat: Double,
    val takeLoopBeats: Double,
    val gainL: Float,
    val gainR: Float,
)

/** Everything the renderer needs to play the loop. Immutable; swapped whole when the user edits. */
class PlaybackSnapshot(
    val sampleRate: Int,
    val framesPerBeat: Double,
    val beatsPerBar: Int,
    val loopFrames: Long,
    val events: Array<TriggerEvent>,
    val samples: Array<FloatArray?>,
    val panL: FloatArray,
    val panR: FloatArray,
    val raws: Array<RawPlayback>,
)

object SnapshotBuilder {
    fun dbToGain(db: Float): Float = if (db <= -60f) 0f else 10f.pow(db / 20f)

    /** Velocity curve: soft hits stay audible, full velocity is unity gain. */
    fun velocityGain(velocity: Int): Float = (velocity.coerceIn(1, 127) / 127f).pow(1.6f)

    fun panGains(pan: Float): Pair<Float, Float> {
        val theta = (pan.coerceIn(-1f, 1f) + 1) * PI / 4
        return (cos(theta) * Math.sqrt(2.0)).toFloat().coerceAtMost(1f) to (sin(theta) * Math.sqrt(2.0)).toFloat().coerceAtMost(1f)
    }

    /**
     * @param sounds the one-shot sample of each voice (null = silent)
     * @param raws the seamless raw loop of each layer that has a take
     * @param include which layers should sound (e.g. leave out the layer being re-recorded)
     * @param onlyVoice render a single voice (for stems)
     * @param onlyRaw render a single raw layer (for stems)
     */
    fun build(
        project: Project,
        sampleRate: Int,
        sounds: Map<Voice, FloatArray?>,
        raws: Map<Layer, FloatArray?>,
        include: Set<Layer> = Layer.entries.toSet(),
        onlyVoice: Voice? = null,
        onlyRaw: Layer? = null,
    ): PlaybackSnapshot {
        val fpb = project.framesPerBeat(sampleRate)
        val loopFrames = project.loopFrames(sampleRate)
        val events = ArrayList<TriggerEvent>()
        if (onlyRaw == null) {
            for (note in project.notes) {
                val v = note.voice
                if (onlyVoice != null && v != onlyVoice) continue
                val layer = project.layer(v.layer)
                val vs = project.voice(v)
                if (v.layer !in include || layer.muted || vs.muted || layer.mode == LayerMode.RAW) continue
                if (sounds[v] == null) continue
                val frame = (project.playedBeat(note) * fpb).roundToLong().coerceIn(0, loopFrames - 1)
                val gain = velocityGain(note.velocity) * dbToGain(vs.gainDb) * dbToGain(layer.gainDb)
                events.add(TriggerEvent(frame, v.ordinal, gain))
            }
        }
        events.sortBy { it.frame }
        val panL = FloatArray(Voice.entries.size)
        val panR = FloatArray(Voice.entries.size)
        for (v in Voice.entries) {
            val (l, r) = panGains(project.voice(v).pan)
            panL[v.ordinal] = l; panR[v.ordinal] = r
        }
        val rawList = ArrayList<RawPlayback>()
        if (onlyVoice == null) {
            for (layer in Layer.entries) {
                if (onlyRaw != null && layer != onlyRaw) continue
                val st = project.layer(layer)
                val take = st.take ?: continue
                val audio = raws[layer] ?: continue
                if (layer !in include || st.muted || st.mode != LayerMode.RAW) continue
                val g = dbToGain(st.gainDb)
                rawList.add(RawPlayback(audio, take.sampleRate * 60.0 / take.bpm, take.loopBeats, g, g))
            }
        }
        return PlaybackSnapshot(
            sampleRate = sampleRate,
            framesPerBeat = fpb,
            beatsPerBar = project.beatsPerBar,
            loopFrames = loopFrames,
            events = events.toTypedArray(),
            samples = Array(Voice.entries.size) { sounds[Voice.entries[it]] },
            panL = panL,
            panR = panR,
            raws = rawList.toTypedArray(),
        )
    }
}

/**
 * Sample-accurate loop player: trigger events, raw loops, metronome clicks and one-shot previews,
 * through a peak limiter. Control calls may come from any thread; [render] runs on one thread.
 */
class LoopRenderer(val sampleRate: Int) {
    @Volatile
    var snapshot: PlaybackSnapshot? = null

    @Volatile
    var clickDuringLoop: Boolean = false

    @Volatile
    var clickGain: Float = 0.6f

    @Volatile
    var masterGain: Float = 1f

    private sealed interface Command
    private class Start(val countInBeats: Int) : Command
    private object Stop : Command
    private class Preview(val sample: FloatArray, val gain: Float) : Command

    private val commands = ConcurrentLinkedQueue<Command>()

    private class ActiveVoice(
        val data: FloatArray,
        val voice: Int,
        var pos: Int,
        val gainL: Float,
        val gainR: Float,
        var fade: Float = 1f,
        var fadeStep: Float = 0f,
    )

    private val active = ArrayList<ActiveVoice>(64)
    private val clickHi = DrumSynth.click(sampleRate, accent = true)
    private val clickLo = DrumSynth.click(sampleRate, accent = false)

    private var playing = false
    private var pos = 0L
    private var lastBeat = Long.MIN_VALUE
    private var nextEvent = 0
    private var lastLoopPos = -1L
    private var lastSnapshot: PlaybackSnapshot? = null
    private var limiterGain = 1f
    private val limiterRelease = (1.0 - 1.0 / (0.08 * sampleRate)).toFloat()

    /** Frames rendered since creation; equals the output stream's frame position. */
    @Volatile
    var renderedFrames: Long = 0L
        private set

    /** [renderedFrames] value at which the loop's first beat (after the count-in) plays. */
    @Volatile
    var loopStartRenderedFrame: Long = -1L
        private set

    /** Transport position in frames; negative during the count-in. */
    @Volatile
    var transportFrame: Long = 0L
        private set

    @Volatile
    var isPlaying: Boolean = false
        private set

    fun start(countInBeats: Int) = commands.add(Start(countInBeats))
    fun stop() = commands.add(Stop)
    fun preview(sample: FloatArray, gain: Float = 1f) = commands.add(Preview(sample, gain))

    private fun handleCommands() {
        while (true) {
            when (val c = commands.poll() ?: return) {
                is Start -> {
                    val snap = snapshot
                    val fpb = snap?.framesPerBeat ?: (sampleRate * 0.5)
                    val countIn = (c.countInBeats * fpb).roundToLong()
                    pos = -countIn
                    playing = true
                    lastBeat = Long.MIN_VALUE
                    nextEvent = 0
                    lastLoopPos = -1L
                    loopStartRenderedFrame = renderedFrames + countIn
                }
                Stop -> {
                    playing = false
                    for (v in active) if (v.voice >= 0) { v.fadeStep = 1f / (0.01f * sampleRate) }
                }
                is Preview -> active.add(ActiveVoice(c.sample, -1, 0, c.gain, c.gain))
            }
        }
    }

    /** Renders [frames] stereo frames, interleaved, into [out]. */
    fun render(out: FloatArray, frames: Int) {
        handleCommands()
        java.util.Arrays.fill(out, 0, frames * 2, 0f)
        val snap = snapshot
        if (snap !== lastSnapshot) {
            lastSnapshot = snap
            if (snap != null && pos >= 0) {
                val lp = pos % snap.loopFrames
                nextEvent = firstEventAtOrAfter(snap, lp)
                lastLoopPos = lp - 1
            }
        }
        for (i in 0 until frames) {
            if (playing && snap != null) stepTransport(snap, out, i)
            mixVoices(out, i)
        }
        limit(out, frames)
        renderedFrames += frames
        transportFrame = pos
        isPlaying = playing
    }

    private fun stepTransport(snap: PlaybackSnapshot, out: FloatArray, i: Int) {
        val beat = floor(pos / snap.framesPerBeat).toLong()
        if (beat != lastBeat) {
            lastBeat = beat
            if (pos < 0 || clickDuringLoop) {
                val accent = Math.floorMod(beat, snap.beatsPerBar.toLong()) == 0L
                active.add(ActiveVoice(if (accent) clickHi else clickLo, -2, 0, clickGain, clickGain))
            }
        }
        if (pos >= 0) {
            val loopPos = pos % snap.loopFrames
            if (loopPos < lastLoopPos) nextEvent = 0
            lastLoopPos = loopPos
            val events = snap.events
            while (nextEvent < events.size && events[nextEvent].frame <= loopPos) {
                val e = events[nextEvent++]
                if (e.frame == loopPos) trigger(snap, e)
            }
            for (r in snap.raws) {
                val beatPos = loopPos / snap.framesPerBeat
                val rawBeat = beatPos - floor(beatPos / r.takeLoopBeats) * r.takeLoopBeats
                val x = rawBeat * r.takeFramesPerBeat
                val i0 = x.toInt()
                if (i0 >= 0 && i0 < r.audio.size) {
                    val frac = (x - i0).toFloat()
                    val a = r.audio[i0]
                    val b = if (i0 + 1 < r.audio.size) r.audio[i0 + 1] else r.audio[0]
                    val s = a + (b - a) * frac
                    out[2 * i] += s * r.gainL
                    out[2 * i + 1] += s * r.gainR
                }
            }
        }
        pos++
    }

    private fun trigger(snap: PlaybackSnapshot, e: TriggerEvent) {
        val data = snap.samples[e.voice] ?: return
        if (e.voice == Voice.HIHAT.ordinal) {
            // A closed hat chokes a ringing open hat, as on a real kit.
            for (v in active) if (v.voice == Voice.OPEN_HAT.ordinal) v.fadeStep = 1f / (0.008f * sampleRate)
        }
        var count = 0
        var oldest: ActiveVoice? = null
        for (v in active) if (v.voice == e.voice) { count++; if (oldest == null) oldest = v }
        if (count >= 4) oldest?.fadeStep = 1f / (0.005f * sampleRate)
        active.add(ActiveVoice(data, e.voice, 0, e.gain * snap.panL[e.voice], e.gain * snap.panR[e.voice]))
    }

    private fun mixVoices(out: FloatArray, i: Int) {
        var l = 0f
        var r = 0f
        var k = 0
        while (k < active.size) {
            val v = active[k]
            if (v.pos >= v.data.size || v.fade <= 0f) {
                active.removeAt(k)
                continue
            }
            val s = v.data[v.pos++] * v.fade
            l += s * v.gainL
            r += s * v.gainR
            if (v.fadeStep > 0f) v.fade -= v.fadeStep
            k++
        }
        out[2 * i] += l
        out[2 * i + 1] += r
    }

    private fun limit(out: FloatArray, frames: Int) {
        val ceiling = 0.97f
        val mg = masterGain
        for (i in 0 until frames) {
            val l = out[2 * i] * mg
            val r = out[2 * i + 1] * mg
            val peak = max(abs(l), abs(r))
            if (peak * limiterGain > ceiling) limiterGain = ceiling / peak
            out[2 * i] = (l * limiterGain).coerceIn(-1f, 1f)
            out[2 * i + 1] = (r * limiterGain).coerceIn(-1f, 1f)
            limiterGain = min(1f, 1f - (1f - limiterGain) * limiterRelease)
        }
    }

    private fun firstEventAtOrAfter(snap: PlaybackSnapshot, frame: Long): Int {
        var lo = 0
        var hi = snap.events.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (snap.events[mid].frame < frame) lo = mid + 1 else hi = mid
        }
        return lo
    }
}

/** Renders loops to buffers for export. */
object OfflineRenderer {
    /** [totalFrames] of interleaved stereo, looping from the first beat, optionally fading out. */
    fun render(snapshot: PlaybackSnapshot, totalFrames: Long, fadeOutFrames: Long = 0): FloatArray {
        require(totalFrames * 2 < Int.MAX_VALUE) { "export too long" }
        val r = LoopRenderer(snapshot.sampleRate)
        r.snapshot = snapshot
        r.clickDuringLoop = false
        r.start(0)
        val out = FloatArray((totalFrames * 2).toInt())
        val block = 1024
        val buf = FloatArray(block * 2)
        var done = 0L
        while (done < totalFrames) {
            val n = min(block.toLong(), totalFrames - done).toInt()
            r.render(buf, n)
            System.arraycopy(buf, 0, out, (done * 2).toInt(), n * 2)
            done += n
        }
        if (fadeOutFrames > 0) {
            val f = min(fadeOutFrames, totalFrames)
            for (i in 0 until f) {
                val idx = (totalFrames - f + i).toInt()
                val g = 1f - (i + 1).toFloat() / f
                out[2 * idx] *= g
                out[2 * idx + 1] *= g
            }
        }
        return out
    }

    /**
     * Exactly one loop that repeats seamlessly: tails of hits near the loop end are wrapped onto
     * the start (rendered as the second of two passes).
     */
    fun renderSeamlessLoop(snapshot: PlaybackSnapshot): FloatArray {
        val lf = snapshot.loopFrames
        val two = render(snapshot, lf * 2)
        return two.copyOfRange((lf * 2).toInt(), (lf * 4).toInt())
    }
}
