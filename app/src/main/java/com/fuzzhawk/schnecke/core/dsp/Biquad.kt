package com.fuzzhawk.schnecke.core.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** RBJ cookbook biquad, direct form I, double precision. */
class Biquad private constructor(
    private val b0: Double, private val b1: Double, private val b2: Double,
    private val a1: Double, private val a2: Double,
) {
    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    fun process(x: Double): Double {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x
        y2 = y1; y1 = y
        return y
    }

    fun processInPlace(buf: FloatArray) {
        for (i in buf.indices) buf[i] = process(buf[i].toDouble()).toFloat()
    }

    companion object {
        private fun make(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) =
            Biquad(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)

        fun lowPass(sr: Int, freq: Double, q: Double = 0.7071): Biquad {
            val w = 2 * PI * freq.coerceAtMost(sr * 0.45) / sr
            val alpha = sin(w) / (2 * q)
            val c = cos(w)
            return make((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + alpha, -2 * c, 1 - alpha)
        }

        fun highPass(sr: Int, freq: Double, q: Double = 0.7071): Biquad {
            val w = 2 * PI * freq.coerceAtMost(sr * 0.45) / sr
            val alpha = sin(w) / (2 * q)
            val c = cos(w)
            return make((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + alpha, -2 * c, 1 - alpha)
        }

        /** Band-pass with 0 dB peak gain. */
        fun bandPass(sr: Int, freq: Double, q: Double): Biquad {
            val w = 2 * PI * freq.coerceAtMost(sr * 0.45) / sr
            val alpha = sin(w) / (2 * q)
            val c = cos(w)
            return make(alpha, 0.0, -alpha, 1 + alpha, -2 * c, 1 - alpha)
        }

        fun peaking(sr: Int, freq: Double, q: Double, gainDb: Double): Biquad {
            val a = Math.pow(10.0, gainDb / 40)
            val w = 2 * PI * freq.coerceAtMost(sr * 0.45) / sr
            val alpha = sin(w) / (2 * q)
            val c = cos(w)
            return make(1 + alpha * a, -2 * c, 1 - alpha * a, 1 + alpha / a, -2 * c, 1 - alpha / a)
        }

        fun butterworthQ(): Double = 1 / sqrt(2.0)
    }
}
