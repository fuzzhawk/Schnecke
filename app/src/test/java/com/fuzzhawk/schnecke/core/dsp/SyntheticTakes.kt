package com.fuzzhawk.schnecke.core.dsp

import com.fuzzhawk.schnecke.core.model.Voice
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/** Synthetic stand-ins for real recordings: surface taps and beatbox sounds over background noise. */
object SyntheticTakes {
    const val SR = 48000

    data class Planted(val sample: Int, val voice: Voice)

    /** Fingertip thump: a damped low "body" resonance of the surface plus a little knock. */
    fun thump(rnd: Random, gain: Double, bodyHz: Double = 110.0): FloatArray {
        val n = (0.18 * SR).toInt()
        val lp = Biquad.lowPass(SR, 900.0)
        return FloatArray(n) { i ->
            val t = i / SR.toDouble()
            val attack = (t / 0.003).coerceAtMost(1.0)
            val body = sin(2 * PI * bodyHz * t) * exp(-t / 0.05)
            val knock = lp.process((rnd.nextDouble() * 2 - 1)) * exp(-t / 0.012) * 0.6
            ((body + knock) * attack * gain).toFloat()
        }
    }

    /** Fingernail flick: a sharp bright click with a short ring of the surface. */
    fun flick(rnd: Random, gain: Double, ringHz: Double = 2600.0): FloatArray {
        val n = (0.12 * SR).toInt()
        val hp = Biquad.highPass(SR, 1800.0)
        return FloatArray(n) { i ->
            val t = i / SR.toDouble()
            val click = hp.process(rnd.nextDouble() * 2 - 1) * exp(-t / 0.006)
            val ring = sin(2 * PI * ringHz * t) * exp(-t / 0.03) * 0.5
            ((click + ring) * gain).toFloat()
        }
    }

    /** Beatbox "ts" (closed hat) or "tsss" (open hat): bright hiss. */
    fun hiss(rnd: Random, gain: Double, lengthS: Double): FloatArray {
        val n = (lengthS * SR * 1.3).toInt()
        val hp = Biquad.highPass(SR, 5000.0)
        val hp2 = Biquad.highPass(SR, 5000.0)
        return FloatArray(n) { i ->
            val t = i / SR.toDouble()
            val env = (t / 0.004).coerceAtMost(1.0) * exp(-t / (lengthS / 3))
            (hp2.process(hp.process(rnd.nextDouble() * 2 - 1)) * env * gain).toFloat()
        }
    }

    /** Beatbox "k" click: a mid burst with some low body. */
    fun kSound(rnd: Random, gain: Double): FloatArray {
        val n = (0.08 * SR).toInt()
        val bp = Biquad.bandPass(SR, 1200.0, 1.2)
        return FloatArray(n) { i ->
            val t = i / SR.toDouble()
            val burst = bp.process(rnd.nextDouble() * 2 - 1) * exp(-t / 0.015) * 2.0
            val body = sin(2 * PI * 300 * t) * exp(-t / 0.02) * 0.5
            ((burst + body) * gain).toFloat()
        }
    }

    /** Mixes [sound] in at [at], fading out its last 20% so it ends like a real sound would. */
    fun mixInto(out: FloatArray, sound: FloatArray, at: Int) {
        val fade = sound.size / 5
        for (i in sound.indices) {
            val idx = at + i
            val g = if (i >= sound.size - fade) (sound.size - i).toFloat() / fade else 1f
            if (idx in out.indices) out[idx] += sound[i] * g
        }
    }

    fun noise(rnd: Random, n: Int, level: Double): FloatArray {
        val lp = Biquad.lowPass(SR, 4000.0)
        return FloatArray(n) { (lp.process(rnd.nextDouble() * 2 - 1) * level).toFloat() }
    }

    /**
     * A 2-bar 4/4 hands groove: kick on 1 and the "and" of 3, snare on 2 and 4, with human timing
     * jitter, loudness variation and a quiet ghost snare.
     */
    fun handsGroove(
        bpm: Double,
        loopStart: Int,
        seed: Int = 1,
        noiseLevel: Double = 0.002,
        bodyHz: Double = 110.0,
        ringHz: Double = 2600.0,
        repeats: Int = 1,
    ): Pair<FloatArray, List<Planted>> {
        val rnd = Random(seed)
        val fpb = SR * 60.0 / bpm
        val total = loopStart + (8 * repeats * fpb).toInt() + SR / 2
        val out = noise(rnd, total, noiseLevel)
        val planted = ArrayList<Planted>()
        val pattern = listOf(
            0.0 to Voice.KICK, 1.0 to Voice.SNARE, 2.5 to Voice.KICK, 3.0 to Voice.SNARE,
            4.0 to Voice.KICK, 4.75 to Voice.KICK, 5.0 to Voice.SNARE, 6.5 to Voice.KICK,
            7.0 to Voice.SNARE, 7.5 to Voice.SNARE,
        )
        for (r in 0 until repeats) for ((b, v) in pattern) {
            val beat = b + 8 * r
            val jitter = ((rnd.nextDouble() * 2 - 1) * 0.008 * SR).toInt()
            val at = loopStart + (beat * fpb).toInt() + jitter
            val ghost = b == 7.5
            val gain = if (ghost) 0.12 else 0.4 + rnd.nextDouble() * 0.4
            val sound = if (v == Voice.KICK) thump(rnd, gain, bodyHz) else flick(rnd, gain, ringHz)
            mixInto(out, sound, at)
            planted.add(Planted(at, v))
        }
        return out to planted
    }

    fun beatboxGroove(bpm: Double, loopStart: Int, seed: Int = 2): Pair<FloatArray, List<Planted>> {
        val rnd = Random(seed)
        val fpb = SR * 60.0 / bpm
        val total = loopStart + (8 * fpb).toInt() + SR / 2
        val out = noise(rnd, total, 0.002)
        val planted = ArrayList<Planted>()
        for (i in 0 until 16) {
            val beat = i * 0.5
            val v = when {
                i % 8 == 7 -> Voice.OPEN_HAT
                i % 4 == 2 -> Voice.PERC
                else -> Voice.HIHAT
            }
            val at = loopStart + (beat * fpb).toInt() + ((rnd.nextDouble() * 2 - 1) * 0.006 * SR).toInt()
            val gain = 0.25 + rnd.nextDouble() * 0.25
            val sound = when (v) {
                Voice.HIHAT -> hiss(rnd, gain, 0.05)
                Voice.OPEN_HAT -> hiss(rnd, gain, 0.3)
                else -> kSound(rnd, gain)
            }
            mixInto(out, sound, at)
            planted.add(Planted(at, v))
        }
        return out to planted
    }
}
