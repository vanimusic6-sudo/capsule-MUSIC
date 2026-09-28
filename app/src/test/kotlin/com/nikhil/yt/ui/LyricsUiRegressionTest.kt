package com.nikhil.yt.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsUiRegressionTest {
    private fun source(relative: String): String {
        val candidates =
            listOf(
                File("src/main/kotlin/$relative"),
                File("app/src/main/kotlin/$relative"),
            )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("Could not find $relative")
    }

    @Test
    fun `lyrics header keeps physical cutout inset while status bar is hidden`() {
        val source = source("com/nikhil/yt/ui/player/CapsuleLyricsContent.kt")
        assertTrue(source.contains("WindowInsets.systemBarsIgnoringVisibility"))
    }

    @Test
    fun `manual lyric scrolling stays detached until resume is pressed`() {
        val source = source("com/nikhil/yt/ui/component/Lyrics.kt")
        assertTrue(source.contains("visible = isManualScrolling && scrollLyrics"))
        assertFalse(source.contains("delay(LyricsPreviewTime)"))
        assertTrue(source.contains("isManualScrolling = false"))
    }

    @Test
    fun `sync offset restarts full lyrics timing loop immediately`() {
        val source = source("com/nikhil/yt/ui/component/Lyrics.kt")
        assertTrue(source.contains("wordSyncLeadMs,\n        lineSyncLeadMs,"))
    }

    @Test
    fun `lyrics translation action is fully removed`() {
        val source = source("com/nikhil/yt/ui/menu/LyricsMenu.kt")
        assertFalse(source.contains("showTranslateDialog"))
        assertFalse(source.contains("R.drawable.translate"))
        assertFalse(source.contains("TranslatorLanguages"))
    }

    @Test
    fun `archive tune animation is a dedicated style`() {
        val keys = source("com/nikhil/yt/constants/PreferenceKeys.kt")
        val lyrics = source("com/nikhil/yt/ui/component/Lyrics.kt")
        assertTrue(keys.contains("ARCHIVE_TUNE"))
        assertTrue(lyrics.contains("ArchiveTuneWord"))
        assertTrue(lyrics.contains("LyricsAnimationStyle.ARCHIVE_TUNE"))
    }

    @Test
    fun `lyrics menu system bars follow player design`() {
        val player = source("com/nikhil/yt/ui/player/Player.kt")
        val screen = source("com/nikhil/yt/ui/player/LyricsScreen.kt")
        assertTrue(player.contains("hideStatusBar = design == CapsulePlayerDesign.IMMERSIVE"))
        assertTrue(screen.contains("requestLyricsStatusBarHidden(hideStatusBar)"))
    }

    @Test
    fun `lyrics player has its own five background choices`() {
        val keys = source("com/nikhil/yt/constants/PreferenceKeys.kt")
        val content = source("com/nikhil/yt/ui/player/CapsuleLyricsContent.kt")
        assertTrue(keys.contains("enum class LyricsBackgroundStyle"))
        assertTrue(keys.contains("CAPSULE_STAR"))
        assertTrue(keys.contains("CAPSULE_GLOW"))
        assertTrue(keys.contains("NEBULA"))
        assertTrue(keys.contains("ARTWORK_GRADIENT"))
        assertTrue(content.contains("CapsuleBackgroundEffect.CAPSULE_STAR"))
        assertTrue(content.contains("CapsuleBackgroundEffect.CAPSULE_GLOW"))
        assertTrue(content.contains("CapsuleBackgroundEffect.NEBULA"))
        assertTrue(content.contains("CapsuleBackgroundEffect.MATTE_GRADIENT"))
    }

    @Test
    fun `animated lyric focus does not change text metrics`() {
        val lyrics = source("com/nikhil/yt/ui/component/Lyrics.kt")
        assertFalse(lyrics.contains("val wordWeight = if (hasRomanization)"))
        assertFalse(lyrics.contains("fontWeight = if (isActiveLine)"))
    }

    @Test
    fun `lyrics tuning uses lowercase positions and centered sync controls`() {
        val tuning = source("com/nikhil/yt/ui/menu/LyricsTuning.kt")
        assertTrue(tuning.contains(".replaceFirstChar { it.lowercase() }"))
        assertTrue(tuning.contains("horizontalArrangement = Arrangement.Center"))
        assertTrue(tuning.contains("textAlign = TextAlign.Center"))
        assertFalse(tuning.contains("LyricsBackgroundStyle"))
    }

    @Test
    fun `lyrics controls inherit the selected background instead of opaque black`() {
        val content = source("com/nikhil/yt/ui/player/CapsuleLyricsContent.kt")
        assertFalse(content.contains("Color(0xFF171717)"))
        assertTrue(content.contains("CapsuleLyricsText.copy(alpha = 0.025f)"))
        assertTrue(content.contains(".background(\n                        lyricsPanelColor"))
    }

    @Test
    fun `archive tune words reserve smaller stable metrics and never grow beyond old base`() {
        val lyrics = source("com/nikhil/yt/ui/component/Lyrics.kt")
        assertTrue(lyrics.contains("archiveTuneFontSize = lyricsTextSize.sp * 0.96f"))
        assertTrue(lyrics.contains("((1f / 0.96f) - 1f) * wave"))
        assertTrue(lyrics.contains("if (hasWordTimings && item.words != null)"))
        assertFalse(lyrics.contains("FontWeight.ExtraBold"))
    }

    @Test
    fun `lyrics animation clock is bounded below display refresh rate`() {
        val lyrics = source("com/nikhil/yt/ui/component/Lyrics.kt")
        assertFalse(lyrics.contains("withFrameNanos"))
        assertTrue(lyrics.contains("delay(33L)"))
        assertTrue(lyrics.contains("delay(150L)"))
    }
}
