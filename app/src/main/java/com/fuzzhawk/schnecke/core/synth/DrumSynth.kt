package com.fuzzhawk.schnecke.core.synth

import com.fuzzhawk.schnecke.core.dsp.Biquad
import com.fuzzhawk.schnecke.core.model.Voice
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.tanh
import kotlin.random.Random

enum class SampleCategory { KICK, SNARE, HAT, OPEN_HAT, PERC }

data class LibrarySample(val id: String, val name: String, val category: SampleCategory)

/**
 * The built-in drum kit. Every sound is synthesized (no bundled recordings), deterministic and
 * rendered at whatever sample rate the engine runs at.
 */
object DrumSynth {

    val library: List<LibrarySample> = listOf(
        LibrarySample("kick_punch", "Punch kick", SampleCategory.KICK),
        LibrarySample("kick_808", "808 kick", SampleCategory.KICK),
        LibrarySample("kick_tight", "Tight kick", SampleCategory.KICK),
        LibrarySample("kick_thud", "Soft thud", SampleCategory.KICK),
        LibrarySample("snare_tight", "Tight snare", SampleCategory.SNARE),
        LibrarySample("snare_fat", "Fat snare", SampleCategory.SNARE),
        LibrarySample("clap", "Clap", SampleCategory.SNARE),
        LibrarySample("rim", "Rimshot", SampleCategory.PERC),
        LibrarySample("hat_closed", "Closed hat", SampleCategory.HAT),
        LibrarySample("hat_tick", "Tick hat", SampleCategory.HAT),
        LibrarySample("shaker", "Shaker", SampleCategory.HAT),
        LibrarySample("hat_open", "Open hat", SampleCategory.OPEN_HAT),
        LibrarySample("hat_sizzle", "Sizzle hat", SampleCategory.OPEN_HAT),
        LibrarySample("cowbell", "Cowbell", SampleCategory.PERC),
        LibrarySample("conga", "Conga", SampleCategory.PERC),
        LibrarySample("tom", "Low tom", SampleCategory.PERC),
        LibrarySample("clave", "Clave", SampleCategory.PERC),
        LibrarySample("tambourine", "Tambourine", SampleCategory.PERC),
    )

    fun byId(id: String): LibrarySample? = library.firstOrNull { it.id == id }

    /** Library sounds that make sense for a voice, best matches first. */
    fun choicesFor(voice: Voice): List<LibrarySample> {
        val primary = when (voice) {
            Voice.KICK -> listOf(SampleCategory.KICK)
            Voice.SNARE -> listOf(SampleCategory.SNARE, SampleCategory.PERC)
            Voice.HIHAT -> listOf(SampleCategory.HAT, SampleCategory.OPEN_HAT)
            Voice.OPEN_HAT -> listOf(SampleCategory.OPEN_HAT, SampleCategory.HAT)
            Voice.PERC -> listOf(SampleCategory.PERC, SampleCategory.SNARE, SampleCategory.HAT)
        }
        return primary.flatMap { c -> library.filter { it.category == c } }
    }

    fun render(id: String, sr: Int): FloatArray {
        val rnd = Random(id.hashCode())
        val out = when (id) {
            "kick_punch" -> kick(sr, 0.45, 48.0, 165.0, 0.035, 0.16, click = 0.35, drive = 1.6)
            "kick_808" -> kick(sr, 1.2, 46.0, 115.0, 0.06, 0.45, click = 0.1, drive = 2.2)
            "kick_tight" -> kick(sr, 0.25, 62.0, 220.0, 0.018, 0.07, click = 0.5, drive = 1.3)
            "kick_thud" -> kick(sr, 0.35, 55.0, 110.0, 0.05, 0.11, click = 0.05, drive = 1.0, noise = 0.25, rnd = rnd)
            "snare_tight" -> snare(sr, rnd, 0.32, 190.0, 0.05, 0.11, 0.8)
            "snare_fat" -> snare(sr, rnd, 0.45, 165.0, 0.08, 0.2, 0.7)
            "clap" -> clap(sr, rnd)
            "rim" -> rim(sr, rnd)
            "hat_closed" -> metalHat(sr, rnd, 0.14, 0.035, 7000.0)
            "hat_tick" -> metalHat(sr, rnd, 0.07, 0.014, 9000.0)
            "hat_open" -> metalHat(sr, rnd, 0.7, 0.28, 7000.0)
            "hat_sizzle" -> metalHat(sr, rnd, 1.0, 0.45, 9500.0)
            "shaker" -> shaker(sr, rnd)
            "cowbell" -> cowbell(sr)
            "conga" -> tonal(sr, rnd, 0.3, 330.0, 295.0, 0.11, 0.15)
            "tom" -> tonal(sr, rnd, 0.5, 150.0, 118.0, 0.2, 0.1)
            "clave" -> tonal(sr, rnd, 0.12, 2500.0, 2480.0, 0.022, 0.0)
            "tambourine" -> tambourine(sr, rnd)
            else -> FloatArray(0)
        }
        return normalize(out, gainFor(byId(id)?.category))
    }

    /** Short click used by the metronome; the accent is higher and louder. */
    fun click(sr: Int, accent: Boolean): FloatArray {
        val f = if (accent) 1760.0 else 1175.0
        val n = (0.03 * sr).toInt()
        return FloatArray(n) { i ->
            val t = i / sr.toDouble()
            (sin(2 * PI * f * t) * exp(-t / 0.006) * (if (accent) 0.9 else 0.6)).toFloat()
        }
    }

    private fun gainFor(c: SampleCategory?) = when (c) {
        SampleCategory.HAT, SampleCategory.OPEN_HAT -> 0.55f
        SampleCategory.PERC -> 0.75f
        else -> 0.9f
    }

    private fun kick(
        sr: Int, len: Double, endHz: Double, startHz: Double, pitchTau: Double, ampTau: Double,
        click: Double, drive: Double, noise: Double = 0.0, rnd: Random? = null,
    ): FloatArray {
        val n = (len * sr).toInt()
        val out = FloatArray(n)
        var phase = 0.0
        val clickHp = Biquad.highPass(sr, 2500.0)
        val noiseLp = Biquad.lowPass(sr, 600.0)
        for (i in 0 until n) {
            val t = i / sr.toDouble()
            val f = endHz + (startHz - endHz) * exp(-t / pitchTau)
            phase += 2 * PI * f / sr
            val attack = (t / 0.0015).coerceAtMost(1.0)
            var s = sin(phase) * exp(-t / ampTau) * attack
            val c = clickHp.process(if (i < sr / 500) (if (i % 7 < 3) 1.0 else -1.0) else 0.0)
            s += c * click * exp(-t / 0.004)
            if (noise > 0 && rnd != null) s += noiseLp.process(rnd.nextDouble() * 2 - 1) * noise * exp(-t / 0.03)
            out[i] = (tanh(s * drive) / tanh(drive)).toFloat()
        }
        return out
    }

    private fun snare(sr: Int, rnd: Random, len: Double, toneHz: Double, toneTau: Double, noiseTau: Double, noiseAmt: Double): FloatArray {
        val n = (len * sr).toInt()
        val hp = Biquad.highPass(sr, 1400.0)
        val lp = Biquad.lowPass(sr, 9000.0)
        val peak = Biquad.peaking(sr, 4000.0, 1.0, 4.0)
        var p1 = 0.0
        var p2 = 0.0
        return FloatArray(n) { i ->
            val t = i / sr.toDouble()
            val f = toneHz * (1 + 0.3 * exp(-t / 0.01))
            p1 += 2 * PI * f / sr
            p2 += 2 * PI * f * 1.78 / sr
            val tone = (sin(p1) + 0.6 * sin(p2)) * exp(-t / toneTau) * 0.6
            val noise = peak.process(lp.process(hp.process(rnd.nextDouble() * 2 - 1))) * exp(-t / noiseTau) * noiseAmt
            val attack = (t / 0.001).coerceAtMost(1.0)
            ((tone + noise) * attack).toFloat()
        }
    }

    private fun clap(sr: Int, rnd: Random): FloatArray {
        val n = (0.4 * sr).toInt()
        val bp = Biquad.bandPass(sr, 1200.0, 1.4)
        val bursts = doubleArrayOf(0.0, 0.011, 0.022)
        return FloatArray(n) { i ->
            val t = i / sr.toDouble()
            var env = 0.0
            for (b in bursts) if (t >= b) env = max(env, exp(-(t - b) / 0.0035))
            if (t >= 0.031) env = max(env, 0.8 * exp(-(t - 0.031) / 0.11))
            (bp.process(rnd.nextDouble() * 2 - 1) * env * 3).toFloat()
        }
    }

    private fun rim(sr: Int, rnd: Random): FloatArray {
        val n = (0.12 * sr).toInt()
        val bp = Biquad.bandPass(sr, 1700.0, 4.0)
        return FloatArray(n) { i ->
            val t = i / sr.toDouble()
            val tone = (sin(2 * PI * 1700 * t) + 0.5 * sin(2 * PI * 820 * t)) * exp(-t / 0.012)
            val noise = bp.process(rnd.nextDouble() * 2 - 1) * exp(-t / 0.005) * 2
            (tone + noise).toFloat()
        }
    }

    /** 808-style hat: six detuned square waves through band and high-pass filters. */
    private fun metalHat(sr: Int, rnd: Random, len: Double, tau: Double, hpHz: Double): FloatArray {
        val n = (len * sr).toInt()
        val freqs = doubleArrayOf(205.3, 304.4, 369.6, 522.7, 540.0, 800.0).map { it * 1.6 }
        val phases = DoubleArray(freqs.size)
        val bp = Biquad.bandPass(sr, 10000.0, 0.9)
        val hp = Biquad.highPass(sr, hpHz)
        val hp2 = Biquad.highPass(sr, hpHz)
        return FloatArray(n) { i ->
            val t = i / sr.toDouble()
            var s = 0.0
            for (k in freqs.indices) {
                phases[k] += freqs[k] / sr
                s += if (phases[k] % 1.0 < 0.5) 1.0 else -1.0
            }
            s = s / freqs.size + (rnd.nextDouble() * 2 - 1) * 0.5
            val attack = (t / 0.0008).coerceAtMost(1.0)
            (hp2.process(hp.process(bp.process(s))) * exp(-t / tau) * attack * 2.5).toFloat()
        }
    }

    private fun shaker(sr: Int, rnd: Random): FloatArray {
        val n = (0.2 * sr).toInt()
        val hp = Biquad.highPass(sr, 5000.0)
        val lp = Biquad.lowPass(sr, 12000.0)
        return FloatArray(n) { i ->
            val t = i / sr.toDouble()
            val env = if (t < 0.018) t / 0.018 else exp(-(t - 0.018) / 0.05)
            (lp.process(hp.process(rnd.nextDouble() * 2 - 1)) * env).toFloat()
        }
    }

    private fun cowbell(sr: Int): FloatArray {
        val n = (0.45 * sr).toInt()
        val bp = Biquad.bandPass(sr, 2600.0, 1.2)
        var a = 0.0
        var b = 0.0
        return FloatArray(n) { i ->
            val t = i / sr.toDouble()
            a += 587.0 / sr
            b += 845.0 / sr
            val sq = (if (a % 1.0 < 0.5) 1.0 else -1.0) + (if (b % 1.0 < 0.5) 1.0 else -1.0)
            val env = 0.65 * exp(-t / 0.02) + 0.35 * exp(-t / 0.16)
            (bp.process(sq) * env * 2).toFloat()
        }
    }

    private fun tonal(sr: Int, rnd: Random, len: Double, startHz: Double, endHz: Double, tau: Double, noise: Double): FloatArray {
        val n = (len * sr).toInt()
        var phase = 0.0
        val bp = Biquad.bandPass(sr, startHz * 3, 1.5)
        return FloatArray(n) { i ->
            val t = i / sr.toDouble()
            val f = endHz + (startHz - endHz) * exp(-t / 0.02)
            phase += 2 * PI * f / sr
            val attack = (t / 0.001).coerceAtMost(1.0)
            val s = sin(phase) * exp(-t / tau) + bp.process(rnd.nextDouble() * 2 - 1) * noise * exp(-t / 0.01)
            (s * attack).toFloat()
        }
    }

    private fun tambourine(sr: Int, rnd: Random): FloatArray {
        val n = (0.35 * sr).toInt()
        val hp = Biquad.highPass(sr, 6000.0)
        val bp = Biquad.bandPass(sr, 9000.0, 2.0)
        return FloatArray(n) { i ->
            val t = i / sr.toDouble()
            // Jingles: a fast tremolo on decaying bright noise.
            val jingle = 0.6 + 0.4 * sin(2 * PI * 38 * t)
            val env = (t / 0.002).coerceAtMost(1.0) * exp(-t / 0.09) * jingle
            (bp.process(hp.process(rnd.nextDouble() * 2 - 1)) * env * 3).toFloat()
        }
    }

    private fun normalize(x: FloatArray, target: Float): FloatArray {
        var p = 0f
        for (v in x) p = max(p, abs(v))
        if (p > 1e-6f) for (i in x.indices) x[i] *= target / p
        // Fade the very end so no sample ends with a click.
        val fade = minOf(x.size / 4, 256)
        for (i in 0 until fade) x[x.size - 1 - i] *= i / fade.toFloat()
        return x
    }
}
