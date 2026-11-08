package com.fuzzhawk.schnecke.core.export

import com.fuzzhawk.schnecke.core.audio.OfflineRenderer
import com.fuzzhawk.schnecke.core.audio.SnapshotBuilder
import com.fuzzhawk.schnecke.core.model.Layer
import com.fuzzhawk.schnecke.core.model.LayerMode
import com.fuzzhawk.schnecke.core.model.Project
import com.fuzzhawk.schnecke.core.model.Voice
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToLong

/** Everything an export needs: the project plus the audio it plays. */
class ExportInputs(
    val project: Project,
    val sampleRate: Int,
    /** One-shot sample per voice (null when the voice has no sound). */
    val sounds: Map<Voice, FloatArray?>,
    /** Seamless raw loop per layer that has a take. */
    val raws: Map<Layer, FloatArray?>,
)

object ProjectExporter {

    fun safeName(name: String): String =
        name.trim().replace(Regex("[^A-Za-z0-9 _.-]"), "_").replace(Regex("\\s+"), " ").take(60).ifBlank { "Schnecke loop" }

    fun midi(inputs: ExportInputs, repeats: Int = 1): ByteArray = MidiWriter.write(inputs.project, repeats)

    /** Frames for an audio export of [seconds], optionally rounded up to whole loops. */
    fun audioFrames(project: Project, sampleRate: Int, seconds: Double, wholeLoops: Boolean): Long {
        val loop = project.loopFrames(sampleRate)
        val raw = (seconds * sampleRate).roundToLong().coerceAtLeast(loop)
        return if (wholeLoops) ceil(raw / loop.toDouble()).toLong() * loop else raw
    }

    /** The full mix repeated for [seconds] as a 16-bit stereo WAV. */
    fun audio(inputs: ExportInputs, seconds: Double, wholeLoops: Boolean, fadeOut: Boolean, out: OutputStream) {
        val sr = inputs.sampleRate
        val snap = SnapshotBuilder.build(inputs.project, sr, inputs.sounds, inputs.raws)
        val frames = audioFrames(inputs.project, sr, seconds, wholeLoops)
        val fade = if (fadeOut) min((2.0 * sr).toLong(), frames / 4) else 0L
        val mix = OfflineRenderer.render(snap, frames, fade)
        Wav.write16(out, mix, 2, sr)
    }

    /**
     * An Ableton Live 10 project folder, zipped: the set, a seamless loop stem per sounding voice
     * (and per raw layer), and every one-shot so you can build your own Drum Rack.
     */
    fun ableton(inputs: ExportInputs, out: OutputStream) {
        val p = inputs.project
        val sr = inputs.sampleRate
        val name = safeName(p.name)
        val root = "$name Project/"
        val zip = ZipOutputStream(out)

        fun put(path: String, bytes: ByteArray) {
            zip.putNextEntry(ZipEntry(root + path))
            zip.write(bytes)
            zip.closeEntry()
        }
        zip.putNextEntry(ZipEntry(root + "Ableton Project Info/"))
        zip.closeEntry()

        val audioTracks = ArrayList<AlsWriter.AudioTrack>()
        val stemDirs = listOf("Samples", "Imported", "Loops")
        fun addStem(label: String, color: Int, stereo: FloatArray, beats: Double, bpm: Double) {
            val file = "$name - $label loop.wav"
            val wav = Wav.encode16(stereo, 2, sr)
            put(stemDirs.joinToString("/") + "/" + file, wav)
            audioTracks.add(
                AlsWriter.AudioTrack(
                    name = label,
                    color = color,
                    sample = AlsWriter.SampleFile(stemDirs, file, (stereo.size / 2).toLong(), sr, wav.size.toLong()),
                    sampleBeats = beats,
                    sampleBpm = bpm,
                ),
            )
        }

        for (v in Voice.entries) {
            val layer = p.layer(v.layer)
            if (layer.mode != LayerMode.REPLACE || layer.muted || p.voice(v).muted) continue
            if (p.notes.none { it.voice == v } || inputs.sounds[v] == null) continue
            val snap = SnapshotBuilder.build(p, sr, inputs.sounds, inputs.raws, onlyVoice = v)
            addStem(v.label, VOICE_COLORS.getValue(v), OfflineRenderer.renderSeamlessLoop(snap), p.loopBeats, p.bpm)
        }
        for (l in Layer.entries) {
            val st = p.layer(l)
            if (st.mode != LayerMode.RAW || st.muted || st.take == null || inputs.raws[l] == null) continue
            val snap = SnapshotBuilder.build(p, sr, inputs.sounds, inputs.raws, onlyRaw = l)
            addStem("${l.label} raw", if (l == Layer.HANDS) 1 else 26, OfflineRenderer.renderSeamlessLoop(snap), p.loopBeats, p.bpm)
        }

        val stepBeats = 1.0 / p.stepsPerBeat
        val midiTracks = Layer.entries.mapNotNull { l ->
            val notes = p.notes.filter { it.voice.layer == l && !p.voice(it.voice).muted }
            if (notes.isEmpty()) null
            else AlsWriter.MidiTrack(
                name = "${l.label} MIDI",
                color = if (l == Layer.HANDS) 2 else 14,
                notes = notes.map { n ->
                    AlsWriter.Note(p.playedBeat(n), min(stepBeats, 0.25), p.voice(n.voice).midiNote, n.velocity)
                },
            )
        }

        for (v in Voice.entries) {
            val s = inputs.sounds[v] ?: continue
            put("Samples/Imported/One-shots/$name - ${v.label}.wav", Wav.encode16(Wav.monoToStereo(s), 2, sr))
        }
        put("$name.mid", MidiWriter.write(p))

        val set = AlsWriter.LiveSet(
            bpm = p.bpm,
            beatsPerBar = p.beatsPerBar,
            loopBeats = p.loopBeats,
            midiTracks = midiTracks,
            audioTracks = audioTracks,
            arrangementRepeats = (16 / p.bars).coerceIn(1, 16),
        )
        put("$name.als", AlsWriter.writeAls(set))
        zip.finish()
    }

    /** Live 10 colour palette indices for each voice's stem. */
    val VOICE_COLORS = mapOf(
        Voice.KICK to 1,
        Voice.SNARE to 4,
        Voice.HIHAT to 13,
        Voice.OPEN_HAT to 15,
        Voice.PERC to 19,
    )
}
