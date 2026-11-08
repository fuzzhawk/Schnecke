package com.fuzzhawk.schnecke.core.dsp

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max

internal object Stats {
    fun percentile(values: DoubleArray, p: Double): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.copyOf().also { it.sort() }
        val idx = ((sorted.size - 1) * p.coerceIn(0.0, 1.0)).toInt()
        return sorted[idx]
    }

    fun median(values: DoubleArray): Double = percentile(values, 0.5)

    fun median(values: List<Double>): Double = median(values.toDoubleArray())

    fun peak(x: FloatArray, from: Int = 0, to: Int = x.size): Float {
        var m = 0f
        for (i in from.coerceAtLeast(0) until to.coerceAtMost(x.size)) m = max(m, abs(x[i]))
        return m
    }

    fun db(linear: Double): Double = 20 * log10(max(linear, 1e-9))
}

/** Remove DC and sub-sonic rumble (wind, handling noise) with a 2nd-order high-pass. */
internal fun highPassCopy(x: FloatArray, sampleRate: Int, freq: Double): FloatArray {
    val hp = Biquad.highPass(sampleRate, freq)
    return FloatArray(x.size) { hp.process(x[it].toDouble()).toFloat() }
}

/** Rectified, moving-average amplitude envelope with a window of [windowSamples]. */
internal fun envelope(x: FloatArray, windowSamples: Int): FloatArray {
    val w = windowSamples.coerceAtLeast(1)
    val out = FloatArray(x.size)
    var acc = 0.0
    for (i in x.indices) {
        acc += abs(x[i])
        if (i >= w) acc -= abs(x[i - w])
        // Centre the window so the envelope does not lag the signal.
        val c = i - w / 2
        if (c >= 0) out[c] = (acc / w).toFloat()
    }
    for (c in (x.size - w / 2).coerceAtLeast(0) until x.size) out[c] = out[(x.size - w / 2 - 1).coerceAtLeast(0)]
    return out
}
