package com.fuzzhawk.schnecke.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class TimingTest {
    private fun project(vararg notes: Note) =
        Project("p", "t", 0, 0, bpm = 120.0, bars = 2, stepsPerBeat = 4, notes = notes.toList())

    @Test
    fun hitsKeepTheirOffsetFromTheGrid() {
        val p = project(Note(1, Voice.SNARE, 1.04, 100))
        val n = p.notes[0]
        assertEquals(4, p.stepOf(n))
        assertEquals(0.04, p.offsetOf(n), 1e-9)
        assertEquals(1.04, p.playedBeat(n), 1e-9)
    }

    @Test
    fun swingMovesTheGridAndTheOffsetRidesOnTop() {
        val p = project(Note(1, Voice.HIHAT, 0.27, 100)).copy(swingPercent = 66f)
        val n = p.notes[0]
        // Step 1 (the second 16th) swings from 0.25 to 0.25 + 0.32 * 0.25 = 0.33; offset +0.02.
        assertEquals(0.25 + 0.32 * 0.25 + 0.02, p.playedBeat(n), 1e-9)
    }

    @Test
    fun snappingRemovesTheOffsetFullyOrPartly() {
        val p = project(Note(1, Voice.KICK, 2.46, 100))
        assertEquals(2.5, p.snapped(p.notes[0]).beat, 1e-9)
        assertEquals(2.48, p.snapped(p.notes[0], 0.5).beat, 1e-9)
        assertEquals(0.0, p.offsetOf(p.snapped(p.notes[0])), 1e-9)
    }

    @Test
    fun offsetsStayInsideTheHitsCell() {
        val p = project(Note(1, Voice.KICK, 1.0, 100))
        val late = p.withOffset(p.notes[0], 0.05)
        assertEquals(1.05, late.beat, 1e-9)
        val tooFar = p.withOffset(p.notes[0], 0.3)
        assertEquals(4, p.stepOf(tooFar))
        assertEquals(Timing.MAX_OFFSET_STEPS * 0.25, p.offsetOf(tooFar), 1e-9)
    }

    @Test
    fun earlyDownbeatWrapsAroundTheLoop() {
        // Played 20 ms of a beat before the loop's first beat: stored at the loop's end.
        val p = project(Note(1, Voice.KICK, 7.98, 100))
        val n = p.notes[0]
        assertEquals(0, p.stepOf(n))
        assertEquals(-0.02, p.offsetOf(n), 1e-9)
        assertEquals(7.98, p.playedBeat(n), 1e-9)
        assertEquals(0.0, p.snapped(n).beat, 1e-9)
        assertEquals(7.95, p.withOffset(n, -0.05).beat, 1e-9)
    }

    @Test
    fun oldProjectsWithGlobalQuantizeAreMigrated() {
        val old = project(Note(1, Voice.SNARE, 1.04, 100), Note(2, Voice.KICK, 2.0, 100)).copy(quantize = 1f)
        val m = old.migrated()
        assertEquals(0f, m.quantize)
        assertEquals(1.0, m.notes[0].beat, 1e-9)
        assertEquals(2.0, m.notes[1].beat, 1e-9)
        val half = project(Note(1, Voice.SNARE, 1.04, 100)).copy(quantize = 0.5f).migrated()
        assertEquals(1.02, half.notes[0].beat, 1e-9)
    }
}
