package com.fuzzhawk.schnecke.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.fuzzhawk.schnecke.core.audio.LoopRenderer
import kotlin.math.floor
import kotlin.math.max

/**
 * Owns the audio hardware: a continuously running low-latency output stream driven by
 * [LoopRenderer], and on-demand microphone capture aligned to it via stream timestamps.
 */
class AudioEngine(private val context: Context) {
    val sampleRate = SAMPLE_RATE
    val renderer = LoopRenderer(sampleRate)

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var track: AudioTrack? = null
    private var outThread: Thread? = null
    @Volatile private var running = false

    // Latest output timestamp: frame [outTsFrame] was heard at [outTsNanos].
    @Volatile private var outTsFrame = -1L
    @Volatile private var outTsNanos = 0L
    @Volatile private var outLatencyFrames = 0

    @Volatile var hapticsEnabled = false
    private var hapticThread: Thread? = null

    fun start() {
        if (running || !hardwareEnabled) return
        running = true
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
            )
            .setBufferSizeInBytes(max(minBuf, 4 * 256 * 2 * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        track = t
        outLatencyFrames = t.bufferSizeInFrames
        t.play()
        outThread = Thread({ outputLoop(t) }, "schnecke-out").also { it.start() }
        hapticThread = Thread({ hapticLoop() }, "schnecke-haptics").also { it.start() }
    }

    fun stop() {
        running = false
        outThread?.join(500)
        hapticThread?.interrupt()
        track?.let { it.stop(); it.release() }
        track = null
    }

    private fun outputLoop(t: AudioTrack) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val frames = 192
        val buf = FloatArray(frames * 2)
        val ts = AudioTimestamp()
        var lastTs = 0L
        while (running) {
            renderer.render(buf, frames)
            var off = 0
            while (off < buf.size && running) {
                val n = t.write(buf, off, buf.size - off, AudioTrack.WRITE_BLOCKING)
                if (n < 0) { Log.w(TAG, "AudioTrack.write error $n"); break }
                off += n
            }
            val now = System.nanoTime()
            if (now - lastTs > 50_000_000L && t.getTimestamp(ts)) {
                outTsFrame = ts.framePosition
                outTsNanos = ts.nanoTime
                lastTs = now
            }
        }
    }

    /** Rendered-frame index being heard right now (estimated from the output timestamp). */
    fun heardRenderedFrame(): Long {
        val f = outTsFrame
        return if (f >= 0) {
            f + ((System.nanoTime() - outTsNanos) * sampleRate / 1_000_000_000L)
        } else {
            renderer.renderedFrames - outLatencyFrames
        }
    }

    /** Transport position (frames, negative during count-in) currently heard. */
    fun heardTransportFrame(): Long =
        renderer.transportFrame - (renderer.renderedFrames - heardRenderedFrame()).coerceAtLeast(0)

    // ---------------------------------------------------------------- headphones

    fun headphonesConnected(): Boolean {
        val types = mutableSetOf(
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_USB_HEADSET,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) types.add(AudioDeviceInfo.TYPE_BLE_HEADSET)
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in types }
    }

    // ---------------------------------------------------------------- recording

    class Recording(
        val audio: FloatArray,
        /** inputFrame = renderedFrame + offset; null if the hardware gave no timestamps. */
        private val measuredOffset: Double?,
        private val fallbackOffset: Double,
    ) {
        val usedTimestamps: Boolean get() = measuredOffset != null
        fun inputFrameOf(renderedFrame: Long): Double = renderedFrame + (measuredOffset ?: fallbackOffset)
    }

    private inner class Capture(val record: AudioRecord) {
        @Volatile var stop = false
        var data = FloatArray(sampleRate * 30)
        var size = 0
        val offsets = ArrayList<Double>()
        var firstReadRendered = -1L
        lateinit var thread: Thread

        fun loop() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val chunk = FloatArray(256)
            val ts = AudioTimestamp()
            var lastTs = 0L
            while (!stop) {
                val n = record.read(chunk, 0, chunk.size, AudioRecord.READ_BLOCKING)
                if (n <= 0) { if (n < 0) Log.w(TAG, "AudioRecord.read error $n"); continue }
                if (firstReadRendered < 0) firstReadRendered = renderer.renderedFrames
                if (size + n > data.size) data = data.copyOf(data.size * 2)
                System.arraycopy(chunk, 0, data, size, n)
                size += n
                val now = System.nanoTime()
                if (now - lastTs > 100_000_000L && outTsFrame >= 0 &&
                    record.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS
                ) {
                    lastTs = now
                    val outFrame = outTsFrame
                    val outNanos = outTsNanos
                    // Both streams run at sampleRate: inputFrame = renderedFrame + offset.
                    val offset = ts.framePosition - outFrame + (outNanos - ts.nanoTime) * sampleRate / 1e9
                    offsets.add(offset)
                }
            }
        }
    }

    private var capture: Capture? = null

    @SuppressLint("MissingPermission")
    fun startRecording(): Boolean {
        if (!hardwareEnabled) return false
        if (capture != null) return true
        // UNPROCESSED skips the automatic gain and noise suppression that would smear transients.
        val source = if (audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true") {
            MediaRecorder.AudioSource.UNPROCESSED
        } else {
            MediaRecorder.AudioSource.VOICE_RECOGNITION
        }
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val rec = try {
            AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(max(minBuf * 2, sampleRate / 10 * 4))
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord", e)
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return false }
        // Always use the phone's own microphone: a headset mic would hear nothing of the table.
        audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
            ?.let { rec.preferredDevice = it }
        val c = Capture(rec)
        rec.startRecording()
        c.thread = Thread({ c.loop() }, "schnecke-in").also { it.start() }
        capture = c
        return true
    }

    fun stopRecording(): Recording? {
        val c = capture ?: return null
        capture = null
        c.stop = true
        c.thread.join(1000)
        try { c.record.stop() } catch (_: Exception) {}
        c.record.release()
        val measured = if (c.offsets.size >= 2) c.offsets.sorted()[c.offsets.size / 2] else null
        // Without timestamps: the first frame read was captured roughly when the frame being
        // rendered then was a buffer's worth from being heard.
        val fallback = -(c.firstReadRendered - outLatencyFrames).toDouble()
        return Recording(c.data.copyOf(c.size), measured, fallback)
    }

    val isRecording: Boolean get() = capture != null

    // ---------------------------------------------------------------- haptics

    private fun vibrator(): Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    /** Buzzes on every heard beat while playing, for a silent metronome in your hand or pocket. */
    private fun hapticLoop() {
        val vib = vibrator() ?: return
        var lastBeat = Long.MIN_VALUE
        try {
            while (running) {
                val snap = renderer.snapshot
                if (!hapticsEnabled || !renderer.isPlaying || snap == null) {
                    lastBeat = Long.MIN_VALUE
                    Thread.sleep(20)
                    continue
                }
                val pos = heardTransportFrame()
                val beat = floor(pos / snap.framesPerBeat).toLong()
                if (beat != lastBeat && lastBeat != Long.MIN_VALUE) {
                    val accent = Math.floorMod(beat, snap.beatsPerBar.toLong()) == 0L
                    vib.vibrate(VibrationEffect.createOneShot(if (accent) 45 else 22, if (accent) 255 else 160))
                }
                lastBeat = beat
                SystemClock.sleep(4)
            }
        } catch (_: InterruptedException) {
        }
    }

    companion object {
        private const val TAG = "AudioEngine"
        const val SAMPLE_RATE = 48000

        /** Off in JVM tests, where there is no audio hardware to drive. */
        @Volatile
        var hardwareEnabled = true
    }
}
