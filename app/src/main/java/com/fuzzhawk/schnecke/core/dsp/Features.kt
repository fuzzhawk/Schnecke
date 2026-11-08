package com.fuzzhawk.schnecke.core.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min

/** Acoustic description of one hit. */
data class HitFeatures(
    val onset: Int,
    /** Where the next hit starts (or the end of the recording); bounds the hit's tail. */
    val limit: Int,
    val peak: Float,
    val peakIdx: Int,
    val centroidHz: Double,
    val rolloffHz: Double,
    val lowRatio: Double,
    val highRatio: Double,
    val flatness: Double,
    val zcrPerMs: Double,
    val attackMs: Double,
    val decayMs: Double,
) {
    /** Timbre-only vector used for hands (kick vs snare) clustering. */
    fun timbre(): DoubleArray = doubleArrayOf(
        log2(centroidHz.coerceAtLeast(20.0)),
        lowRatio,
        highRatio,
        log2(rolloffHz.coerceAtLeast(20.0)),
        flatness,
        zcrPerMs,
    )

    /** Timbre plus length, so a trained profile can tell an open hat from a closed one. */
    fun full(): DoubleArray = timbre() + doubleArrayOf(log2(decayMs.coerceAtLeast(1.0)))

    val peakDb: Double get() = Stats.db(peak.toDouble())
}

class FeatureExtractor(private val sampleRate: Int) {
    private val n = 2048
    private val fft = Fft(n)

    fun extract(audio: FloatArray, onsets: IntArray): List<HitFeatures> {
        val env = envelope(audio, max(1, sampleRate / 1000))
        return onsets.mapIndexed { i, onset ->
            val limit = if (i + 1 < onsets.size) onsets[i + 1] else audio.size
            extractOne(audio, env, onset, limit)
        }
    }

    private fun extractOne(audio: FloatArray, env: FloatArray, onset: Int, limit: Int): HitFeatures {
        val pre = (0.0015 * sampleRate).toInt()
        val start = max(0, onset - pre)
        val segLen = min(n, max(64, limit - start - (0.001 * sampleRate).toInt()))
        val frame = DoubleArray(n)
        val taper = max(1, segLen / 10)
        for (i in 0 until segLen) {
            val idx = start + i
            if (idx >= audio.size) break
            // Tukey window: keep the attack, fade only the edges.
            val w = when {
                i < taper -> 0.5 - 0.5 * cos(PI * i / taper)
                i >= segLen - taper -> 0.5 - 0.5 * cos(PI * (segLen - 1 - i) / taper)
                else -> 1.0
            }
            frame[i] = audio[idx] * w
        }
        val re = DoubleArray(n)
        val im = DoubleArray(n)
        val power = DoubleArray(n / 2 + 1)
        fft.powerSpectrum(frame, re, im, power)

        val binHz = sampleRate.toDouble() / n
        var total = 0.0
        var weighted = 0.0
        var low = 0.0
        var high = 0.0
        var logSum = 0.0
        var flatCount = 0
        var flatSum = 0.0
        val kMax = min(n / 2, (20000 / binHz).toInt())
        for (k in 1..kMax) {
            val f = k * binHz
            val p = power[k]
            total += p
            weighted += f * p
            if (f < 250) low += p
            if (f > 3000) high += p
            if (f in 150.0..16000.0) {
                logSum += ln(p + 1e-12)
                flatSum += p
                flatCount++
            }
        }
        total = total.coerceAtLeast(1e-12)
        var cumulative = 0.0
        var rolloff = kMax * binHz
        for (k in 1..kMax) {
            cumulative += power[k]
            if (cumulative >= 0.85 * total) { rolloff = k * binHz; break }
        }
        val flatness = if (flatCount > 0 && flatSum > 0) {
            exp(logSum / flatCount) / (flatSum / flatCount)
        } else 0.0

        // Zero-crossing rate over the first 20 ms.
        val zEnd = min(min(limit, audio.size), onset + (0.02 * sampleRate).toInt())
        var crossings = 0
        for (i in onset + 1 until zEnd) if ((audio[i - 1] >= 0) != (audio[i] >= 0)) crossings++
        val zcr = if (zEnd > onset + 1) crossings / ((zEnd - onset) * 1000.0 / sampleRate) else 0.0

        // Attack: onset to envelope peak (within 40 ms). Decay: peak until -20 dB.
        val peakSearchEnd = min(min(limit, audio.size), onset + (0.04 * sampleRate).toInt())
        var peakIdx = onset.coerceIn(0, audio.size - 1)
        for (i in onset until peakSearchEnd) if (env[i] > env[peakIdx]) peakIdx = i
        var rawPeak = 0f
        for (i in max(0, onset) until min(audio.size, max(peakSearchEnd, onset + 1))) rawPeak = max(rawPeak, abs(audio[i]))
        val decayLimit = min(min(limit, audio.size), peakIdx + (0.8 * sampleRate).toInt())
        val thr = env[peakIdx] * 0.1f
        var d = peakIdx
        while (d < decayLimit - 1 && env[d] > thr) d++
        return HitFeatures(
            onset = onset,
            limit = limit,
            peak = rawPeak,
            peakIdx = peakIdx,
            centroidHz = weighted / total,
            rolloffHz = rolloff,
            lowRatio = low / total,
            highRatio = high / total,
            flatness = flatness,
            zcrPerMs = zcr,
            attackMs = (peakIdx - onset) * 1000.0 / sampleRate,
            decayMs = (d - peakIdx) * 1000.0 / sampleRate,
        )
    }

    companion object {
        fun log2Safe(v: Double) = log2(v.coerceAtLeast(1e-9))
    }
}
