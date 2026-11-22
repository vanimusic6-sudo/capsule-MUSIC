package com.nikhil.yt.ui.menu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class SimpleEqualizerTest {
    private fun source(name: String): String = listOf(
        File("src/main/kotlin/com/nikhil/yt/ui/menu/$name.kt"),
        File("app/src/main/kotlin/com/nikhil/yt/ui/menu/$name.kt"),
    ).first(File::isFile).readText()

    @Test fun graphAdjustsRealHardwareBands() {
        assertEquals(0, capsuleEqualizerBandAt(18f, 300f, 5, 18f))
        assertEquals(2, capsuleEqualizerBandAt(150f, 300f, 5, 18f))
        assertEquals(4, capsuleEqualizerBandAt(282f, 300f, 5, 18f))
        assertEquals(0, capsuleEqualizerBandAt(150f, 300f, 1, 18f))
        assertEquals(1_500, capsuleEqualizerLevelAt(0f, 220f, -1_500, 1_500, 18f))
        assertEquals(-1_500, capsuleEqualizerLevelAt(220f, 220f, -1_500, 1_500, 18f))
        assertEquals(0, capsuleEqualizerLevelAt(110f, 220f, -1_500, 1_500, 18f))
    }

    @Test fun frequenciesAreOriginalDeviceCentersWithUnits() {
        assertEquals("60 Hz", capsuleGraphHz(60))
        assertEquals("230 Hz", capsuleGraphHz(230))
        assertEquals("910 Hz", capsuleGraphHz(910))
        assertEquals("3.6 kHz", capsuleGraphHz(3600))
        assertEquals("14 kHz", capsuleGraphHz(14000))
        assertEquals("—", capsuleGraphHz(0))
    }

    @Test fun allSoundControlsShareOneScrollSurface() {
        val s = source("PlayerMenu")
        val dialog = s.substringAfter("fun EqualizerDialog(")
            .substringBefore("@Composable\nprivate fun CapsuleEffectRow(")
        assertTrue(dialog.contains("Slider("))
        assertTrue(dialog.contains("multiplierToSlider(speed)"))
        assertTrue(dialog.contains("CapsuleEqPresets.all.chunked(3)"))
        assertTrue(dialog.contains("CapsuleEqPresets.levelsFor("))
        assertTrue(dialog.contains("capsule:"))
        assertFalse(dialog.contains("caps.systemPresets.mapIndexed"))
        assertFalse(dialog.contains("applySystemEqPreset(preset)"))
        assertTrue(dialog.contains("CapsuleEqualizerGraph("))
        assertTrue(dialog.contains("graphHeight = graphHeight"))
        assertFalse(dialog.contains("eq_offline_edit_hint"))
        assertTrue(dialog.contains("scaleEqBandLevels("))
        assertTrue(dialog.contains("inverseScaledEqLevel("))
        assertFalse(dialog.contains("eq_curve_intensity_hint"))
        assertTrue(dialog.contains("CapsuleStudioSlider("))
        assertTrue(dialog.contains("eq_audio_studio"))
        assertTrue(dialog.contains("CapsuleEffectRow("))
        assertTrue(dialog.contains("eq_effect_spatial"))
        assertTrue(dialog.contains("eq_output_gain"))
        assertTrue(dialog.contains("showSaveProfileDialog = true"))
        assertFalse(dialog.contains("showAdvancedEqualizer"))
        assertFalse(dialog.contains("eq_simple_bass"))
        assertFalse(dialog.contains("eq_versions_title"))
        assertFalse(dialog.contains("simpleEqPresetLevels"))
        assertFalse(dialog.contains("TempoPitchSection()"))
    }

    @Test fun studioMasterBypassesTempoPitchAndDoesNotAutoEnableItself() {
        val source = source("PlayerMenu")
        val dialog = source.substringAfter("fun EqualizerDialog(")
            .substringBefore("@Composable\\nprivate fun CapsuleEffectRow(")
        assertTrue(dialog.contains("SoundStudioSpeedKey"))
        assertTrue(dialog.contains("SoundStudioPitchKey"))
        assertTrue(dialog.contains("setEqEnabled(enabled)"))
        assertTrue(dialog.contains("enabled = eqEnabled"))
        assertTrue(dialog.contains("if (eqEnabled) playerConnection.player.playbackParameters"))
        assertFalse(dialog.contains("setEqEnabled(true)"))
    }

    @Test fun graphConsumesTouchBeforeParentScroll() {
        val graph = source("CapsuleEqualizerGraph")
        assertTrue(graph.contains("awaitEachGesture"))
        assertTrue(graph.contains("down.consume()"))
        assertTrue(graph.contains("change.consume()"))
        assertFalse(graph.contains("detectDragGestures"))
    }

    @Test fun presetsAreFromAndroidAndEffectStrengthIsBounded() {
        val s = source("PlayerMenu")
        assertTrue(s.contains("CapsuleEqPresets.all.chunked(3)"))
        assertFalse(s.contains("caps.systemPresets.mapIndexed"))
        assertFalse(s.contains("availablePresets"))
        assertFalse(s.contains("Text(current,"))
        assertTrue(s.contains("valueRange = 0..100"))
        assertTrue(s.contains("percent * CAPSULE_MAX_BASS_STRENGTH / 100"))
        assertTrue(s.contains("percent * CAPSULE_MAX_VIRTUALIZER_STRENGTH / 100"))
        assertTrue(s.contains("val graphMaxMb ="))
        assertTrue(s.contains("maxMb = graphMaxMb"))
        assertTrue(s.contains("enabled = enabled && checked"))
        assertTrue(s.contains("valueRange = -1500..CAPSULE_MAX_OUTPUT_GAIN_MB"))
        assertFalse(s.contains("simpleEqPresetLevels("))
    }
}
