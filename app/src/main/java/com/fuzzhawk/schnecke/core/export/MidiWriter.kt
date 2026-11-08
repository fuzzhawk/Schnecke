package com.fuzzhawk.schnecke.core.export

import com.fuzzhawk.schnecke.core.model.Layer
import com.fuzzhawk.schnecke.core.model.Project
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Standard MIDI File (type 1) export: a tempo track plus one drum track (channel 10) per layer. */
object MidiWriter {
    const val PPQ = 480
    private const val DRUM_CHANNEL = 9

    fun write(project: Project, repeats: Int = 1): ByteArray {
        val tracks = ArrayList<ByteArray>()
        tracks.add(tempoTrack(project))
        for (layer in Layer.entries) {
            val notes = project.notes.filter { it.voice.layer == layer && !project.voice(it.voice).muted }
            if (notes.isEmpty()) continue
            tracks.add(drumTrack(project, layer, notes, repeats.coerceAtLeast(1)))
        }
        val out = ByteArrayOutputStream()
        out.write("MThd".toByteArray(Charsets.US_ASCII))
        out.write(int32(6))
        out.write(int16(1))
        out.write(int16(tracks.size))
        out.write(int16(PPQ))
        for (t in tracks) {
            out.write("MTrk".toByteArray(Charsets.US_ASCII))
            out.write(int32(t.size))
            out.write(t)
        }
        return out.toByteArray()
    }

    private fun tempoTrack(project: Project): ByteArray {
        val e = Events()
        e.meta(0, 0x03, project.name.toByteArray(Charsets.UTF_8))
        val usPerBeat = (60_000_000.0 / project.bpm).roundToInt()
        e.meta(0, 0x51, byteArrayOf((usPerBeat shr 16).toByte(), (usPerBeat shr 8).toByte(), usPerBeat.toByte()))
        e.meta(0, 0x58, byteArrayOf(project.beatsPerBar.toByte(), 2, 24, 8))
        e.meta(0, 0x2F, ByteArray(0))
        return e.bytes()
    }

    private fun drumTrack(project: Project, layer: Layer, notes: List<com.fuzzhawk.schnecke.core.model.Note>, repeats: Int): ByteArray {
        data class Ev(val tick: Long, val on: Boolean, val key: Int, val vel: Int)
        val loopTicks = (project.loopBeats * PPQ).roundToLong()
        val len = (PPQ / project.stepsPerBeat).coerceAtMost(PPQ / 4).toLong().coerceAtLeast(1)
        val evs = ArrayList<Ev>()
        for (r in 0 until repeats) {
            for (n in notes) {
                val t = r * loopTicks + (project.playedBeat(n) * PPQ).roundToLong().coerceIn(0, loopTicks - 1)
                val key = project.voice(n.voice).midiNote.coerceIn(0, 127)
                evs.add(Ev(t, true, key, n.velocity.coerceIn(1, 127)))
                evs.add(Ev(t + len, false, key, 0))
            }
        }
        // Note-offs first at equal ticks, so a re-struck key is not cut by its own previous note-off.
        evs.sortWith(compareBy<Ev> { it.tick }.thenBy { it.on })
        val e = Events()
        e.meta(0, 0x03, "${layer.label} drums".toByteArray(Charsets.UTF_8))
        var last = 0L
        for (ev in evs) {
            val delta = ev.tick - last
            last = ev.tick
            val status = (if (ev.on) 0x90 else 0x80) or DRUM_CHANNEL
            e.raw(delta, byteArrayOf(status.toByte(), ev.key.toByte(), (if (ev.on) ev.vel else 64).toByte()))
        }
        val end = (repeats * loopTicks).coerceAtLeast(last)
        e.meta(end - last, 0x2F, ByteArray(0))
        return e.bytes()
    }

    private class Events {
        private val out = ByteArrayOutputStream()
        fun raw(delta: Long, data: ByteArray) { out.write(vlq(delta)); out.write(data) }
        fun meta(delta: Long, type: Int, data: ByteArray) {
            out.write(vlq(delta)); out.write(0xFF); out.write(type); out.write(vlq(data.size.toLong())); out.write(data)
        }
        fun bytes(): ByteArray = out.toByteArray()
    }

    fun vlq(value: Long): ByteArray {
        require(value >= 0)
        var v = value
        val bytes = ArrayList<Byte>()
        bytes.add((v and 0x7F).toByte())
        v = v shr 7
        while (v > 0) {
            bytes.add(((v and 0x7F) or 0x80).toByte())
            v = v shr 7
        }
        return bytes.reversed().toByteArray()
    }

    private fun int32(v: Int) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
    private fun int16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())
}
