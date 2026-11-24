package com.nikhil.yt.ui.menu

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SimpleEqualizerTest {
    private fun source(name:String)=
        File("src/main/kotlin/com/nikhil/yt/ui/menu/${name}.kt").readText()

    @Test fun tenBandGraphHasOneHarmonizedInterfaceWithoutDebugPanels() {
        val dialog=source("PlayerMenu").substringAfter("fun EqualizerDialog(")
            .substringBefore("@Composable\nprivate fun CapsuleStudioSlider(")
        assertTrue(dialog.contains("CAPSULE_TEN_BAND_HZ"))
        assertTrue(dialog.contains("CapsuleEqualizerGraph("))
        assertTrue(dialog.contains("eq_ten_band_label"))
        assertTrue(dialog.contains("CapsuleEqPresets.all.chunked(3)"))
        assertTrue(dialog.contains("CapsuleSoundPill("))
        assertTrue(dialog.contains("appliedLevelsMb = if (eqEnabled) applied"))
        assertTrue(dialog.contains("Switch("))
        assertTrue(dialog.contains("eq_tone_character"))
        assertTrue(dialog.contains("eq_tempo_character"))
        assertTrue(dialog.contains("service.selectTempoVersion("))
        assertTrue(dialog.contains("capsuleTempoParams(tempoMode, tempoDepth)"))
        assertTrue(dialog.contains("inverseScaledEqLevel("))
        assertTrue(dialog.contains("editable = eqEnabled"))
        assertFalse(dialog.contains("eq_clean_headroom"))
        assertFalse(dialog.contains("eq_adaptive_corrected"))
        assertFalse(dialog.contains("setVirtualizerEnabled("))
        assertFalse(dialog.contains("setBassBoostEnabled("))
        assertFalse(dialog.contains("setOutputGainMb("))
    }

    @Test fun manualAndPresetSelectionsAreAtomicAndValidated() {
        val service=File("src/main/kotlin/com/nikhil/yt/playback/MusicService.kt").readText()
        val handler=service.substringAfter("fun selectSoundVersion(")
            .substringBefore("fun selectTempoVersion(")
        assertTrue(handler.contains("dataStore.edit"))
        assertTrue(handler.contains("prefs[EqualizerBandLevelsMbKey]"))
        assertTrue(handler.contains("manual = id == \"adaptive:manual\""))
        assertTrue(handler.contains("preset == null && !manual && !neutral"))
        assertTrue(handler.contains("EqualizerEnabledKey"))
        assertTrue(handler.contains("requestedLevelsMb.size <= 32"))
        assertFalse(handler.contains("BassBoost(true)"))
        val tempo=service.substringAfter("fun selectTempoVersion(")
            .substringBefore("fun applyEqFlatPreset()")
        assertTrue(tempo.contains("prefs[SoundStudioTempoModeKey]"))
        assertTrue(tempo.contains("prefs[SoundStudioTempoDepthKey]"))
        assertFalse(tempo.contains("prefs[EqualizerSelectedProfileIdKey]"))
    }

    @Test fun tenLabelsRemainReadableAndTouchesAreScoped() {
        val graph=source("CapsuleEqualizerGraph")
        assertTrue(graph.contains("awaitEachGesture"))
        assertTrue(graph.contains("change.consume()"))
        assertTrue(graph.contains("capsuleGraphCompactHz(hz)"))
        assertEquals(2,capsuleEqualizerBandAt(150f,300f,5,18f))
        assertEquals("910 Hz",capsuleGraphHz(910))
        assertEquals("1.3k",capsuleGraphCompactHz(1250))
        assertEquals("16k",capsuleGraphCompactHz(16000))
    }
}
