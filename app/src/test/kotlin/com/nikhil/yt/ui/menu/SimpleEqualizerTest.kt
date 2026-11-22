package com.nikhil.yt.ui.menu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SimpleEqualizerTest {
    private val frequencies = listOf(60, 250, 910, 3_600, 14_000)

    @Test fun graphSelectsRealHardwareBandInsteadOfThreeSyntheticGroups() {
        assertEquals(0, capsuleEqualizerBandAt(18f, 300f, 5, 18f))
        assertEquals(2, capsuleEqualizerBandAt(150f, 300f, 5, 18f))
        assertEquals(4, capsuleEqualizerBandAt(282f, 300f, 5, 18f))
        assertEquals(0, capsuleEqualizerBandAt(150f, 300f, 1, 18f))
    }

    @Test fun graphClampsVerticalTouchesToRealDeviceGainRange() {
        assertEquals(1_500, capsuleEqualizerLevelAt(0f, 220f, -1_500, 1_500, 18f))
        assertEquals(-1_500, capsuleEqualizerLevelAt(220f, 220f, -1_500, 1_500, 18f))
        assertEquals(0, capsuleEqualizerLevelAt(110f, 220f, -1_500, 1_500, 18f))
    }

    @Test fun presetValuesRespectNativeBandCountAndHardwareLimits() {
        assertEquals(5, simpleEqPresetLevels(frequencies, 5, -1_500, 1_500, "bass").size)
        assertEquals(List(5) { 0 }, simpleEqPresetLevels(frequencies, 5, -1_500, 1_500, "flat"))
        assertTrue(simpleEqPresetLevels(frequencies, 5, -1_500, 1_500, "bass")[0] >
            simpleEqPresetLevels(frequencies, 5, -1_500, 1_500, "bass")[4])
        assertEquals(emptyList<Int>(), simpleEqPresetLevels(emptyList(), 0, -1_500, 1_500, "bass"))
        assertTrue(simpleEqPresetLevels(frequencies, 5, -120, 130, "energy").all { it in -120..130 })
    }

    @Test fun mainEqualizerPutsVersionsPresetsAndActualCurveOnOnePage() {
        val file = listOf(
            File("src/main/kotlin/com/nikhil/yt/ui/menu/PlayerMenu.kt"),
            File("app/src/main/kotlin/com/nikhil/yt/ui/menu/PlayerMenu.kt"),
        ).first(File::isFile)
        val source = file.readText()
        val simple = source.substringAfter("if (!showAdvancedEqualizer) {")
            .substringBefore("TextButton(onClick = { showAdvancedEqualizer = false })")
        assertTrue(simple.contains("eq_versions_title"))
        assertTrue(simple.contains("presetOptions.forEach"))
        assertTrue(simple.contains("CapsuleEqualizerGraph("))
        assertTrue(simple.contains("graphHeight = graphHeight"))
        assertTrue(simple.contains("return@Column"))
        assertTrue(!simple.contains("CompactTempoPitchSection()"))
        assertTrue(!simple.contains("Slider("))
    }
}
