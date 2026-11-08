package com.fuzzhawk.schnecke.data

import android.content.Context
import com.fuzzhawk.schnecke.core.model.Layer
import com.fuzzhawk.schnecke.core.model.Project
import com.fuzzhawk.schnecke.core.model.Voice
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.UUID

@Serializable
enum class AutoMode { AUTO, ON, OFF }

@Serializable
data class AppSettings(
    val countInBars: Int = 1,
    /** Metronome click while recording (after the count-in). AUTO = only with headphones. */
    val clickWhileRecording: AutoMode = AutoMode.AUTO,
    /** Play the hands loop while the beatbox layer is recorded (best with headphones). */
    val playHandsWhileBeatboxing: Boolean = true,
    val clickDuringPlayback: Boolean = false,
    val haptics: Boolean = true,
    val clickVolume: Float = 0.7f,
    /** Added to the measured alignment: positive if your taps land late. */
    val latencyMs: Float = 0f,
    val dynamicVelocity: Boolean = true,
    val lastProjectId: String? = null,
)

data class ProjectSummary(val id: String, val name: String, val updatedAt: Long)

/** Projects live in app-private storage: one folder each with JSON plus raw float32 audio. */
class ProjectStore(context: Context) {
    private val root = File(context.filesDir, "projects").apply { mkdirs() }
    private val settingsFile = File(context.filesDir, "settings.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun dir(id: String) = File(root, id).apply { mkdirs() }

    fun newProject(name: String): Project {
        val now = System.currentTimeMillis()
        return Project(id = UUID.randomUUID().toString(), name = name, createdAt = now, updatedAt = now)
    }

    fun save(p: Project) {
        val f = File(dir(p.id), "project.json")
        val tmp = File(f.parentFile, "project.json.tmp")
        tmp.writeText(json.encodeToString(Project.serializer(), p))
        tmp.renameTo(f)
    }

    fun load(id: String): Project? = try {
        json.decodeFromString(Project.serializer(), File(dir(id), "project.json").readText()).migrated()
    } catch (e: Exception) {
        null
    }

    fun list(): List<ProjectSummary> = root.listFiles().orEmpty()
        .mapNotNull { d -> load(d.name)?.let { ProjectSummary(it.id, it.name, it.updatedAt) } }
        .sortedByDescending { it.updatedAt }

    fun delete(id: String) {
        File(root, id).deleteRecursively()
    }

    fun duplicate(p: Project, name: String): Project {
        val copy = p.copy(id = UUID.randomUUID().toString(), name = name, updatedAt = System.currentTimeMillis())
        dir(p.id).listFiles()?.filter { it.name.endsWith(".f32") }?.forEach { it.copyTo(File(dir(copy.id), it.name), overwrite = true) }
        save(copy)
        return copy
    }

    fun saveTake(id: String, layer: Layer, audio: FloatArray) = writeFloats(File(dir(id), "take_${layer.name}.f32"), audio)
    fun loadTake(id: String, layer: Layer): FloatArray? = readFloats(File(dir(id), "take_${layer.name}.f32"))
    fun deleteTake(id: String, layer: Layer) { File(dir(id), "take_${layer.name}.f32").delete() }

    fun saveCustom(id: String, voice: Voice, audio: FloatArray) = writeFloats(File(dir(id), "custom_${voice.name}.f32"), audio)
    fun loadCustom(id: String, voice: Voice): FloatArray? = readFloats(File(dir(id), "custom_${voice.name}.f32"))

    fun loadSettings(): AppSettings = try {
        json.decodeFromString(AppSettings.serializer(), settingsFile.readText())
    } catch (e: Exception) {
        AppSettings()
    }

    fun saveSettings(s: AppSettings) = settingsFile.writeText(json.encodeToString(AppSettings.serializer(), s))

    private fun writeFloats(f: File, data: FloatArray) {
        RandomAccessFile(f, "rw").use { raf ->
            raf.setLength(data.size * 4L)
            val buf = raf.channel.map(FileChannel.MapMode.READ_WRITE, 0, data.size * 4L).order(ByteOrder.LITTLE_ENDIAN)
            buf.asFloatBuffer().put(data)
        }
    }

    private fun readFloats(f: File): FloatArray? {
        if (!f.exists()) return null
        return RandomAccessFile(f, "r").use { raf ->
            val buf = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length()).order(ByteOrder.LITTLE_ENDIAN)
            FloatArray((raf.length() / 4).toInt()).also { buf.asFloatBuffer().get(it) }
        }
    }
}
