package com.fuzzhawk.schnecke.core.export

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.log2
import kotlin.math.roundToInt

/**
 * Writes an Ableton Live 10 set (.als: gzipped XML, schema `10.0_370`, as saved by Live 10.0.6).
 *
 * Live 10.0.6 and every later version (10.1, 11, 12) open this format. The track, master and set
 * structure comes from templates cut from a real Live 10.0.6 set (see tools/als/make_templates.py);
 * clips are written here following the same structure. Samples are referenced relative to the
 * project folder (`Samples/...`), so the set must sit in a project folder next to them.
 */
object AlsWriter {

    data class Note(val time: Double, val duration: Double, val key: Int, val velocity: Int)

    data class MidiTrack(val name: String, val color: Int, val notes: List<Note>)

    data class SampleFile(
        /** Folders below the project root, e.g. ["Samples", "Imported"]. */
        val dirs: List<String>,
        val fileName: String,
        val frames: Long,
        val sampleRate: Int,
        val fileSize: Long,
    )

    data class AudioTrack(
        val name: String,
        val color: Int,
        val sample: SampleFile,
        /** Length of the sample in beats at [sampleBpm] (it is warped to the set tempo). */
        val sampleBeats: Double,
        val sampleBpm: Double,
        val volume: Double = 1.0,
        val pan: Double = 0.0,
    )

    data class LiveSet(
        val bpm: Double,
        val beatsPerBar: Int,
        val loopBeats: Double,
        val midiTracks: List<MidiTrack>,
        val audioTracks: List<AudioTrack>,
        /** How many loop repetitions to lay out in the arrangement. */
        val arrangementRepeats: Int = 8,
        val scenes: Int = 8,
        val modifiedEpochSeconds: Long = System.currentTimeMillis() / 1000,
    )

    private fun template(name: String): String =
        requireNotNull(AlsWriter::class.java.getResourceAsStream("/als/$name")) { "missing template $name" }
            .use { it.readBytes().toString(Charsets.UTF_8) }

    fun gzip(xml: String): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(xml.toByteArray(Charsets.UTF_8)) }
        return bos.toByteArray()
    }

    fun writeAls(set: LiveSet): ByteArray = gzip(writeXml(set))

    fun writeXml(set: LiveSet): String {
        val midiTemplate = template("live10_midi_track.xml")
        val audioTemplate = template("live10_audio_track.xml")
        val arrangementBeats = set.loopBeats * set.arrangementRepeats.coerceAtLeast(1)
        var trackId = 8
        val tracks = StringBuilder()
        for (t in set.midiTracks) {
            val session = midiClip(set, t, time = 0.0, end = set.loopBeats)
            val arranged = midiClip(set, t, time = 0.0, end = arrangementBeats)
            tracks.append(fillTrack(midiTemplate, trackId++, t.name, t.color, 1.0, 0.0, session, arranged, set.scenes))
        }
        for (t in set.audioTracks) {
            val session = audioClip(set, t, time = 0.0, end = t.sampleBeats)
            val arrangedEnd = (arrangementBeats / t.sampleBeats).roundToInt().coerceAtLeast(1) * t.sampleBeats
            val arranged = audioClip(set, t, time = 0.0, end = arrangedEnd)
            tracks.append(fillTrack(audioTemplate, trackId++, t.name, t.color, t.volume, t.pan, session, arranged, set.scenes))
        }
        val scenes = StringBuilder()
        for (i in 0 until set.scenes) {
            scenes.append(
                """
                |			<Scene Id="$i" Value="">
                |				<Annotation Value="" />
                |				<ColorIndex Value="0" />
                |				<LomId Value="0" />
                |				<ClipSlotsListWrapper LomId="0" />
                |			</Scene>""".trimMargin(),
            )
        }
        var doc = template("live10_set.xml")
            .replace("{{TRACKS}}", "\n" + tracks.toString() + "\t\t")
            .replace("{{SCENES}}", scenes.toString() + "\n\t\t")
            .replace("{{TEMPO}}", num(set.bpm))
            .replace("{{TIME_SIGNATURE}}", timeSignatureValue(set.beatsPerBar, 4).toString())
            .replace("{{LOOP_LENGTH}}", num(arrangementBeats))
        // Every automation/modulation target in the set needs its own id; NextPointeeId must exceed them all.
        val sb = StringBuilder(doc.length + 4096)
        var next = 1
        var idx = 0
        while (true) {
            val at = doc.indexOf("{{PID}}", idx)
            if (at < 0) { sb.append(doc, idx, doc.length); break }
            sb.append(doc, idx, at).append(next++)
            idx = at + "{{PID}}".length
        }
        doc = sb.toString().replace("{{NEXT_POINTEE_ID}}", next.toString())
        check(!doc.contains("{{")) { "unfilled template placeholder" }
        return doc
    }

    /** Live encodes a time signature as (numerator - 1) + 99 * log2(denominator); 4/4 is 201. */
    fun timeSignatureValue(numerator: Int, denominator: Int): Int =
        (numerator - 1) + 99 * log2(denominator.toDouble()).roundToInt()

    private fun fillTrack(
        template: String, id: Int, name: String, color: Int, volume: Double, pan: Double,
        sessionClip: String, arrangementClip: String, scenes: Int,
    ): String {
        val slots = StringBuilder()
        val freeze = StringBuilder()
        for (i in 0 until scenes) {
            slots.append(clipSlot(i, if (i == 0) sessionClip else null))
            freeze.append(clipSlot(i, null))
        }
        return template
            .replace("{{TRACK_ID}}", id.toString())
            .replace("{{NAME}}", xml(name))
            .replace("{{COLOR}}", color.toString())
            .replace("{{VOLUME}}", num(volume))
            .replace("{{PAN}}", num(pan.coerceIn(-1.0, 1.0)))
            .replace("{{CLIP_SLOTS}}", slots.toString() + "\n\t\t\t\t\t\t")
            .replace("{{FREEZE_CLIP_SLOTS}}", freeze.toString() + "\n\t\t\t\t\t\t")
            .replace("{{ARRANGER_CLIPS}}", arrangementClip + "\n\t\t\t\t\t\t\t\t")
    }

    private fun clipSlot(i: Int, clip: String?): String {
        val value = if (clip == null) "<Value />" else "<Value>$clip\n\t\t\t\t\t\t\t\t\t</Value>"
        return """
            |
            |							<ClipSlot Id="$i">
            |								<LomId Value="0" />
            |								<ClipSlot>
            |									$value
            |								</ClipSlot>
            |								<HasStop Value="true" />
            |								<NeedRefreeze Value="true" />
            |							</ClipSlot>""".trimMargin().trimEnd()
    }

    private fun clipHeader(id: Int, time: Double, end: Double, loopEnd: Double, name: String, color: Int, beatsPerBar: Int, warpMarkers: String) = """
        |<LomId Value="0" />
        |<LomIdView Value="0" />
        |<WarpMarkers>
        |$warpMarkers
        |</WarpMarkers>
        |<CurrentStart Value="${num(time)}" />
        |<CurrentEnd Value="${num(time + end)}" />
        |<Loop>
        |	<LoopStart Value="0" />
        |	<LoopEnd Value="${num(loopEnd)}" />
        |	<StartRelative Value="0" />
        |	<LoopOn Value="true" />
        |	<OutMarker Value="${num(loopEnd)}" />
        |	<HiddenLoopStart Value="0" />
        |	<HiddenLoopEnd Value="${num(loopEnd)}" />
        |</Loop>
        |<Name Value="${xml(name)}" />
        |<Annotation Value="" />
        |<ColorIndex Value="$color" />
        |<LaunchMode Value="0" />
        |<LaunchQuantisation Value="0" />
        |<TimeSignature>
        |	<TimeSignatures>
        |		<RemoteableTimeSignature Id="0">
        |			<Numerator Value="$beatsPerBar" />
        |			<Denominator Value="4" />
        |			<Time Value="0" />
        |		</RemoteableTimeSignature>
        |	</TimeSignatures>
        |</TimeSignature>
        |<Envelopes>
        |	<Envelopes />
        |</Envelopes>
        |<ScrollerTimePreserver>
        |	<LeftTime Value="0" />
        |	<RightTime Value="${num(loopEnd)}" />
        |</ScrollerTimePreserver>
        |<TimeSelection>
        |	<AnchorTime Value="0" />
        |	<OtherTime Value="0" />
        |</TimeSelection>
        |<Legato Value="false" />
        |<Ram Value="false" />
        |<GrooveSettings>
        |	<GrooveId Value="-1" />
        |</GrooveSettings>
        |<Disabled Value="false" />
        |<VelocityAmount Value="0" />
        |<FollowTime Value="4" />
        |<FollowActionA Value="0" />
        |<FollowActionB Value="0" />
        |<FollowChanceA Value="1" />
        |<FollowChanceB Value="0" />
        |<Grid>
        |	<FixedNumerator Value="1" />
        |	<FixedDenominator Value="16" />
        |	<GridIntervalPixel Value="20" />
        |	<Ntoles Value="2" />
        |	<SnapToGrid Value="true" />
        |	<Fixed Value="false" />
        |</Grid>
        |<FreezeStart Value="0" />
        |<FreezeEnd Value="0" />
        |<IsWarped Value="true" />""".trimMargin()

    private fun midiClip(set: LiveSet, t: MidiTrack, time: Double, end: Double): String {
        val secPerBeat = 60.0 / set.bpm
        val markers = """
            |	<WarpMarker Id="0" SecTime="0" BeatTime="0" />
            |	<WarpMarker Id="1" SecTime="${num(0.03125 * secPerBeat)}" BeatTime="0.03125" />""".trimMargin()
        val keyTracks = StringBuilder()
        t.notes.groupBy { it.key }.toSortedMap().entries.forEachIndexed { i, (key, notes) ->
            keyTracks.append("\n\t\t<KeyTrack Id=\"$i\">\n\t\t\t<Notes>")
            for (n in notes.sortedBy { it.time }) {
                keyTracks.append(
                    "\n\t\t\t\t<MidiNoteEvent Time=\"${num(n.time)}\" Duration=\"${num(n.duration)}\" " +
                        "Velocity=\"${n.velocity.coerceIn(1, 127)}\" OffVelocity=\"64\" IsEnabled=\"true\" />",
                )
            }
            keyTracks.append("\n\t\t\t</Notes>\n\t\t\t<MidiKey Value=\"$key\" />\n\t\t</KeyTrack>")
        }
        val body = clipHeader(0, time, end, set.loopBeats, t.name, t.color, set.beatsPerBar, markers) + """
            |
            |<Notes>
            |	<KeyTracks>$keyTracks
            |	</KeyTracks>
            |</Notes>
            |<BankSelectCoarse Value="-1" />
            |<BankSelectFine Value="-1" />
            |<ProgramChange Value="-1" />
            |<NoteEditorFoldInZoom Value="127" />
            |<NoteEditorFoldInScroll Value="0" />
            |<NoteEditorFoldOutZoom Value="-1" />
            |<NoteEditorFoldOutScroll Value="-1" />""".trimMargin()
        return wrap("MidiClip", time, body)
    }

    private fun audioClip(set: LiveSet, t: AudioTrack, time: Double, end: Double): String {
        val s = t.sample
        val durSec = s.frames.toDouble() / s.sampleRate
        val secPerBeat = 60.0 / t.sampleBpm
        val markers = """
            |	<WarpMarker Id="0" SecTime="0" BeatTime="0" />
            |	<WarpMarker Id="1" SecTime="${num(durSec)}" BeatTime="${num(t.sampleBeats)}" />
            |	<WarpMarker Id="2" SecTime="${num(durSec + 0.03125 * secPerBeat)}" BeatTime="${num(t.sampleBeats + 0.03125)}" />""".trimMargin()
        val rel = s.dirs.mapIndexed { i, d -> "\n\t\t\t<RelativePathElement Id=\"$i\" Dir=\"${xml(d)}\" />" }.joinToString("")
        val body = clipHeader(0, time, end, t.sampleBeats, t.name, t.color, set.beatsPerBar, markers) + """
            |
            |<SampleRef>
            |	<FileRef>
            |		<HasRelativePath Value="true" />
            |		<RelativePathType Value="3" />
            |		<RelativePath>$rel
            |		</RelativePath>
            |		<Name Value="${xml(s.fileName)}" />
            |		<Type Value="1" />
            |		<Data />
            |		<RefersToFolder Value="false" />
            |		<SearchHint>
            |			<PathHint />
            |			<FileSize Value="${s.fileSize}" />
            |			<Crc Value="0" />
            |			<MaxCrcSize Value="0" />
            |			<HasExtendedInfo Value="false" />
            |		</SearchHint>
            |		<LivePackName Value="" />
            |		<LivePackId Value="" />
            |	</FileRef>
            |	<LastModDate Value="${set.modifiedEpochSeconds}" />
            |	<SourceContext />
            |	<SampleUsageHint Value="0" />
            |	<DefaultDuration Value="${s.frames}" />
            |	<DefaultSampleRate Value="${s.sampleRate}" />
            |</SampleRef>
            |<Onsets>
            |	<UserOnsets />
            |	<HasUserOnsets Value="false" />
            |</Onsets>
            |<WarpMode Value="0" />
            |<GranularityTones Value="30" />
            |<GranularityTexture Value="65" />
            |<FluctuationTexture Value="25" />
            |<TransientResolution Value="6" />
            |<TransientLoopMode Value="2" />
            |<TransientEnvelope Value="100" />
            |<ComplexProFormants Value="100" />
            |<ComplexProEnvelope Value="128" />
            |<Sync Value="true" />
            |<HiQ Value="true" />
            |<Fade Value="false" />
            |<Fades>
            |	<FadeInLength Value="0" />
            |	<FadeOutLength Value="0" />
            |	<ClipFadesAreInitialized Value="true" />
            |	<CrossfadeInState Value="0" />
            |	<FadeInCurveSkew Value="0" />
            |	<FadeInCurveSlope Value="0" />
            |	<FadeOutCurveSkew Value="0" />
            |	<FadeOutCurveSlope Value="0" />
            |	<IsDefaultFadeIn Value="true" />
            |	<IsDefaultFadeOut Value="true" />
            |</Fades>
            |<PitchCoarse Value="0" />
            |<PitchFine Value="0" />
            |<SampleVolume Value="1" />
            |<MarkerDensity Value="2" />
            |<AutoWarpTolerance Value="4" />
            |<SavedWarpMarkersForStretched />
            |<MarkersGenerated Value="false" />
            |<IsSongTempoMaster Value="false" />""".trimMargin()
        return wrap("AudioClip", time, body)
    }

    private fun wrap(tag: String, time: Double, body: String): String {
        val indent = "\t".repeat(10)
        val inner = body.lines().joinToString("\n") { "$indent\t$it" }
        return "\n$indent<$tag Id=\"0\" Time=\"${num(time)}\">\n$inner\n$indent</$tag>"
    }

    /** Numbers the way Live writes them: integers without a decimal point. */
    fun num(v: Double): String {
        if (v == Math.rint(v) && kotlin.math.abs(v) < 1e15) return v.toLong().toString()
        val s = "%.12f".format(java.util.Locale.ROOT, v).trimEnd('0')
        return if (s.endsWith(".")) s.dropLast(1) else s
    }

    fun xml(s: String): String = buildString {
        for (c in s) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> if (c.code < 0x20 && c != '\t') append(' ') else append(c)
        }
    }
}
