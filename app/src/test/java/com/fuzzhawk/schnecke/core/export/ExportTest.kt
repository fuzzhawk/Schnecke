package com.fuzzhawk.schnecke.core.export

import com.fuzzhawk.schnecke.core.audio.LoopRenderer
import com.fuzzhawk.schnecke.core.audio.OfflineRenderer
import com.fuzzhawk.schnecke.core.audio.SnapshotBuilder
import com.fuzzhawk.schnecke.core.model.Layer
import com.fuzzhawk.schnecke.core.model.LayerMode
import com.fuzzhawk.schnecke.core.model.LayerState
import com.fuzzhawk.schnecke.core.model.Note
import com.fuzzhawk.schnecke.core.model.Project
import com.fuzzhawk.schnecke.core.model.TakeInfo
import com.fuzzhawk.schnecke.core.model.Voice
import com.fuzzhawk.schnecke.core.synth.DrumSynth
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs

class ExportTest {
    private val sr = 48000

    private fun project(bpm: Double = 100.0, rawBeatbox: Boolean = false): Project {
        val notes = listOf(
            Note(1, Voice.KICK, 0.0, 120), Note(2, Voice.SNARE, 1.02, 100), Note(3, Voice.KICK, 2.5, 110),
            Note(4, Voice.SNARE, 3.0, 127), Note(5, Voice.HIHAT, 0.5, 80), Note(6, Voice.OPEN_HAT, 3.5, 90),
            Note(7, Voice.PERC, 6.75, 70), Note(8, Voice.KICK, 4.0, 120), Note(9, Voice.SNARE, 7.0, 100),
        )
        val layers = mapOf(
            Layer.HANDS to LayerState(LayerMode.REPLACE),
            Layer.BEATBOX to LayerState(
                if (rawBeatbox) LayerMode.RAW else LayerMode.REPLACE,
                take = TakeInfo(sr, sr * 6, sr, bpm, 8.0),
            ),
        )
        return Project("p1", "Test & <Loop>", 0, 0, bpm = bpm, bars = 2, notes = notes, layers = layers)
    }

    private fun sounds() = mapOf(
        Voice.KICK to DrumSynth.render("kick_punch", sr),
        Voice.SNARE to DrumSynth.render("snare_tight", sr),
        Voice.HIHAT to DrumSynth.render("hat_closed", sr),
        Voice.OPEN_HAT to DrumSynth.render("hat_open", sr),
        Voice.PERC to DrumSynth.render("rim", sr),
    )

    private fun rawLoop(p: Project): FloatArray {
        val n = p.loopFrames(sr).toInt()
        return FloatArray(n) { if (it % (sr / 2) < 200) 0.5f else 0f }
    }

    @Test
    fun everyLibrarySampleRendersCleanly() {
        for (s in DrumSynth.library) {
            val x = DrumSynth.render(s.id, sr)
            assertTrue(s.id, x.size > sr / 50)
            val peak = x.maxOf { abs(it) }
            assertTrue("${s.id} peak $peak", peak in 0.4f..0.95f)
            assertTrue("${s.id} ends silent", abs(x.last()) < 1e-3f)
            assertTrue(s.id, x.none { it.isNaN() })
        }
    }

    @Test
    fun rendererPlaysNotesAtQuantizedFrames() {
        val p = project().copy(notes = listOf(Note(1, Voice.KICK, 1.03, 127)))
        val snap = SnapshotBuilder.build(p, sr, sounds(), emptyMap())
        val out = OfflineRenderer.render(snap, p.loopFrames(sr))
        val firstLoud = (0 until out.size / 2).first { abs(out[2 * it]) > 0.01f }
        val expected = (sr * 0.6).toInt() // beat 1 at 100 bpm, quantized from 1.03
        assertTrue("kick at $firstLoud, expected ~$expected", abs(firstLoud - expected) < 48)
    }

    @Test
    fun seamlessLoopWrapsTailsAndHasExactLength() {
        val p = project().copy(notes = listOf(Note(1, Voice.OPEN_HAT, 7.75, 127)))
        val snap = SnapshotBuilder.build(p, sr, sounds(), emptyMap())
        val loop = OfflineRenderer.renderSeamlessLoop(snap)
        assertEquals(p.loopFrames(sr) * 2, loop.size.toLong())
        // The open hat starts 1/4 beat before the loop end and must keep ringing at the start.
        assertTrue(abs(loop[200]) > 1e-3f || abs(loop[201]) > 1e-3f)
    }

    @Test
    fun liveRendererCountsInWithClicksThenLoops() {
        val p = project()
        val r = LoopRenderer(sr)
        r.snapshot = SnapshotBuilder.build(p, sr, sounds(), emptyMap())
        r.start(4)
        val buf = FloatArray(2 * 512)
        r.render(buf, 512)
        assertTrue("count-in click", buf.any { abs(it) > 0.05f })
        assertEquals(512L + (4 * p.framesPerBeat(sr)).toLong(), r.loopStartRenderedFrame + 512)
        assertTrue(r.transportFrame < 0)
    }

    @Test
    fun midiFileHasTempoTrackAndDrumNotes() {
        val p = project()
        val bytes = MidiWriter.write(p, repeats = 2)
        assertEquals("MThd", String(bytes, 0, 4))
        val tracks = (bytes[10].toInt() shl 8) or (bytes[11].toInt() and 0xff)
        assertEquals(3, tracks)
        val ppq = (bytes[12].toInt() and 0xff shl 8) or (bytes[13].toInt() and 0xff)
        assertEquals(480, ppq)
        val notes = parseNoteOns(bytes)
        assertEquals(p.notes.size * 2, notes.size)
        // snare detected at 1.02 beats quantizes to beat 1 = tick 480, on channel 10, note 38
        assertTrue(notes.contains(Triple(480L, 38, 100)))
        assertTrue(notes.all { it.second in setOf(36, 38, 42, 46, 37) })
        // second repeat starts 8 beats later
        assertTrue(notes.contains(Triple(480L + 8 * 480, 38, 100)))
        assertArrayEquals(byteArrayOf(0x81.toByte(), 0x00), MidiWriter.vlq(128))
    }

    private fun parseNoteOns(b: ByteArray): List<Triple<Long, Int, Int>> {
        val out = ArrayList<Triple<Long, Int, Int>>()
        var i = 14
        while (i < b.size) {
            val len = ((b[i + 4].toInt() and 0xff) shl 24) or ((b[i + 5].toInt() and 0xff) shl 16) or
                ((b[i + 6].toInt() and 0xff) shl 8) or (b[i + 7].toInt() and 0xff)
            var j = i + 8
            val end = j + len
            var tick = 0L
            var running = 0
            while (j < end) {
                var delta = 0L
                while (true) { val c = b[j++].toInt() and 0xff; delta = (delta shl 7) or (c and 0x7f).toLong(); if (c and 0x80 == 0) break }
                tick += delta
                var status = b[j].toInt() and 0xff
                if (status < 0x80) status = running else j++
                running = status
                when {
                    status == 0xFF -> { j++; var l = 0; while (true) { val c = b[j++].toInt() and 0xff; l = (l shl 7) or (c and 0x7f); if (c and 0x80 == 0) break }; j += l }
                    status and 0xF0 == 0x90 -> { assertEquals(0x99, status); out.add(Triple(tick, b[j].toInt(), b[j + 1].toInt())); j += 2 }
                    else -> j += 2
                }
            }
            i = end
        }
        return out
    }

    @Test
    fun wavRoundTripsAndAudioExportHasRequestedLength() {
        val p = project()
        val inputs = ExportInputs(p, sr, sounds(), emptyMap())
        val bos = ByteArrayOutputStream()
        ProjectExporter.audio(inputs, seconds = 10.0, wholeLoops = false, fadeOut = true, out = bos)
        val decoded = Wav.decode(bos.toByteArray())
        assertEquals(sr, decoded.sampleRate)
        assertEquals(2, decoded.channels)
        assertEquals(sr * 10, decoded.mono.size)
        assertTrue("fades to silence", abs(decoded.mono.last()) < 1e-3f)
        assertTrue(decoded.mono.any { abs(it) > 0.2f })
        val rounded = ProjectExporter.audioFrames(p, sr, 10.0, wholeLoops = true)
        assertEquals(0L, rounded % p.loopFrames(sr))
        assertTrue(rounded >= sr * 10L)
        val resampled = Wav.resample(FloatArray(441) { 1f }, 44100, 48000)
        assertEquals(480, resampled.size)
    }

    @Test
    fun abletonProjectZipIsCompleteAndAlsIsWellFormed() {
        val p = project(rawBeatbox = true)
        val inputs = ExportInputs(p, sr, sounds(), mapOf(Layer.BEATBOX to rawLoop(p)))
        val bos = ByteArrayOutputStream()
        ProjectExporter.ableton(inputs, bos)
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bos.toByteArray())).use { z ->
            while (true) { val e = z.nextEntry ?: break; entries[e.name] = z.readBytes() }
        }
        val root = "Test _ _Loop_ Project/"
        assertTrue(entries.keys.toString(), entries.containsKey(root + "Ableton Project Info/"))
        val als = entries.getValue(root + "Test _ _Loop_.als")
        val xml = GZIPInputStream(ByteArrayInputStream(als)).readBytes().toString(Charsets.UTF_8)
        File("build/test-output").mkdirs()
        File("build/test-output/sample.als").writeBytes(als)
        File("build/test-output/sample.xml").writeText(xml)

        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray()))
        val ableton = doc.documentElement
        assertEquals("10.0_370", ableton.getAttribute("MinorVersion"))
        val tracks = (ableton.getElementsByTagName("Tracks").item(0) as Element).childNodes
        val names = (0 until tracks.length).mapNotNull { tracks.item(it) as? Element }.map { it.tagName }
        // Kick and Snare stems, the raw beatbox loop, and two MIDI tracks.
        assertEquals(listOf("MidiTrack", "MidiTrack", "AudioTrack", "AudioTrack", "AudioTrack"), names)

        // Set-wide ids are unique and below NextPointeeId.
        val ids = ArrayList<Int>()
        val all = doc.getElementsByTagName("*")
        for (i in 0 until all.length) {
            val e = all.item(i) as Element
            if (Regex("^(AutomationTarget|\\w*ModulationTarget|ControllerTargets\\.\\d+)$").matches(e.tagName)) {
                ids.add(e.getAttribute("Id").toInt())
            }
        }
        assertEquals(ids.size, ids.toSet().size)
        val next = (ableton.getElementsByTagName("NextPointeeId").item(0) as Element).getAttribute("Value").toInt()
        assertTrue(next > ids.max())

        // Every clip slot list matches the number of scenes.
        val scenes = (ableton.getElementsByTagName("SceneNames").item(0) as Element).getElementsByTagName("Scene").length
        val slotLists = doc.getElementsByTagName("ClipSlotList")
        for (i in 0 until slotLists.length) {
            val list = slotLists.item(i) as Element
            val n = (0 until list.childNodes.length).count { (list.childNodes.item(it) as? Element)?.tagName == "ClipSlot" }
            assertTrue(n == scenes || n == 0)
        }

        // Every referenced sample is in the zip.
        val refs = doc.getElementsByTagName("FileRef")
        assertTrue(refs.length >= 3)
        for (i in 0 until refs.length) {
            val ref = refs.item(i) as Element
            val dirs = (ref.getElementsByTagName("RelativePath").item(0) as Element).getElementsByTagName("RelativePathElement")
            val path = (0 until dirs.length).joinToString("/") { (dirs.item(it) as Element).getAttribute("Dir") }
            val file = (ref.getElementsByTagName("Name").item(0) as Element).getAttribute("Value")
            assertTrue("$path/$file", entries.containsKey("$root$path/$file"))
        }
        // Tempo and time signature landed in the master track.
        assertTrue(xml.contains("<Manual Value=\"100\" />"))
        assertTrue(xml.contains("<Manual Value=\"201\" />"))
        assertTrue(entries.keys.any { it.endsWith("One-shots/Test _ _Loop_ - Kick.wav") })
        assertTrue(entries.containsKey(root + "Test _ _Loop_.mid"))
    }
}
