package com.nikhil.yt.ui.menu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CapsuleEqualizerGraphTest {
    @Test fun bandSelectionClampsToActualDeviceBands() {
        assertEquals(0, capsuleEqualizerBandAt(-100f, 300f, 6, 18f))
        assertEquals(5, capsuleEqualizerBandAt(900f, 300f, 6, 18f))
        assertEquals(2, capsuleEqualizerBandAt(124f, 300f, 6, 18f))
        assertEquals(0, capsuleEqualizerBandAt(150f, 300f, 1, 18f))
        assertEquals(0, capsuleEqualizerBandAt(150f, 300f, 0, 18f))
        assertEquals(0, capsuleEqualizerBandAt(150f, 0f, 6, 18f))
    }

    @Test fun gainMappingRespectsHardwareRangeAndPrecision() {
        assertEquals(1500, capsuleEqualizerLevelAt(0f, 214f, -1500, 1500, 18f))
        assertEquals(-1500, capsuleEqualizerLevelAt(214f, 214f, -1500, 1500, 18f))
        assertEquals(0, capsuleEqualizerLevelAt(107f, 214f, -1500, 1500, 18f))
        assertEquals(0, capsuleEqualizerLevelAt(107f, 214f, 0, 0, 18f))
        assertEquals(0, capsuleEqualizerLevelAt(40f, 20f, -500, 500, 18f))
    }

    @Test fun graphUsesRealBandsAndDirectTouchNotFixedMockFrequencies() {
        val src = listOf(
            java.io.File("src/main/kotlin/com/nikhil/yt/ui/menu/CapsuleEqualizerGraph.kt"),
            java.io.File("app/src/main/kotlin/com/nikhil/yt/ui/menu/CapsuleEqualizerGraph.kt"),
        ).first(java.io.File::isFile).readText()
        assertTrue(src.contains("frequenciesHz.take(count)"))
        assertTrue(src.contains("detectDragGestures("))
        assertTrue(src.contains("detectTapGestures"))
        assertTrue(src.contains("onFinished: (Int, Int) -> Unit"))
    }
}
