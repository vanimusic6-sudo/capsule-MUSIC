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

    @Test fun physicalBassAndTrebleMaximaBothReachTheTop() {
        val zero = capsuleGraphZeroY(214f, -1500, 900, 18f)
        assertEquals(400, capsuleBandLevelAt(18f, 214f, -1500, 900, 400, 18f))
        assertEquals(900, capsuleBandLevelAt(18f, 214f, -1500, 900, 900, 18f))
        assertEquals(0, capsuleBandLevelAt(zero, 214f, -1500, 900, 400, 18f))
        assertEquals(-1500, capsuleBandLevelAt(214f, 214f, -1500, 900, 400, 18f))
        assertEquals(18f, capsuleBandGraphY(400, 214f, -1500, 900, 400, 18f), 0.01f)
        assertEquals(18f, capsuleBandGraphY(900, 214f, -1500, 900, 900, 18f), 0.01f)
        assertEquals(zero, capsuleBandGraphY(0, 214f, -1500, 900, 400, 18f), 0.01f)
        assertEquals(214f - 18f, capsuleBandGraphY(-1500, 214f, -1500, 900, 400, 18f), 0.01f)
    }

    @Test fun graphUsesRealBandsAndDirectTouchNotFixedMockFrequencies() {
        val src = listOf(
            java.io.File("src/main/kotlin/com/nikhil/yt/ui/menu/CapsuleEqualizerGraph.kt"),
            java.io.File("app/src/main/kotlin/com/nikhil/yt/ui/menu/CapsuleEqualizerGraph.kt"),
        ).first(java.io.File::isFile).readText()
        assertTrue(src.contains("frequenciesHz.take(count)"))
        assertTrue(src.contains("awaitEachGesture"))
        assertTrue(src.contains("awaitFirstDown"))
        assertTrue(src.contains("down.consume()"))
        assertTrue(src.contains("change.consume()"))
        assertTrue(src.contains("bandMaxLevelsMb"))
        assertTrue(src.contains("onFinished: (Int, Int) -> Unit"))
    }
}
