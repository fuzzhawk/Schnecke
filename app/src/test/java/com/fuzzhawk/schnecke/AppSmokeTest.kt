package com.fuzzhawk.schnecke

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.fuzzhawk.schnecke.audio.AudioEngine
import com.fuzzhawk.schnecke.core.dsp.SyntheticTakes
import com.fuzzhawk.schnecke.core.model.Layer
import com.fuzzhawk.schnecke.core.model.LayerMode
import com.fuzzhawk.schnecke.core.model.TakeInfo
import com.fuzzhawk.schnecke.core.model.Voice
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.zip.ZipFile

/**
 * Drives the real app on the JVM: feeds it synthetic recordings through the same path a
 * microphone take goes, walks every screen (saving screenshots to build/screens) and runs every
 * export.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w393dp-h852dp-xxhdpi")
class AppSmokeTest {
    init {
        AudioEngine.hardwareEnabled = false
    }

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val vm: MainViewModel get() = ViewModelProvider(compose.activity)[MainViewModel::class.java]

    private fun shot(name: String) = compose.onRoot().captureRoboImage("build/screens/$name.png")
    private fun screen(name: String) = captureScreenRoboImage("build/screens/$name.png")

    private fun waitFor(what: String, cond: () -> Boolean) = compose.waitUntil(20_000) { cond() }.also {
        assertTrue(what, cond())
    }

    @Test
    fun recordEditAndExportEverything() {
        compose.onNodeWithText("Hands").assertExists()
        shot("01_loop_empty")

        val sr = AudioEngine.SAMPLE_RATE
        val bpm = vm.state.value.project.bpm
        val (hands, planted) = SyntheticTakes.handsGroove(bpm, loopStart = sr)
        val (beatbox, plantedBb) = SyntheticTakes.beatboxGroove(bpm, loopStart = sr)
        vm.viewModelScope.launch {
            vm.applyTake(Layer.HANDS, hands, TakeInfo(sr, hands.size, sr, bpm, 8.0))
            vm.applyTake(Layer.BEATBOX, beatbox, TakeInfo(sr, beatbox.size, sr, bpm, 8.0))
        }
        waitFor("both takes analyzed") { vm.state.value.layerViews.size == 2 }
        compose.waitForIdle()
        val notes = vm.state.value.project.notes
        assertEquals(planted.size, notes.count { it.voice.layer == Layer.HANDS })
        assertEquals(plantedBb.size, notes.count { it.voice.layer == Layer.BEATBOX })
        assertEquals(planted.count { it.voice == Voice.SNARE }, notes.count { it.voice == Voice.SNARE })
        assertTrue("a snare was cut from the recording", (vm.state.value.recordedCandidates[Voice.SNARE] ?: 0) > 0)
        shot("02_loop_recorded")

        // Beatbox to samples, edit a step, re-classify a hit.
        vm.setLayerMode(Layer.BEATBOX, LayerMode.REPLACE)
        val before = vm.state.value.project.notes.size
        vm.toggleStep(Voice.PERC, 3)
        assertEquals(before + 1, vm.state.value.project.notes.size)
        vm.cycleVoiceAt(Voice.PERC, 3)
        assertTrue(vm.state.value.project.notes.any { it.voice == Voice.HIHAT })
        compose.waitForIdle()
        shot("03_loop_replace")

        compose.onNodeWithText("Sounds").performClick()
        compose.waitForIdle()
        shot("04_sounds")

        compose.onNodeWithText("Settings").performClick()
        compose.waitForIdle()
        shot("05_settings")

        compose.onNodeWithText("Export").performClick()
        compose.waitForIdle()
        shot("06_export")

        vm.export(ExportKind.ABLETON)
        waitFor("live set exported") { vm.state.value.exportResult != null }
        compose.waitForIdle()
        screen("07_export_ready")
        val zip = vm.state.value.exportResult!!.file
        ZipFile(zip).use { z ->
            val names = z.entries().toList().map { it.name }
            assertTrue(names.toString(), names.any { it.endsWith(".als") })
            assertTrue(names.toString(), names.any { it.endsWith("Snare loop.wav") })
            assertTrue(names.toString(), names.any { it.endsWith("One-shots/Loop 1 - Snare.wav") })
        }
        vm.dismissExport()

        vm.export(ExportKind.WAV, seconds = 20.0, wholeLoops = true, fadeOut = true)
        waitFor("wav exported") { vm.state.value.exportResult != null }
        val wav = vm.state.value.exportResult!!.file
        assertTrue(wav.name.endsWith(".wav") && wav.length() > 20 * sr * 4)
        vm.dismissExport()

        vm.export(ExportKind.MIDI, midiRepeats = 2)
        waitFor("midi exported") { vm.state.value.exportResult != null }
        assertEquals("MThd", vm.state.value.exportResult!!.file.readBytes().copyOfRange(0, 4).toString(Charsets.US_ASCII))
        vm.dismissExport()

        compose.onNodeWithText("Loop").performClick()
        vm.startTeach(Layer.HANDS)
        compose.waitForIdle()
        screen("08_teach")
        vm.teachCancel()

        vm.openCalibration()
        compose.waitForIdle()
        screen("09_calibrate")
        vm.closeCalibration()

        // Projects survive a reload.
        vm.saveNow()
        val id = vm.state.value.project.id
        vm.newProject()
        compose.waitForIdle()
        assertTrue(vm.state.value.project.notes.isEmpty())
        vm.open(id)
        waitFor("reopened project analyzed") { vm.state.value.layerViews.size == 2 }
        assertEquals(before + 1, vm.state.value.project.notes.size)
    }
}
