package com.fuzzhawk.schnecke.core.dsp

import com.fuzzhawk.schnecke.core.dsp.SyntheticTakes.SR
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class LoopFinderTest {

    @Test
    fun findsATwoBarGroovePlayedTwiceWithoutAMetronome() {
        for (bpm in listOf(84.0, 100.0, 126.0)) {
            val start = (0.73 * SR).toInt()
            val (audio, planted) = SyntheticTakes.handsGroove(bpm, loopStart = start, seed = bpm.toInt(), repeats = 2)
            val hits = TakeAnalyzer(SR).detect(audio, 0.5f)
            val loop = LoopFinder.suggest(hits, audio.size, SR, beatsPerBar = 4)
            assertTrue("in ${loop.inFrame} vs ${planted.first().sample}", abs(loop.inFrame - planted.first().sample) < SR / 200)
            // The repeat starts with the 11th hit (the first of the second pass).
            assertTrue("out ${loop.outFrame} vs ${planted[10].sample}", abs(loop.outFrame - planted[10].sample) < SR / 200)
            assertEquals(2, loop.bars)
            assertEquals("bpm at $bpm", bpm, loop.bpm(SR), bpm * 0.012)
        }
    }

    @Test
    fun halfBarRepeatsAreExtendedToAWholeBar() {
        // Kick-snare-kick-snare on every beat repeats every two beats; the loop should be a full bar.
        val rnd = kotlin.random.Random(4)
        val fpb = SR * 0.6 // 100 bpm
        val out = SyntheticTakes.noise(rnd, (SR * 0.5 + 12 * fpb).toInt(), 0.002)
        for (i in 0 until 12) {
            val s = if (i % 2 == 0) SyntheticTakes.thump(rnd, 0.5) else SyntheticTakes.flick(rnd, 0.5)
            SyntheticTakes.mixInto(out, s, (SR * 0.5 + i * fpb).toInt())
        }
        val hits = TakeAnalyzer(SR).detect(out, 0.5f)
        val loop = LoopFinder.suggest(hits, out.size, SR, 4)
        assertEquals(1, loop.bars)
        assertEquals(100.0, loop.bpm(SR), 1.5)
    }

    @Test
    fun tooFewHitsFallsBackToTheWholeRecording() {
        val hits = TakeAnalyzer(SR).detect(SyntheticTakes.noise(kotlin.random.Random(1), SR * 3, 0.002), 0.5f)
        val loop = LoopFinder.suggest(hits, SR * 3, SR, 4)
        assertEquals(SR * 3, loop.outFrame)
    }

    @Test
    fun barsAndSnapHelpers() {
        assertEquals(1, LoopFinder.barsFor(2.4, 4)) // 100 bpm
        assertEquals(2, LoopFinder.barsFor(4.8, 4))
        assertEquals(4, LoopFinder.barsFor(8.0, 4)) // 120 bpm
        assertEquals(100, LoopFinder.snap(103, intArrayOf(50, 100, 200), 10))
        assertEquals(130, LoopFinder.snap(130, intArrayOf(50, 100, 200), 10))
        assertEquals(120.0, LoopFinder.bpm(0, SR * 2, 4, SR), 1e-9)
    }
}
