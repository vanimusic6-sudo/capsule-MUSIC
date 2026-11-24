package com.nikhil.yt.ui.menu

import androidx.datastore.preferences.core.mutablePreferencesOf
import com.nikhil.yt.constants.EqualizerBandLevelsMbKey
import com.nikhil.yt.constants.EqualizerEnabledKey
import com.nikhil.yt.constants.EqualizerSelectedProfileIdKey
import com.nikhil.yt.constants.SoundStudioTempoVariantKey
import com.nikhil.yt.playback.SoundStudioTempoVariant
import com.nikhil.yt.playback.preserveLegacySoundStudioTempo
import com.nikhil.yt.playback.readSoundStudioTempoVariant
import com.nikhil.yt.playback.setSoundStudioTempoVariant
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SimpleEqualizerTest {
    private fun file(name:String)=
        File("src/main/kotlin/com/nikhil/yt/ui/menu/${name}.kt").readText()

    @Test fun adaptiveGraphAndCuratedVersionsCoexist() {
        val dialog=file("PlayerMenu").substringAfter("fun EqualizerDialog(")
            .substringBefore("@Composable\nprivate fun RowScope.StudioChoice(")
        assertTrue(dialog.contains("CapsuleEqPresets.entries.chunked(2)"))
        assertTrue(dialog.contains("CapsuleEqualizerGraph("))
        assertTrue(dialog.contains("eq_ten_band_title"))
        assertTrue(dialog.contains("SoundStudioTempoVariant.SPEED_UP"))
        assertTrue(dialog.contains("SoundStudioTempoVariant.SLOWED"))
        assertTrue(dialog.contains("EqSection("))
        assertTrue(dialog.contains("Switch("))
        assertFalse(dialog.contains("eq_curve_intensity"))
        assertFalse(dialog.contains("eq_clean_headroom"))
        assertTrue(dialog.contains("editable = eqEnabled"))
        assertTrue(dialog.contains("selectSoundVersion(CAPSULE_STUDIO_MANUAL_ID, next)"))
        assertFalse(dialog.contains("eq_version_original"))
        assertFalse(dialog.contains("eq_adaptive_activate"))
        assertTrue(dialog.contains("selectTempoVariant(variant)"))
        assertTrue(dialog.indexOf("CapsuleEqPresets.entries.chunked(2)") <
            dialog.indexOf("CapsuleEqualizerGraph("))
        assertTrue(dialog.contains("if (showBands)"))
        assertFalse(dialog.contains("setVirtualizerEnabled("))
        assertFalse(dialog.contains("setBassBoostEnabled("))
        assertFalse(dialog.contains("setOutputGainMb("))
    }

    @Test fun manualAndPresetSelectionsAreAtomicAndValidated() {
        val service=File("src/main/kotlin/com/nikhil/yt/playback/MusicService.kt").readText()
        val handler=service.substringAfter("fun selectSoundVersion(")
            .substringBefore("fun selectTempoVariant(")
        assertTrue(handler.contains("dataStore.edit"))
        assertTrue(handler.contains("prefs[EqualizerBandLevelsMbKey]"))
        assertTrue(handler.contains("manual = id == CAPSULE_STUDIO_MANUAL_ID"))
        assertTrue(handler.contains("preset == null && !manual"))
        assertTrue(handler.contains("EqualizerEnabledKey"))
        assertTrue(handler.contains("requestedLevelsMb.size == CAPSULE_STUDIO_FREQUENCIES_HZ.size"))
        assertTrue(handler.contains("prefs.preserveLegacySoundStudioTempo()"))
        assertFalse(handler.contains("BassBoost(true)"))
    }

    @Test fun independentTempoDoesNotOverwriteEqProfile() {
        val curve = "[0,0,10,20,30,40,50,60,70,80]"
        val prefs = mutablePreferencesOf(
            EqualizerSelectedProfileIdKey to "adaptive:manual",
            EqualizerBandLevelsMbKey to curve,
            EqualizerEnabledKey to true,
        )
        for (variant in SoundStudioTempoVariant.entries) {
            prefs.setSoundStudioTempoVariant(variant)
            assertEquals(variant, prefs.readSoundStudioTempoVariant())
            assertEquals("adaptive:manual", prefs[EqualizerSelectedProfileIdKey])
            assertEquals(curve, prefs[EqualizerBandLevelsMbKey])
            assertEquals(true, prefs[EqualizerEnabledKey])
        }
        assertEquals(1.18f, SoundStudioTempoVariant.SPEED_UP.rate, 0f)
        assertEquals(0.84f, SoundStudioTempoVariant.SLOWED.rate, 0f)
    }

    @Test fun legacyTempoSurvivesFirstToneSelectionAndExplicitNormalWins() {
        val prefs = mutablePreferencesOf(EqualizerSelectedProfileIdKey to "version:slowed")
        assertEquals(SoundStudioTempoVariant.SLOWED, prefs.readSoundStudioTempoVariant())
        prefs.preserveLegacySoundStudioTempo()
        prefs[EqualizerSelectedProfileIdKey] = "capsule:soft_bass_v2"
        assertEquals(SoundStudioTempoVariant.SLOWED, prefs.readSoundStudioTempoVariant())

        prefs[EqualizerSelectedProfileIdKey] = "version:speed_up"
        prefs.setSoundStudioTempoVariant(SoundStudioTempoVariant.NORMAL)
        prefs.preserveLegacySoundStudioTempo()
        assertEquals(SoundStudioTempoVariant.NORMAL, prefs.readSoundStudioTempoVariant())
        prefs[SoundStudioTempoVariantKey] = "invalid"
        assertEquals(SoundStudioTempoVariant.NORMAL, prefs.readSoundStudioTempoVariant())
    }

    @Test fun hardwareAwareGraphStillHandlesTouchAndLabels() {
        val graph=file("CapsuleEqualizerGraph")
        assertTrue(graph.contains("awaitEachGesture"))
        assertTrue(graph.contains("change.consume()"))
        assertEquals(2,capsuleEqualizerBandAt(150f,300f,5,18f))
        assertEquals("910 Hz",capsuleGraphHz(910))
    }
}
