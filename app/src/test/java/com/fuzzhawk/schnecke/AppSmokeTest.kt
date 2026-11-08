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
import com.fuzzhawk.schnecke.ui.showIntroOnLaunch
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
import kotlin.math.abs

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
        showIntroOnLaunch = false
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
        compose.onNodeWithText("Set loop").assertExists()
        assertEquals(HandsStage.CAPTURE, vm.state.value.stage)
        shot("01_record")

        // Step 1 -> 2: a free recording, a two-bar groove played twice at 100 bpm, no metronome.
        val sr = AudioEngine.SAMPLE_RATE
        val bpm = 100.0
        val (hands, planted) = SyntheticTakes.handsGroove(bpm, loopStart = (0.8 * sr).toInt(), repeats = 2)
        vm.viewModelScope.launch { vm.useCapture(hands) }
        waitFor("set-loop step") { vm.state.value.stage == HandsStage.TRIM && vm.state.value.trim != null }
        val trim = vm.state.value.trim!!
        assertEquals(2, trim.bars)
        assertEquals(bpm, trim.bpm(sr), 1.5)
        assertTrue(abs(trim.inFrame - planted.first().sample) < sr / 200)
        compose.waitForIdle()
        shot("02_set_loop")

        // Step 2 -> 3: detect hits in the marked loop.
        vm.detectHits()
        waitFor("hands analyzed in the lab") { vm.state.value.stage == HandsStage.LAB && vm.state.value.layerViews.containsKey(Layer.HANDS) }
        compose.waitForIdle()
        val p = vm.state.value.project
        assertEquals(bpm, p.bpm, 1.5)
        assertEquals(2, p.bars)
        val handsNotes = p.notes.filter { it.voice.layer == Layer.HANDS }
        assertEquals(10, handsNotes.size)
        assertEquals(planted.take(10).count { it.voice == Voice.SNARE }, handsNotes.count { it.voice == Voice.SNARE })
        assertTrue("a snare was cut from the recording", (vm.state.value.recordedCandidates[Voice.SNARE] ?: 0) > 0)

        // Back to set-loop with the current points, and detect again.
        vm.editLoopPoints()
        waitFor("editing loop points") { vm.state.value.stage == HandsStage.TRIM && vm.state.value.trim != null }
        assertEquals(vm.state.value.project.layer(Layer.HANDS).take!!.loopStartFrame, vm.state.value.trim!!.inFrame)
        vm.nudgeLoopPoint(isIn = false, ms = 5)
        vm.detectHits()
        waitFor("lab again") { vm.state.value.stage == HandsStage.LAB && !vm.state.value.analyzing && vm.state.value.layerViews.containsKey(Layer.HANDS) }

        // Beatbox layer over the loop.
        val (beatbox, plantedBb) = SyntheticTakes.beatboxGroove(vm.state.value.project.bpm, loopStart = sr)
        vm.viewModelScope.launch {
            vm.applyTake(Layer.BEATBOX, beatbox, TakeInfo(sr, beatbox.size, sr, vm.state.value.project.bpm, 8.0))
        }
        waitFor("beatbox analyzed") { vm.state.value.layerViews.size == 2 && !vm.state.value.analyzing }
        compose.waitForIdle()
        val notes = vm.state.value.project.notes
        assertEquals(10, notes.count { it.voice.layer == Layer.HANDS })
        assertEquals(plantedBb.size, notes.count { it.voice.layer == Layer.BEATBOX })
        assertTrue(vm.state.value.settings.playHandsWhileBeatboxing)
        shot("03_lab")

        // Beatbox to samples, edit a step, re-classify a hit.
        vm.setLayerMode(Layer.BEATBOX, LayerMode.REPLACE)
        val before = vm.state.value.project.notes.size
        vm.toggleStep(Voice.PERC, 3)
        assertEquals(before + 1, vm.state.value.project.notes.size)
        vm.cycleVoiceAt(Voice.PERC, 3)
        assertTrue(vm.state.value.project.notes.any { it.voice == Voice.HIHAT })
        compose.waitForIdle()
        shot("04_lab_samples")

        compose.onNodeWithText("Sounds").performClick()
        compose.waitForIdle()
        shot("05_sounds")

        compose.onNodeWithText("Settings").performClick()
        compose.waitForIdle()
        shot("06_settings")

        compose.onNodeWithText("Export").performClick()
        compose.waitForIdle()
        shot("07_export")

        vm.export(ExportKind.ABLETON)
        waitFor("live set exported") { vm.state.value.exportResult != null }
        compose.waitForIdle()
        screen("08_export_ready")
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
        screen("09_teach")
        vm.teachCancel()

        vm.openCalibration()
        compose.waitForIdle()
        screen("10_calibrate")
        vm.closeCalibration()

        // Projects survive a reload.
        vm.saveNow()
        val id = vm.state.value.project.id
        vm.newProject()
        compose.waitForIdle()
        assertTrue(vm.state.value.project.notes.isEmpty())
        vm.open(id)
        waitFor("reopened project analyzed") { vm.state.value.layerViews.size == 2 }
        assertEquals(HandsStage.LAB, vm.state.value.stage)
        assertEquals(before + 1, vm.state.value.project.notes.size)
    }
}
