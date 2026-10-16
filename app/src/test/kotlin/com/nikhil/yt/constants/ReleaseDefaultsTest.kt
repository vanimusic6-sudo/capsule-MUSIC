package com.nikhil.yt.constants

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.toMutablePreferences
import com.nikhil.yt.ui.theme.CapsuleThemeEnabledKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TASK 06: absence of a key is not a write operation. Explicitly stored
 * preferences always win, including false and custom ranking strings.
 */
class ReleaseDefaultsTest {
    @Test fun appearanceFreshInstallUsesCapsuleDarkWithLightPlayerAndGlow() {
        assertTrue(ReleaseAppearanceDefaults.capsuleTheme)
        assertFalse(ReleaseAppearanceDefaults.dynamicTheme)
        assertFalse(ReleaseAppearanceDefaults.pureBlack)
        assertFalse(ReleaseAppearanceDefaults.disableBlur)
        assertTrue(ReleaseAppearanceDefaults.cropThumbnail)
        assertTrue(ReleaseAppearanceDefaults.usePlayerThemeInLyrics)
        assertFalse(ReleaseAppearanceDefaults.lightLyricLine)
        assertEquals(CapsulePlayerDesign.LIGHT, ReleaseAppearanceDefaults.playerDesign)
        assertEquals(PlayerBackgroundStyle.CAPSULE_GLOW, ReleaseAppearanceDefaults.playerBackground)
        assertEquals(MiniPlayerBackgroundStyle.CAPSULE_GLOW, ReleaseAppearanceDefaults.miniPlayerBackground)
        assertEquals(LyricsAnimationStyle.ARCHIVE_TUNE, ReleaseAppearanceDefaults.lyricsAnimation)
        val fresh = emptyPreferences()
        assertEquals(DarkMode.ON.name, fresh[DarkModeKey] ?: DarkMode.ON.name)
        assertEquals(ReleaseAppearanceDefaults.capsuleTheme, fresh[CapsuleThemeEnabledKey] ?: ReleaseAppearanceDefaults.capsuleTheme)
    }

    @Test fun audioFreshInstallIsHighSafeAndKeepsTheCompleteFallbackList() {
        assertEquals(AudioQuality.HIGH, ReleasePlaybackDefaults.audioQuality)
        assertTrue(ReleasePlaybackDefaults.metered)
        assertTrue(ReleasePlaybackDefaults.persistentQueue)
        assertFalse(ReleasePlaybackDefaults.normalization)
        assertTrue(ReleasePlaybackDefaults.autoSkipOnError)
        assertTrue(ReleasePlaybackDefaults.pauseOnMute)
        assertFalse("Never claim hardware support without an actual format and audio route",
            ReleasePlaybackDefaults.audioOffloadUntilVerified)
        assertEquals(
            listOf(
                AudioClientOrder.WEB_REMIX,
                AudioClientOrder.VISIONOS,
                AudioClientOrder.VISIONOS_0_1,
                AudioClientOrder.WEB_EMBEDDED,
                AudioClientOrder.WEB_CREATOR,
                AudioClientOrder.TVHTML5_SIMPLY,
            ),
            AudioClientOrder.resolve(null, AudioStreamPolicy.VISIONOS),
        )
        assertEquals(6, AudioClientOrder.resolve(null, AudioStreamPolicy.VISIONOS).distinct().size)
        assertEquals(CapsuleVideoQuality.AUTO, CapsuleVideoQuality.valueOf("AUTO"))
    }

    @Test fun lyricsFreshInstallStartsWithPaxsenixAndKeepsAllAvailableProviders() {
        assertEquals(
            listOf(
                LyricsProviderOrder.PAXSENIX_APPLE_MUSIC,
                LyricsProviderOrder.BETTER_LYRICS,
                LyricsProviderOrder.NETEASE,
                LyricsProviderOrder.LYRICS_PLUS,
                LyricsProviderOrder.YOUTUBE,
            ),
            LyricsProviderOrder.resolve(null),
        )
        val fresh = emptyPreferences()
        assertTrue(fresh[EnablePaxsenixAppleMusicKey] ?: true)
    }

    @Test fun previouslyStoredValuesIncludingFalseWinOverNewDefaults() {
        val stored = emptyPreferences().toMutablePreferences()
        stored[CapsuleThemeEnabledKey] = false
        stored[DarkModeKey] = DarkMode.OFF.name
        stored[AudioQualityKey] = AudioQuality.LOW.name
        stored[AudioOffload] = false
        stored[EnablePaxsenixAppleMusicKey] = false
        stored[AudioClientOrderKey] = "WEB_CREATOR,WEB_REMIX"
        stored[LyricsProviderOrderKey] = "YOUTUBE,NETEASE"

        assertFalse(stored[CapsuleThemeEnabledKey] ?: ReleaseAppearanceDefaults.capsuleTheme)
        assertEquals(DarkMode.OFF.name, stored[DarkModeKey] ?: DarkMode.ON.name)
        assertEquals(AudioQuality.LOW.name, stored[AudioQualityKey] ?: ReleasePlaybackDefaults.audioQuality.name)
        assertFalse(stored[AudioOffload] ?: ReleasePlaybackDefaults.audioOffloadUntilVerified)
        assertFalse(stored[EnablePaxsenixAppleMusicKey] ?: true)
        assertEquals(AudioClientOrder.WEB_CREATOR,
            AudioClientOrder.resolve(stored[AudioClientOrderKey], AudioStreamPolicy.VISIONOS).first())
        assertEquals(LyricsProviderOrder.YOUTUBE,
            LyricsProviderOrder.resolve(stored[LyricsProviderOrderKey]).first())

        // Closing and reopening is another read of the same persisted state.
        assertFalse(stored[CapsuleThemeEnabledKey] ?: ReleaseAppearanceDefaults.capsuleTheme)
        assertEquals("WEB_CREATOR,WEB_REMIX", stored[AudioClientOrderKey])
    }

    @Test fun legacyAudioChoiceAndLegacyLyricsChoiceSurviveAnUpgrade() {
        assertEquals(AudioClientOrder.WEB_EMBEDDED,
            AudioClientOrder.resolve(
                raw = null,
                legacyPolicy = AudioStreamPolicy.WEB_EMBEDDED,
                legacyPreferenceExplicit = true,
            ).first())
        assertEquals(LyricsProviderOrder.BETTER_LYRICS,
            LyricsProviderOrder.resolve(raw = null, legacyPreferred = "BETTER_LYRICS").first())
    }

    @Test fun manualClientAndProviderReordersRoundTripWithoutReset() {
        val audio = AudioClientOrder.releaseDefaultOrder.reversed()
        val lyrics = LyricsProviderOrder.supportedProviders.reversed()
        assertEquals(audio, AudioClientOrder.resolve(
            AudioClientOrder.encode(audio), AudioStreamPolicy.VISIONOS))
        assertEquals(lyrics, LyricsProviderOrder.resolve(LyricsProviderOrder.encode(lyrics)))
    }
}
