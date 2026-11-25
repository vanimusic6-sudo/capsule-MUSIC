package com.nikhil.yt.ui.menu

import androidx.datastore.preferences.core.mutablePreferencesOf
import com.nikhil.yt.constants.EqualizerBandLevelsMbKey
import com.nikhil.yt.constants.EqualizerEnabledKey
import com.nikhil.yt.constants.EqualizerSelectedProfileIdKey
import com.nikhil.yt.constants.SoundStudioTempoVariantKey
import com.nikhil.yt.playback.SoundStudioTempoVariant
import com.nikhil.yt.playback.CapsuleTrackSound
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
        assertTrue(dialog.contains("CapsuleEqualizerGraph("))
        assertTrue(dialog.contains("EqSection(title = equalizerLabel"))
        assertTrue(dialog.contains("CapsuleMixerSection(service, mixerProfile)"))
        assertTrue(dialog.contains("service.previewStudioMixer(setting.effect, amount)"))
        assertTrue(dialog.contains("service.setSoundStudioMixer(setting.effect, it)"))
        assertTrue(dialog.contains("CapsuleMixerEffect.OFF"))
        assertTrue(dialog.contains("SoundStudioTempoVariant.SPEED_UP"))
        assertTrue(dialog.contains("SoundStudioTempoVariant.SLOWED"))
        assertTrue(dialog.contains("EqSection("))
        assertTrue(dialog.contains("Switch("))
        assertFalse(dialog.contains("eq_curve_intensity"))
        assertFalse(dialog.contains("eq_clean_headroom"))
        assertFalse(dialog.contains("CapsuleDjControlsPanel"))
        assertFalse(dialog.contains("eq_dj_jog"))
        assertFalse(dialog.contains("eq_mixer_sun_hint"))
        assertFalse(dialog.contains("eq_mixer_vinyl_hint"))
        assertTrue(dialog.contains("R.string.capsule_studio_final_fast"))
        assertTrue(dialog.contains("R.string.capsule_studio_final_slow"))
        assertTrue(dialog.contains("editable = eqEnabled"))
        assertTrue(dialog.contains("selectSoundVersion(CAPSULE_STUDIO_MANUAL_ID, next)"))
        assertFalse(dialog.contains("eq_version_original"))
        assertFalse(dialog.contains("eq_adaptive_activate"))
        assertTrue(dialog.contains("selectTempoVariant(variant)"))
        assertTrue(dialog.indexOf("CapsuleEqualizerGraph(") <
            dialog.indexOf("CapsuleMixerSection(service, mixerProfile)"))
        assertFalse(dialog.contains("showBands"))
        assertTrue(dialog.contains("service.previewStudioCurve(next)"))
        assertTrue(dialog.contains("onDispose { service.cancelStudioPreview() }"))
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
        assertEquals(1.18f, CapsuleTrackSound.NEUTRAL.tempo(SoundStudioTempoVariant.SPEED_UP).speed, 0f)
        assertEquals(0.85f, CapsuleTrackSound.NEUTRAL.tempo(SoundStudioTempoVariant.SLOWED).speed, 0f)
        assertEquals(1.0594631f, CapsuleTrackSound.NEUTRAL.tempo(SoundStudioTempoVariant.SPEED_UP).pitch, 0f)
        assertEquals(0.9438743f, CapsuleTrackSound.NEUTRAL.tempo(SoundStudioTempoVariant.SLOWED).pitch, 0f)
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

    @Test fun everyStudioLocaleProvidesAllVisibleLabelsAndSafeListeningNote() {
        val res = File("src/main/res")
        val files = res.listFiles().orEmpty().filter { it.isDirectory && it.name.startsWith("values") }
            .map { File(it, "capsule_studio_final.xml") }
        val pattern = Regex("""name="(capsule_studio_final_[a-z_]+)"""")
        val expected = pattern.findAll(File(res, "values/capsule_studio_final.xml").readText())
            .map { it.groupValues[1] }.toSet()
        assertEquals(24, expected.size)
        assertTrue(files.size >= 24)
        for (file in files) {
            assertTrue("Missing studio locale: " + file, file.isFile)
            val xml = file.readText()
            val names = pattern.findAll(xml).map { it.groupValues[1] }.toList()
            assertEquals(file.path, expected, names.toSet())
            assertEquals(file.path, expected.size, names.size)
            assertTrue(xml.contains("%1\$d%%"))
            assertTrue(xml.contains("%1\$s"))
        }
        val dialog = file("PlayerMenu").substringAfter("fun EqualizerDialog(")
        val references = Regex("""R\.string\.(capsule_studio_final_[a-z_]+)""")
            .findAll(dialog).map { it.groupValues[1] }.toSet()
        assertTrue(expected.containsAll(references))
        assertFalse(dialog.contains("eq_mixer_soft_hint"))
        assertFalse(dialog.contains("eq_studio_tape_hint"))
    }

    @Test fun hardwareAwareGraphStillHandlesTouchAndLabels() {
        val graph=file("CapsuleEqualizerGraph")
        assertTrue(graph.contains("awaitEachGesture"))
        assertTrue(graph.contains("change.consume()"))
        assertEquals(2,capsuleEqualizerBandAt(150f,300f,5,18f))
        assertEquals("910 Hz",capsuleGraphHz(910))
    }
}
