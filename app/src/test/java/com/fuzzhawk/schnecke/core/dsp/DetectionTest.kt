package com.fuzzhawk.schnecke.core.dsp

import com.fuzzhawk.schnecke.core.dsp.SyntheticTakes.SR
import com.fuzzhawk.schnecke.core.model.Layer
import com.fuzzhawk.schnecke.core.model.TakeInfo
import com.fuzzhawk.schnecke.core.model.Voice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class DetectionTest {

    private fun matchOnsets(found: IntArray, planted: List<SyntheticTakes.Planted>, tolMs: Double): List<Pair<SyntheticTakes.Planted, Int?>> =
        planted.map { p ->
            val best = found.minByOrNull { abs(it - p.sample) }
            p to best?.takeIf { abs(it - p.sample) <= tolMs * SR / 1000 }
        }

    @Test
    fun detectsEveryTapIncludingGhostNoteWithTightTiming() {
        val (audio, planted) = SyntheticTakes.handsGroove(bpm = 100.0, loopStart = SR)
        val onsets = OnsetDetector(SR).detect(audio)
        val matches = matchOnsets(onsets, planted, tolMs = 6.0)
        val missed = matches.filter { it.second == null }
        assertTrue("missed ${missed.map { it.first }} of ${planted.size}; found ${onsets.toList()}", missed.isEmpty())
        assertEquals("false positives: ${onsets.toList()}", planted.size, onsets.size)
        val worstMs = matches.maxOf { abs(it.second!! - it.first.sample) } * 1000.0 / SR
        assertTrue("timing error $worstMs ms", worstMs < 4.0)
    }

    @Test
    fun noBackgroundNoiseIsDetectedAsHits() {
        val rnd = kotlin.random.Random(9)
        val audio = SyntheticTakes.noise(rnd, SR * 4, 0.01)
        val onsets = OnsetDetector(SR).detect(audio)
        assertTrue("found ${onsets.size} onsets in pure noise", onsets.size <= 1)
    }

    @Test
    fun classifiesThumpsAsKickAndFlicksAsSnareOnDifferentSurfaces() {
        // A wooden table, a cardboard box and a metal railing ring at very different pitches.
        for ((body, ring) in listOf(110.0 to 2600.0, 70.0 to 1500.0, 220.0 to 4200.0)) {
            val (audio, planted) = SyntheticTakes.handsGroove(100.0, SR, seed = body.toInt(), bodyHz = body, ringHz = ring)
            val hits = TakeAnalyzer(SR).detect(audio, 0.5f)
            val voices = HitClassifier.classify(Layer.HANDS, hits, null)
            val byPlanted = planted.map { p -> voices[hits.indices.minBy { abs(hits[it].onset - p.sample) }] to p.voice }
            val wrong = byPlanted.count { it.first != it.second }
            assertEquals("surface $body/$ring Hz: $byPlanted", 0, wrong)
        }
    }

    @Test
    fun allKicksStayKicksWhenThereIsNoSecondSound() {
        val rnd = kotlin.random.Random(3)
        val out = SyntheticTakes.noise(rnd, SR * 4, 0.002)
        for (i in 0 until 6) SyntheticTakes.mixInto(out, SyntheticTakes.thump(rnd, 0.3 + 0.1 * (i % 3)), SR / 2 + i * SR / 2)
        val hits = TakeAnalyzer(SR).detect(out, 0.5f)
        assertEquals(6, hits.size)
        assertTrue(HitClassifier.classify(Layer.HANDS, hits, null).all { it == Voice.KICK })
    }

    @Test
    fun classifiesBeatboxHatsOpenHatsAndPercussion() {
        val (audio, planted) = SyntheticTakes.beatboxGroove(100.0, SR)
        val hits = TakeAnalyzer(SR).detect(audio, 0.5f)
        assertEquals(planted.size, hits.size)
        val voices = HitClassifier.classify(Layer.BEATBOX, hits, null)
        val pairs = planted.map { p -> voices[hits.indices.minBy { abs(hits[it].onset - p.sample) }] to p.voice }
        assertEquals(pairs.toString(), 0, pairs.count { it.first != it.second })
    }

    @Test
    fun taughtProfileClassifiesNewHits() {
        val rnd = kotlin.random.Random(5)
        val ex = FeatureExtractor(SR)
        fun featuresOf(sounds: List<FloatArray>): List<HitFeatures> {
            val out = SyntheticTakes.noise(rnd, SR * (sounds.size + 1) / 2, 0.002)
            val at = sounds.indices.map { SR / 4 + it * SR / 2 }
            sounds.forEachIndexed { i, s -> SyntheticTakes.mixInto(out, s, at[i]) }
            return ex.extract(out, at.toIntArray())
        }
        val profile = HitClassifier.train(
            mapOf(
                Voice.KICK to featuresOf(List(4) { SyntheticTakes.thump(rnd, 0.4) }),
                Voice.SNARE to featuresOf(List(4) { SyntheticTakes.flick(rnd, 0.4) }),
            ),
        )
        assertNotNull(profile)
        val (audio, planted) = SyntheticTakes.handsGroove(110.0, SR, seed = 11)
        val hits = TakeAnalyzer(SR).detect(audio, 0.5f)
        val voices = HitClassifier.classify(Layer.HANDS, hits, profile)
        val pairs = planted.map { p -> voices[hits.indices.minBy { abs(hits[it].onset - p.sample) }] to p.voice }
        assertEquals(pairs.toString(), 0, pairs.count { it.first != it.second })
        assertNull(HitClassifier.train(mapOf(Voice.KICK to featuresOf(listOf(SyntheticTakes.thump(rnd, 0.4))))))
    }

    @Test
    fun analyzerPlacesHitsOnTheLoopGridAndWrapsTheTail() {
        val bpm = 100.0
        val loopStart = SR // one second of count-in
        val (audio, planted) = SyntheticTakes.handsGroove(bpm, loopStart)
        val take = TakeInfo(SR, audio.size, loopStart, bpm, 8.0)
        val result = TakeAnalyzer(SR).analyze(audio, take, Layer.HANDS, 0.5f, null, stepsPerBeat = 4, dynamicVelocity = true)
        assertEquals(planted.size, result.loopHits.size)
        val expectedBeats = listOf(0.0, 1.0, 2.5, 3.0, 4.0, 4.75, 5.0, 6.5, 7.0, 7.5)
        result.loopHits.zip(expectedBeats).forEach { (hit, beat) ->
            assertEquals(beat, hit.beat, 0.02)
        }
        val ghost = result.loopHits.last()
        assertTrue("ghost note velocity ${ghost.velocity}", ghost.velocity < 80)
        assertTrue(result.loopHits.filter { it.voice == Voice.SNARE }.any { it.velocity == 127 })
    }

    @Test
    fun latencyCalibrationFindsMedianOffset() {
        val clicks = DoubleArray(8) { SR + it * SR * 0.6 }
        val taps = IntArray(8) { (clicks[it] + 0.030 * SR + (if (it % 2 == 0) 0.004 else -0.004) * SR).toInt() }
        val ms = LatencyCalibrator.offsetMs(taps, clicks, SR)!!
        assertEquals(30.0, ms, 4.5)
        assertNull(LatencyCalibrator.offsetMs(taps.copyOf(2), clicks, SR))
    }

    @Test
    fun rawLoopIsExactlyOneLoopLongAndGateSilencesNoise() {
        val bpm = 120.0
        val (audio, _) = SyntheticTakes.handsGroove(bpm, SR, noiseLevel = 0.01)
        val take = TakeInfo(SR, audio.size, SR, bpm, 8.0)
        val hits = TakeAnalyzer(SR).detect(audio, 0.5f)
        val loop = RawLoopBuilder.build(audio, take, hits, gate = true, stepsPerBeat = 4)
        assertEquals((8 * SR * 0.5).toInt(), loop.size)
        // Just before beat 2 (beat 1.9) there is only background noise, which the gate removes.
        val quiet = (1.9 * SR * 0.5).toInt()
        assertTrue(Stats.peak(loop, quiet - 200, quiet + 200) < 0.002f)
    }

    @Test
    fun cutsACleanSnareOneShotFromTheTake() {
        val (audio, _) = SyntheticTakes.handsGroove(100.0, SR)
        val hits = TakeAnalyzer(SR).detect(audio, 0.5f)
        val snares = hits.filterIndexed { i, _ -> HitClassifier.classify(Layer.HANDS, hits, null)[i] == Voice.SNARE }
        val best = SampleCutter.rank(snares, SR).first()
        val shot = SampleCutter.cut(audio, best, SR)
        assertTrue(shot.size in (SR * 0.03).toInt()..(SR * 0.6).toInt())
        assertEquals(0.9f, Stats.peak(shot), 0.01f)
        assertTrue("one-shot starts at the attack", Stats.peak(shot, 0, SR / 200) > 0.3f)
    }
}
