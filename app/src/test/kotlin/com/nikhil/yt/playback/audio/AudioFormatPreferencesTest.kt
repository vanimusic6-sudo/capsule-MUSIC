package com.nikhil.yt.playback.audio

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.nikhil.yt.constants.AudioClientOrderKey
import com.nikhil.yt.constants.AudioOffload
import com.nikhil.yt.constants.AudioQuality
import com.nikhil.yt.constants.AudioQualityKey
import com.nikhil.yt.constants.PreferAacAudioKey
import com.nikhil.yt.constants.ReleasePlaybackDefaults
import org.junit.Assert.*
import org.junit.Test

class AudioFormatPreferencesTest {
    @Test fun freshInstallUsesAutomaticFormatsAndTheExistingQualityDefault() {
        val prefs = emptyPreferences()
        assertFalse(prefs.prefersAacAudio())
        assertEquals(ReleasePlaybackDefaults.audioQuality, prefs.effectiveAudioQuality())
        for (quality in listOf(AudioQuality.AUTO, AudioQuality.LOW, AudioQuality.HIGH)) {
            assertEquals(quality, mutablePreferencesOf(AudioQualityKey to quality.name).effectiveAudioQuality())
        }
    }

    @Test fun compatibilityPreferenceIsIndependentOfHardwareOffload() {
        for (offload in listOf(false, true)) {
            val prefs = mutablePreferencesOf(AudioOffload to offload, PreferAacAudioKey to true)
            assertEquals(AudioQuality.AAC, prefs.effectiveAudioQuality())
            prefs[PreferAacAudioKey] = false
            assertEquals(ReleasePlaybackDefaults.audioQuality, prefs.effectiveAudioQuality())
            assertEquals(offload, prefs[AudioOffload])
        }
    }

    @Test fun legacyAacOptInSurvivesQualityChangesWithoutReappearingAsAQualityTier() {
        val prefs = mutablePreferencesOf(AudioQualityKey to AudioQuality.AAC.name,
            AudioClientOrderKey to "user-client-order")
        assertTrue(prefs.prefersAacAudio())
        prefs.selectAudioQuality(AudioQuality.LOW)
        assertEquals(AudioQuality.LOW.name, prefs[AudioQualityKey])
        assertEquals(true, prefs[PreferAacAudioKey])
        assertEquals(AudioQuality.AAC, prefs.effectiveAudioQuality())
        assertEquals("user-client-order", prefs[AudioClientOrderKey])
    }

    @Test fun turningCompatibilityOffOverridesTheLegacyAacValue() {
        val prefs = mutablePreferencesOf(AudioQualityKey to AudioQuality.AAC.name, PreferAacAudioKey to false)
        assertFalse(prefs.prefersAacAudio())
        assertEquals(AudioQuality.HIGH, prefs.effectiveAudioQuality())
        prefs.selectAudioQuality(AudioQuality.AUTO)
        assertEquals(false, prefs[PreferAacAudioKey])
        assertEquals(AudioQuality.AUTO, prefs.effectiveAudioQuality())
    }

    @Test fun ordinaryQualityChangesDoNotEnableAacPreferenceOrWriteOtherSettings() {
        val prefs = mutablePreferencesOf(AudioOffload to false)
        prefs.selectAudioQuality(AudioQuality.LOW)
        assertEquals(false, prefs[PreferAacAudioKey])
        assertEquals(AudioQuality.LOW, prefs.effectiveAudioQuality())
        assertEquals(false, prefs[AudioOffload])
    }

    @Test fun invalidAndRetiredQualityValuesKeepTheExistingSafeDefaults() {
        assertEquals(ReleasePlaybackDefaults.audioQuality,
            mutablePreferencesOf(AudioQualityKey to "obsolete").effectiveAudioQuality())
        assertEquals(AudioQuality.HIGH,
            mutablePreferencesOf(AudioQualityKey to AudioQuality.HIGHEST.name).effectiveAudioQuality())
    }
}
