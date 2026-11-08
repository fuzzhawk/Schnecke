package com.fuzzhawk.schnecke.core.dsp

import com.fuzzhawk.schnecke.core.model.ClassProfile
import com.fuzzhawk.schnecke.core.model.Layer
import com.fuzzhawk.schnecke.core.model.TakeInfo
import com.fuzzhawk.schnecke.core.model.Timing
import com.fuzzhawk.schnecke.core.model.Voice
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** One detected hit of a take, placed in the loop. */
data class AnalyzedHit(
    val features: HitFeatures,
    val voice: Voice,
    /** Unquantized loop position in beats, in `[0, loopBeats)`. */
    val beat: Double,
    val velocity: Int,
)

data class TakeAnalysis(
    /** Every onset found in the recording (including count-in and tail), for display. */
    val allHits: List<HitFeatures>,
    val allVoices: List<Voice>,
    /** The hits that fall inside the loop, merged so each voice has at most one hit per step. */
    val loopHits: List<AnalyzedHit>,
)

class TakeAnalyzer(private val sampleRate: Int) {
    private val detector = OnsetDetector(sampleRate)
    private val extractor = FeatureExtractor(sampleRate)

    fun detect(audio: FloatArray, sensitivity: Float): List<HitFeatures> {
        val onsets = detector.detect(audio, OnsetDetector.Params(sensitivity = sensitivity))
        return extractor.extract(audio, onsets)
    }

    fun analyze(
        audio: FloatArray,
        take: TakeInfo,
        layer: Layer,
        sensitivity: Float,
        profile: ClassProfile?,
        stepsPerBeat: Int,
        dynamicVelocity: Boolean,
    ): TakeAnalysis {
        val hits = detect(audio, sensitivity)
        val voices = HitClassifier.classify(layer, hits, profile)
        val fpb = sampleRate * 60.0 / take.bpm
        val halfStep = 0.5 / stepsPerBeat
        val loopBeats = take.loopBeats

        data class Placed(val f: HitFeatures, val v: Voice, val beat: Double)
        val placed = hits.indices.mapNotNull { i ->
            val beat = (hits[i].onset - take.loopStartFrame) / fpb
            if (beat < -halfStep || beat >= loopBeats + halfStep) null
            else Placed(hits[i], voices[i], Timing.wrap(beat, loopBeats))
        }

        val maxDb = placed.groupBy { it.v }.mapValues { (_, l) -> l.maxOf { it.f.peakDb } }
        fun velocity(p: Placed): Int {
            if (!dynamicVelocity) return 100
            val rel = p.f.peakDb - maxDb.getValue(p.v)
            return (127 + rel * (87.0 / 24.0)).roundToInt().coerceIn(15, 127)
        }

        val totalSteps = (loopBeats * stepsPerBeat).roundToInt().coerceAtLeast(1)
        val merged = placed
            .groupBy { it.v to Timing.stepIndex(it.beat, stepsPerBeat, totalSteps) }
            .values
            .map { group -> group.maxBy { it.f.peak } }
            .map { AnalyzedHit(it.f, it.v, it.beat, velocity(it)) }
            .sortedBy { it.beat }
        return TakeAnalysis(hits, voices, merged)
    }
}

/** Cuts one-shot samples out of a recording. */
object SampleCutter {
    /** Hits of a voice ordered best-first: loud, unclipped, with room for the tail to ring. */
    fun rank(hits: List<HitFeatures>, sampleRate: Int): List<HitFeatures> = hits.sortedByDescending { h ->
        val gapMs = (h.limit - h.onset) * 1000.0 / sampleRate
        val clipped = if (h.peak > 0.98f) 12.0 else 0.0
        h.peakDb + min(gapMs, 400.0) / 40.0 - clipped
    }

    fun cut(audio: FloatArray, hit: HitFeatures, sampleRate: Int): FloatArray {
        val start = max(0, hit.onset - (0.001 * sampleRate).toInt())
        val maxEnd = min(min(audio.size, hit.limit - (0.001 * sampleRate).toInt()), hit.onset + (0.6 * sampleRate).toInt())
        if (maxEnd - start < 32) return FloatArray(0)
        val env = envelope(audio.copyOfRange(start, maxEnd), max(1, sampleRate / 500))
        val peak = env.maxOrNull() ?: 0f
        var last = env.size - 1
        while (last > 0 && env[last] < peak * 0.01f) last--
        val end = min(maxEnd, start + last + (0.015 * sampleRate).toInt())
        val out = highPassCopy(audio.copyOfRange(start, end), sampleRate, 30.0)
        val fadeIn = min(out.size / 4, (0.0005 * sampleRate).toInt().coerceAtLeast(1))
        for (i in 0 until fadeIn) out[i] *= i / fadeIn.toFloat()
        val fadeOut = min(out.size / 3, (0.012 * sampleRate).toInt()).coerceAtLeast(1)
        for (i in 0 until fadeOut) out[out.size - 1 - i] *= i / fadeOut.toFloat()
        val p = Stats.peak(out)
        if (p > 1e-6f) for (i in out.indices) out[i] *= 0.9f / p
        return out
    }
}

/** Builds a seamlessly looping copy of a raw take. */
object RawLoopBuilder {
    fun build(audio: FloatArray, take: TakeInfo, hits: List<HitFeatures>, gate: Boolean, stepsPerBeat: Int): FloatArray {
        val sr = take.sampleRate
        val src = if (gate) gated(audio, hits, sr) else audio
        val fpb = sr * 60.0 / take.bpm
        val len = (take.loopBeats * fpb).roundToInt()
        val start = take.loopStartFrame
        val out = FloatArray(len)
        for (i in 0 until len) {
            val s = start + i
            if (s in src.indices) out[i] = src[s]
        }
        // Sound still ringing at the loop end continues at the loop start, up to the next hit.
        val loopEnd = start + len
        val nextHit = hits.firstOrNull { it.onset >= loopEnd - (0.003 * sr).toInt() }?.onset ?: Int.MAX_VALUE
        val tail = min(min((0.4 * sr).toInt(), src.size - loopEnd), nextHit - loopEnd).coerceAtLeast(0)
        for (i in 0 until min(tail, len)) out[i] += src[loopEnd + i] * (1f - i / tail.toFloat())
        // A downbeat played slightly early starts before the loop: put its attack at the loop end.
        val halfStep = (0.5 / stepsPerBeat * fpb).toInt()
        val early = hits.lastOrNull { it.onset in (start - halfStep) until start }
        if (early != null) {
            val from = max(0, early.onset - (0.002 * sr).toInt())
            val n = min(start - from, len)
            for (i in 0 until n) out[len - n + i] += src[from + i]
        }
        val fade = min(len / 4, (0.002 * sr).toInt())
        for (i in 0 until fade) out[len - 1 - i] *= 0.5f + 0.5f * i / fade.toFloat()
        return out
    }

    /** Keep each hit from its onset until it decays; silence the background in between. */
    fun gated(audio: FloatArray, hits: List<HitFeatures>, sr: Int): FloatArray {
        if (hits.isEmpty()) return audio.copyOf()
        val gain = FloatArray(audio.size)
        val env = envelope(audio, max(1, sr / 500))
        val noise = Stats.percentile(DoubleArray(env.size / 64) { env[it * 64].toDouble() }, 0.2).toFloat()
        val attack = (0.003 * sr).toInt()
        val release = (0.02 * sr).toInt()
        for (h in hits) {
            val from = max(0, h.onset - attack)
            val limit = min(audio.size, h.limit)
            val pk = env[h.peakIdx.coerceIn(0, env.size - 1)]
            val thr = max(noise * 2.5f, pk * 0.015f)
            var end = h.peakIdx.coerceIn(from, max(from, limit - 1))
            while (end < limit - 1 && env[end] > thr) end++
            for (i in from until min(end, audio.size)) {
                val a = if (i < h.onset) (i - from) / attack.toFloat() else 1f
                gain[i] = max(gain[i], a)
            }
            for (i in 0 until release) {
                val idx = end + i
                if (idx >= limit) break
                gain[idx] = max(gain[idx], 1f - i / release.toFloat())
            }
        }
        return FloatArray(audio.size) { audio[it] * gain[it] }
    }
}

/** Measures how late the player's taps land relative to the clicks they hear. */
object LatencyCalibrator {
    /**
     * [onsets] and [clickFrames] are positions in the same (input) timeline. Returns the median
     * offset in milliseconds (positive = taps arrive late), or null with too little data.
     */
    fun offsetMs(onsets: IntArray, clickFrames: DoubleArray, sampleRate: Int): Double? {
        if (clickFrames.isEmpty()) return null
        val maxDev = 0.25 * sampleRate
        val diffs = onsets.asList().mapNotNull { o ->
            val nearest = clickFrames.minBy { kotlin.math.abs(it - o) }
            val d = o - nearest
            if (kotlin.math.abs(d) <= maxDev) d * 1000.0 / sampleRate else null
        }
        if (diffs.size < 4) return null
        return Stats.median(diffs)
    }
}
