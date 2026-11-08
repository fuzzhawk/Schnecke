package com.fuzzhawk.schnecke.core.dsp

import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Loop points within a free recording and the bar grid they imply. */
data class LoopPoints(val inFrame: Int, val outFrame: Int, val bars: Int, val beatsPerBar: Int) {
    val beats: Int get() = bars * beatsPerBar
    fun bpm(sampleRate: Int): Double = LoopFinder.bpm(inFrame, outFrame, beats, sampleRate)
}

/**
 * Finds where a freely played groove repeats, so the loop (and with it the tempo) can be set from
 * the recording instead of from a metronome.
 */
object LoopFinder {
    const val MIN_BPM = 40.0
    const val MAX_BPM = 240.0
    private const val MIN_LOOP_S = 0.75
    private const val MAX_LOOP_S = 20.0

    fun bpm(inFrame: Int, outFrame: Int, beats: Int, sampleRate: Int): Double {
        val seconds = (outFrame - inFrame).toDouble() / sampleRate
        return if (seconds <= 0) 0.0 else beats * 60.0 / seconds
    }

    /** The bar count (1, 2, 4 or 8) that puts a loop of [seconds] at the most natural tempo. */
    fun barsFor(seconds: Double, beatsPerBar: Int): Int {
        val options = listOf(1, 2, 4, 8)
        fun bpmOf(b: Int) = b * beatsPerBar * 60.0 / seconds
        val inRange = options.filter { bpmOf(it) in 70.0..150.0 }
        return (inRange.ifEmpty { options }).minBy { abs(bpmOf(it) - 105.0) }
    }

    /** Moves [frame] onto the nearest hit within [maxDistance] frames. */
    fun snap(frame: Int, onsets: IntArray, maxDistance: Int): Int {
        val nearest = onsets.minByOrNull { abs(it - frame) } ?: return frame
        return if (abs(nearest - frame) <= maxDistance) nearest else frame
    }

    /**
     * Suggests loop points: starts at the first clear hit and ends where the groove first repeats
     * (hits recur after the same delay with a similar sound).
     */
    fun suggest(hits: List<HitFeatures>, totalFrames: Int, sampleRate: Int, beatsPerBar: Int): LoopPoints {
        val sorted = hits.sortedBy { it.onset }
        val loudest = sorted.maxOfOrNull { it.peak } ?: 0f
        val first = sorted.firstOrNull { it.peak >= loudest * 0.3f }
        val start = first?.onset ?: 0
        val fallbackEnd = totalFrames
        fun fallback(): LoopPoints {
            val end = max(start + sampleRate, fallbackEnd)
            return LoopPoints(start, end, barsFor((end - start).toDouble() / sampleRate, beatsPerBar), beatsPerBar)
        }
        val usable = sorted.filter { it.onset >= start }
        if (usable.size < 4) return fallback()

        val onsets = usable.map { it.onset }.toIntArray()
        val weights = usable.map { it.peak.toDouble() / loudest }.toDoubleArray()
        val tone = usable.map { log2(it.centroidHz.coerceAtLeast(20.0)) }.toDoubleArray()
        val last = onsets.last()
        val tol = (0.03 * sampleRate).toInt()
        val minLag = (MIN_LOOP_S * sampleRate).toInt()
        val maxLag = min((MAX_LOOP_S * sampleRate).toInt(), last - start + tol)
        if (maxLag <= minLag) return fallback()

        fun score(lag: Int): Double {
            var matched = 0.0
            var total = 0.0
            var considered = 0
            for (i in onsets.indices) {
                val target = onsets[i] + lag
                if (target > last + tol) break
                considered++
                total += weights[i]
                val j = nearestIndex(onsets, target)
                // A hit counts by its own weight: played again louder or softer is still a repeat.
                if (abs(onsets[j] - target) <= tol && abs(tone[j] - tone[i]) < 0.7) matched += weights[i]
            }
            return if (considered < 3 || total <= 0) 0.0 else matched / total
        }

        val step = max(1, sampleRate / 200)
        val lags = (minLag..maxLag step step).toList()
        val scores = lags.map { score(it) }
        val best = scores.maxOrNull() ?: 0.0
        if (best < 0.5) return fallback()
        // The shortest strong repeat is the fundamental. A pattern that varies (a fill in bar 2)
        // only repeats exactly at a multiple of it, so among 1x, 2x, 4x and 8x take the shortest
        // that matches about as well as the best of them.
        val fundamental = lags.indices.first { scores[it] >= best * 0.75 && isLocalMax(scores, it) }
        // Multiply the plateau's centre, not its first lag, or the error doubles with each multiple.
        val fundamentalLag = refine(lags, scores, fundamental)
        val multiples = listOf(1, 2, 4, 8).mapNotNull { m ->
            val target = fundamentalLag * m
            lags.indices.filter { abs(lags[it] - target) <= 2 * tol }.maxByOrNull { scores[it] }
        }
        val bestMultiple = multiples.maxOf { scores[it] }
        var pick = multiples.first { scores[it] >= bestMultiple * 0.97 }
        var lag = refine(lags, scores, pick)
        // A loop shorter than one bar at a sane tempo is usually half a bar: take whole bars.
        while (beatsPerBar * 60.0 * sampleRate / lag > 160.0 && lag * 2 <= maxLag) {
            val doubled = lag * 2
            val near = lags.indices.filter { abs(lags[it] - doubled) <= 2 * tol }
            val idx = near.maxByOrNull { scores[it] } ?: break
            if (scores[idx] < best * 0.75) break
            pick = idx
            lag = refine(lags, scores, pick)
        }
        val end = snap(start + lag, onsets, tol)
        val bars = barsFor((end - start).toDouble() / sampleRate, beatsPerBar)
        return LoopPoints(start, end, bars, beatsPerBar)
    }

    private fun isLocalMax(s: List<Double>, i: Int): Boolean {
        val r = 6
        for (k in max(0, i - r)..min(s.size - 1, i + r)) if (s[k] > s[i]) return false
        return true
    }

    /** Centre of the plateau of equal scores around [i] (tolerance windows make scores flat-topped). */
    private fun refine(lags: List<Int>, scores: List<Double>, i: Int): Int {
        var a = i
        var b = i
        while (a > 0 && scores[a - 1] >= scores[i] - 1e-9) a--
        while (b < scores.size - 1 && scores[b + 1] >= scores[i] - 1e-9) b++
        return ((lags[a] + lags[b]) / 2.0).roundToInt()
    }

    private fun nearestIndex(sorted: IntArray, target: Int): Int {
        var lo = 0
        var hi = sorted.size - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (sorted[mid] < target) lo = mid + 1 else hi = mid
        }
        return if (lo > 0 && abs(sorted[lo - 1] - target) < abs(sorted[lo] - target)) lo - 1 else lo
    }
}
