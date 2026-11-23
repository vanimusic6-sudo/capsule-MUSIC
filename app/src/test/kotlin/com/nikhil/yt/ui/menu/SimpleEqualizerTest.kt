package com.nikhil.yt.ui.menu

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SimpleEqualizerTest {
    private fun source(name:String):String =
        File("src/main/kotlin/com/nikhil/yt/ui/menu/${name}.kt").readText()

    @Test fun versionDialogDoesNotProvideUnrestrictedManualEq() {
        val dialog=source("PlayerMenu")
            .substringAfter("fun EqualizerDialog(")
            .substringBefore("@Composable\nprivate fun CapsuleStudioSlider(")
        assertTrue(dialog.contains("CapsuleEqPresets.all.chunked(2)"))
        assertTrue(dialog.contains("eq_version_original"))
        assertTrue(dialog.contains("version:speed_up"))
        assertTrue(dialog.contains("version:slowed"))
        assertTrue(dialog.contains("setEqEnabled(false)"))
        assertTrue(dialog.contains("setEqEnabled(true)"))
        assertTrue(dialog.contains("CapsuleEqualizerGraph("))
        assertTrue(dialog.contains("CapsuleEqPresets.CUSTOM_ID"))
        assertTrue(dialog.contains("manualLevels"))
        assertTrue(dialog.contains("capsuleMusicalEqLevels("))
        assertFalse(dialog.contains("setBassBoostEnabled("))
        assertFalse(dialog.contains("setOutputGainMb("))
        assertFalse(dialog.contains("showSaveProfileDialog"))
    }

    @Test fun versionPickerNeverReEnablesLegacyUnsafeEqProfile() {
        val src=File("src/main/kotlin/com/nikhil/yt/playback/MusicService.kt").readText()
        assertTrue(src.contains("(preset != null || isCustom)"))
        assertTrue(src.contains("version:speed_up"))
        assertTrue(src.contains("version:slowed"))
        assertTrue(src.contains("CapsuleEqPresets.CUSTOM_ID else preset?.id"))
        assertTrue(src.substringAfter("private fun readEqSettingsFromPrefs")
            .substringBefore("fun applyEqFlatPreset").contains("decodeEqBandLevels("))
    }

    @Test fun mathHelpersStillAvailableForFutureTests() {
        assertEquals(2,capsuleEqualizerBandAt(150f,300f,5,18f))
        assertEquals(0,capsuleEqualizerLevelAt(110f,220f,-1500,1500,18f))
        assertEquals("910 Hz",capsuleGraphHz(910))
    }
}
