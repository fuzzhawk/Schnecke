package com.fuzzhawk.schnecke.core.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** In-place iterative radix-2 complex FFT of a fixed power-of-two size. */
class Fft(val size: Int) {
    private val cosTable: DoubleArray
    private val sinTable: DoubleArray
    private val rev: IntArray

    init {
        require(size >= 2 && size and (size - 1) == 0) { "FFT size must be a power of two" }
        cosTable = DoubleArray(size / 2) { cos(2.0 * PI * it / size) }
        sinTable = DoubleArray(size / 2) { -sin(2.0 * PI * it / size) }
        val bits = Integer.numberOfTrailingZeros(size)
        rev = IntArray(size) { Integer.reverse(it) ushr (32 - bits) }
    }

    fun transform(re: DoubleArray, im: DoubleArray) {
        for (i in 0 until size) {
            val j = rev[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= size) {
            val half = len / 2
            val step = size / len
            var i = 0
            while (i < size) {
                var k = 0
                for (j in i until i + half) {
                    val wr = cosTable[k]
                    val wi = sinTable[k]
                    val l = j + half
                    val tr = re[l] * wr - im[l] * wi
                    val ti = re[l] * wi + im[l] * wr
                    re[l] = re[j] - tr
                    im[l] = im[j] - ti
                    re[j] += tr
                    im[j] += ti
                    k += step
                }
                i += len
            }
            len *= 2
        }
    }

    /** Power spectrum (|X|^2) of a real frame, bins 0..size/2. */
    fun powerSpectrum(frame: DoubleArray, re: DoubleArray, im: DoubleArray, out: DoubleArray) {
        System.arraycopy(frame, 0, re, 0, size)
        java.util.Arrays.fill(im, 0.0)
        transform(re, im)
        for (k in 0..size / 2) out[k] = re[k] * re[k] + im[k] * im[k]
    }

    companion object {
        fun hann(n: Int): DoubleArray = DoubleArray(n) { 0.5 - 0.5 * cos(2.0 * PI * it / n) }
    }
}
