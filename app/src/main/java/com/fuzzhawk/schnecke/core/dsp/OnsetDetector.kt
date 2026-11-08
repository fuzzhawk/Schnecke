package com.fuzzhawk.schnecke.core.dsp

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Finds percussive onsets (taps, flicks, beatboxed consonants) in a mono recording.
 *
 * Spectral flux on log-compressed magnitudes (robust to the very different loudness of a fingertip
 * thump and a nail flick), an adaptive median threshold, peak picking with a minimum gap, then
 * sample-accurate refinement on the time-domain envelope.
 */
class OnsetDetector(private val sampleRate: Int) {

    data class Params(
        /** 0 = only the clearest hits, 1 = catch very quiet ghost notes. */
        val sensitivity: Float = 0.5f,
        val minGapMs: Double = 45.0,
    )

    private val n = 1024
    private val hop = 128
    private val lag = 2
    private val fft = Fft(n)
    private val window = Fft.hann(n)

    /** Returns onset positions (sample indices into [audio]), ascending. */
    fun detect(audio: FloatArray, params: Params = Params()): IntArray {
        if (audio.size < n) return IntArray(0)
        val x = normalized(highPassCopy(audio, sampleRate, 30.0))

        val flux = spectralFlux(x)
        val frames = flux.size
        val smooth = DoubleArray(frames) { i ->
            val a = flux[max(0, i - 1)]; val b = flux[i]; val c = flux[min(frames - 1, i + 1)]
            0.25 * a + 0.5 * b + 0.25 * c
        }

        // Frame loudness, to reject flux bumps that are only background noise.
        val rmsDb = DoubleArray(frames) { t ->
            var acc = 0.0
            val start = t * hop
            for (i in start until min(x.size, start + n)) acc += x[i] * x[i].toDouble()
            Stats.db(sqrt(acc / n))
        }
        val noiseDb = Stats.percentile(rmsDb, 0.15)

        val s = params.sensitivity.coerceIn(0f, 1f).toDouble()
        val floor = Stats.median(smooth)
        val top = Stats.percentile(smooth, 0.995).coerceAtLeast(floor + 1e-6)
        val delta = (top - floor) * (0.30 * (1 - s).pow(1.6) + 0.025)
        val gateDb = noiseDb + 14.0 - 8.0 * s

        val w = (0.06 * sampleRate / hop).toInt()
        val localMedian = runningMedian(smooth, w)
        val minGapFrames = (params.minGapMs / 1000 * sampleRate / hop).toInt()
        val pickRadius = 3

        val coarse = ArrayList<Int>()
        var last = -minGapFrames
        for (t in 1 until frames - 1) {
            val v = smooth[t]
            if (v <= localMedian[t] + delta) continue
            var isPeak = true
            for (j in 1..pickRadius) {
                if (t - j >= 0 && smooth[t - j] > v) { isPeak = false; break }
                if (t + j < frames && smooth[t + j] >= v) { isPeak = false; break }
            }
            if (!isPeak) continue
            // Loudness gate: the frames just after the onset must stand out of the noise.
            val loud = max(rmsDb[t], rmsDb[min(frames - 1, t + 2)])
            if (loud < gateDb) continue
            if (t - last < minGapFrames) {
                // Keep the stronger of two close candidates.
                if (coarse.isNotEmpty() && v > smooth[last]) {
                    coarse[coarse.size - 1] = t
                    last = t
                }
                continue
            }
            coarse.add(t)
            last = t
        }

        val env = envelope(x, max(1, sampleRate / 2000)) // 0.5 ms
        val refined = ArrayList<Int>(coarse.size)
        val peaks = ArrayList<Float>(coarse.size)
        val maskWindow = (0.1 * sampleRate).toInt()
        val peakWindow = (0.03 * sampleRate).toInt()
        for (t in coarse) {
            val centre = t * hop + n / 2
            val r = refine(env, centre)
            val pk = Stats.peak(x, r, min(x.size, r + peakWindow))
            if (refined.isNotEmpty()) {
                if (r - refined.last() <= sampleRate * params.minGapMs / 2000.0) continue
                // Rattle or ring-out of the previous hit (24 dB quieter, within 100 ms): not a new hit.
                if (r - refined.last() < maskWindow && pk < peaks.last() * 0.063f) continue
            }
            refined.add(r)
            peaks.add(pk)
        }
        return refined.toIntArray()
    }

    private fun spectralFlux(x: FloatArray): DoubleArray {
        val frames = (x.size - n) / hop + 1
        val binHz = sampleRate.toDouble() / n
        val k0 = max(1, (30 / binHz).toInt())
        val k1 = min(n / 2, (16000 / binHz).toInt())
        val bins = k1 - k0 + 1
        val history = Array(lag + 1) { DoubleArray(bins) }
        val frame = DoubleArray(n)
        val re = DoubleArray(n)
        val im = DoubleArray(n)
        val power = DoubleArray(n / 2 + 1)
        val flux = DoubleArray(frames)
        val scale = 2.0 / (n / 2)
        for (t in 0 until frames) {
            val start = t * hop
            for (i in 0 until n) frame[i] = x[start + i] * window[i]
            fft.powerSpectrum(frame, re, im, power)
            val cur = history[t % (lag + 1)]
            for (k in 0 until bins) cur[k] = ln(1 + 1000 * sqrt(power[k0 + k]) * scale)
            if (t >= lag) {
                val prev = history[(t - lag) % (lag + 1)]
                var acc = 0.0
                for (k in 0 until bins) {
                    val d = cur[k] - prev[k]
                    if (d > 0) acc += d
                }
                flux[t] = acc / bins
            }
        }
        return flux
    }

    /** Walk back from the envelope peak near [centre] to where the attack starts. */
    private fun refine(env: FloatArray, centre: Int): Int {
        val a = max(0, centre - n / 2 - hop * lag)
        val b = min(env.size - 1, centre + n / 4)
        if (b <= a) return centre.coerceIn(0, env.size - 1)
        var peakIdx = a
        for (i in a..b) if (env[i] > env[peakIdx]) peakIdx = i
        val thr = env[peakIdx] * 0.12f
        var i = peakIdx
        while (i > a && env[i] > thr) {
            // A rising envelope going backwards means we reached the previous hit's tail.
            if (env[i - 1] > env[i] * 1.05f && env[i] < env[peakIdx] * 0.5f) break
            i--
        }
        return i
    }

    private fun normalized(x: FloatArray): FloatArray {
        val p = Stats.peak(x)
        if (p < 1e-6f) return x
        val g = 1f / p
        for (i in x.indices) x[i] *= g
        return x
    }

    private fun runningMedian(v: DoubleArray, radius: Int): DoubleArray {
        val out = DoubleArray(v.size)
        val buf = DoubleArray(2 * radius + 1)
        for (i in v.indices) {
            val lo = max(0, i - radius)
            val hi = min(v.size - 1, i + radius)
            val len = hi - lo + 1
            System.arraycopy(v, lo, buf, 0, len)
            java.util.Arrays.sort(buf, 0, len)
            out[i] = buf[len / 2]
        }
        return out
    }
}
