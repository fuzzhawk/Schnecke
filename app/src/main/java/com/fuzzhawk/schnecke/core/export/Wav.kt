package com.fuzzhawk.schnecke.core.export

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.random.Random

object Wav {
    /** Writes 16-bit PCM WAV with TPDF dither. [interleaved] holds [channels]-interleaved floats. */
    fun write16(out: OutputStream, interleaved: FloatArray, channels: Int, sampleRate: Int) {
        val dataBytes = interleaved.size * 2
        out.write(header(dataBytes, channels, sampleRate, bitsPerSample = 16, format = 1))
        val rnd = Random(0x5c4ec)
        val buf = ByteBuffer.allocate(8192).order(ByteOrder.LITTLE_ENDIAN)
        for (v in interleaved) {
            val dither = (rnd.nextFloat() - rnd.nextFloat()) / 32768f
            val s = ((v + dither).coerceIn(-1f, 1f) * 32767f).roundToInt()
            buf.putShort(s.toShort())
            if (!buf.hasRemaining()) { out.write(buf.array(), 0, buf.position()); buf.clear() }
        }
        out.write(buf.array(), 0, buf.position())
    }

    /** Streaming 16-bit WAV writer for exports too long to hold in memory. */
    class Writer16(private val out: OutputStream, private val channels: Int, sampleRate: Int, totalFrames: Long) {
        private val rnd = Random(0x5c4ec)
        private val buf = ByteBuffer.allocate(16384).order(ByteOrder.LITTLE_ENDIAN)

        init {
            val bytes = totalFrames * channels * 2
            require(bytes < Int.MAX_VALUE.toLong()) { "WAV files are limited to 2 GB" }
            out.write(header(bytes.toInt(), channels, sampleRate, bitsPerSample = 16, format = 1))
        }

        fun write(interleaved: FloatArray, frames: Int) {
            for (i in 0 until frames * channels) {
                val dither = (rnd.nextFloat() - rnd.nextFloat()) / 32768f
                val s = ((interleaved[i] + dither).coerceIn(-1f, 1f) * 32767f).roundToInt()
                buf.putShort(s.toShort())
                if (!buf.hasRemaining()) flushBuffer()
            }
        }

        fun finish() = flushBuffer()

        private fun flushBuffer() {
            out.write(buf.array(), 0, buf.position())
            buf.clear()
        }
    }

    fun encode16(interleaved: FloatArray, channels: Int, sampleRate: Int): ByteArray =
        ByteArrayOutputStream(44 + interleaved.size * 2).also { write16(it, interleaved, channels, sampleRate) }.toByteArray()

    fun monoToStereo(mono: FloatArray): FloatArray = FloatArray(mono.size * 2) { mono[it / 2] }

    private fun header(dataBytes: Int, channels: Int, sampleRate: Int, bitsPerSample: Int, format: Int): ByteArray {
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        val blockAlign = channels * bitsPerSample / 8
        b.put("RIFF".toByteArray(Charsets.US_ASCII))
        b.putInt(36 + dataBytes)
        b.put("WAVE".toByteArray(Charsets.US_ASCII))
        b.put("fmt ".toByteArray(Charsets.US_ASCII))
        b.putInt(16)
        b.putShort(format.toShort())
        b.putShort(channels.toShort())
        b.putInt(sampleRate)
        b.putInt(sampleRate * blockAlign)
        b.putShort(blockAlign.toShort())
        b.putShort(bitsPerSample.toShort())
        b.put("data".toByteArray(Charsets.US_ASCII))
        b.putInt(dataBytes)
        return b.array()
    }

    class Decoded(val mono: FloatArray, val sampleRate: Int, val channels: Int)

    /** Reads PCM (8/16/24/32-bit) or 32-bit float WAV, mixed down to mono. */
    fun decode(bytes: ByteArray): Decoded {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun tag(): String { val a = ByteArray(4); b.get(a); return String(a, Charsets.US_ASCII) }
        require(bytes.size >= 12 && tag() == "RIFF") { "Not a WAV file" }
        b.getInt()
        require(tag() == "WAVE") { "Not a WAV file" }
        var format = 0
        var channels = 0
        var sampleRate = 0
        var bits = 0
        var data: ByteBuffer? = null
        while (b.remaining() >= 8) {
            val id = tag()
            val size = b.getInt()
            if (size < 0 || size > b.remaining()) {
                if (id == "data") data = b.slice().order(ByteOrder.LITTLE_ENDIAN)
                break
            }
            when (id) {
                "fmt " -> {
                    val start = b.position()
                    format = b.getShort().toInt() and 0xffff
                    channels = b.getShort().toInt()
                    sampleRate = b.getInt()
                    b.getInt(); b.getShort()
                    bits = b.getShort().toInt()
                    if (format == 0xFFFE && size >= 40) {
                        b.getShort(); b.getShort(); b.getInt()
                        format = b.getShort().toInt() and 0xffff
                    }
                    b.position(start + size)
                }
                "data" -> {
                    val slice = b.slice().order(ByteOrder.LITTLE_ENDIAN)
                    slice.limit(size)
                    data = slice
                    b.position(b.position() + size)
                }
                else -> b.position(b.position() + size)
            }
            if (size % 2 == 1 && b.hasRemaining()) b.get()
        }
        val d = requireNotNull(data) { "WAV has no data" }
        require(channels in 1..8 && sampleRate > 0) { "Unsupported WAV format" }
        require(format == 1 || format == 3) { "Only PCM or float WAV files are supported" }
        val bytesPer = bits / 8
        require(bytesPer in 1..4) { "Unsupported bit depth $bits" }
        val frames = d.remaining() / (bytesPer * channels)
        val mono = FloatArray(frames)
        for (f in 0 until frames) {
            var acc = 0f
            for (c in 0 until channels) {
                acc += when {
                    format == 3 && bits == 32 -> d.getFloat()
                    bits == 8 -> ((d.get().toInt() and 0xff) - 128) / 128f
                    bits == 16 -> d.getShort() / 32768f
                    bits == 24 -> {
                        val lo = d.get().toInt() and 0xff
                        val mid = d.get().toInt() and 0xff
                        val hi = d.get().toInt()
                        ((hi shl 16) or (mid shl 8) or lo) / 8388608f
                    }
                    bits == 32 -> d.getInt() / 2147483648f
                    else -> error("Unsupported WAV format")
                }
            }
            mono[f] = acc / channels
        }
        return Decoded(mono, sampleRate, channels)
    }

    /** Cubic (Catmull-Rom) resampling, good enough for drum one-shots. */
    fun resample(x: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to || x.isEmpty()) return x
        val ratio = from.toDouble() / to
        val n = (x.size / ratio).toInt()
        fun at(i: Int) = if (i < 0) x[0] else if (i >= x.size) x[x.size - 1] else x[i]
        return FloatArray(n) { j ->
            val pos = j * ratio
            val i = floor(pos).toInt()
            val t = (pos - i).toFloat()
            val p0 = at(i - 1); val p1 = at(i); val p2 = at(i + 1); val p3 = at(i + 2)
            0.5f * (2 * p1 + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t * t + (-p0 + 3 * p1 - 3 * p2 + p3) * t * t * t)
        }
    }
}
