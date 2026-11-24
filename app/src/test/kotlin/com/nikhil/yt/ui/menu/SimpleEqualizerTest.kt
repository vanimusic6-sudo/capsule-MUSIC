package com.nikhil.yt.ui.menu

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SimpleEqualizerTest {
    private fun file(name:String)=
        File("src/main/kotlin/com/nikhil/yt/ui/menu/${name}.kt").readText()

    @Test fun adaptiveGraphAndCuratedVersionsCoexist() {
        val dialog=file("PlayerMenu").substringAfter("fun EqualizerDialog(")
            .substringBefore("@Composable\nprivate fun CapsuleStudioSlider(")
        assertTrue(dialog.contains("CapsuleEqPresets.all.chunked(2)"))
        assertTrue(dialog.contains("CapsuleEqualizerGraph("))
        assertTrue(dialog.contains("appliedLevelsMb = if (eqEnabled) applied"))
        assertTrue(dialog.contains("val manual = eqEnabled"))
        assertTrue(dialog.contains("eq_ten_band_title"))
        assertTrue(dialog.contains("version:speed_up"))
        assertTrue(dialog.contains("version:slowed"))
        assertTrue(dialog.contains("EqSection("))
        assertTrue(dialog.contains("Switch("))
        assertFalse(dialog.contains("eq_curve_intensity"))
        assertFalse(dialog.contains("eq_clean_headroom"))
        assertTrue(dialog.contains("editable = eqEnabled"))
        assertTrue(dialog.contains("selectTempoVariant(id)"))
        assertTrue(dialog.indexOf("CapsuleEqualizerGraph(") <
            dialog.indexOf("CapsuleEqPresets.all.chunked(2)"))
        assertFalse(dialog.contains("setVirtualizerEnabled("))
        assertFalse(dialog.contains("setBassBoostEnabled("))
        assertFalse(dialog.contains("setOutputGainMb("))
    }

    @Test fun manualAndPresetSelectionsAreAtomicAndValidated() {
        val service=File("src/main/kotlin/com/nikhil/yt/playback/MusicService.kt").readText()
        val handler=service.substringAfter("fun selectSoundVersion(")
            .substringBefore("fun applyEqFlatPreset()")
        assertTrue(handler.contains("dataStore.edit"))
        assertTrue(handler.contains("prefs[EqualizerBandLevelsMbKey]"))
        assertTrue(handler.contains("manual = id == \"adaptive:manual\""))
        assertTrue(handler.contains("preset == null && !manual && !neutral"))
        assertTrue(handler.contains("EqualizerEnabledKey"))
        assertTrue(handler.contains("requestedLevelsMb.size <= 32"))
        assertFalse(handler.contains("BassBoost(true)"))
    }

    @Test fun independentTempoDoesNotOverwriteEqProfile() {
        val source=File("src/main/kotlin/com/nikhil/yt/playback/MusicService.kt").readText()
        val selector=source.substringAfter("fun selectTempoVariant(id: String)")
            .substringBefore("fun applyEqFlatPreset()")
        assertTrue(selector.contains("SoundStudioTempoVariantKey"))
        assertFalse(selector.contains("prefs[EqualizerBandLevelsMbKey] ="))
        val observe=source.substringAfter("dataStore.data\n            .map { prefs ->")
            .substringBefore(".distinctUntilChanged()")
        assertTrue(observe.contains("SoundStudioTempoVariantKey"))
        assertTrue(observe.contains("\"version:speed_up\" -> 1.18f"))
        assertTrue(observe.contains("\"version:slowed\" -> 0.84f"))
    }

    @Test fun hardwareAwareGraphStillHandlesTouchAndLabels() {
        val graph=file("CapsuleEqualizerGraph")
        assertTrue(graph.contains("awaitEachGesture"))
        assertTrue(graph.contains("change.consume()"))
        assertEquals(2,capsuleEqualizerBandAt(150f,300f,5,18f))
        assertEquals("910 Hz",capsuleGraphHz(910))
    }
}
