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
        assertTrue(Regex("""visible\s*=\s*isManualScrolling\s*&&\s*isSynced\s*&&\s*hasDisplayableLyrics\s*&&\s*scrollLyrics""").containsMatchIn(source))
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
    fun `lyrics menu does not own or reapply Android system bars`() {
        val activity = source("com/nikhil/yt/MainActivity.kt")
        val screen = source("com/nikhil/yt/ui/player/LyricsScreen.kt")
        val menu = source("com/nikhil/yt/ui/menu/LyricsMenu.kt")
        assertFalse(activity.contains("LocalLyricsStatusBarRequest"))
        assertFalse(screen.contains("requestLyricsStatusBarHidden"))
        assertFalse(menu.contains("keepStatusBarHidden"))
    }

    @Test
    fun `initial lyrics focus waits for a real current line`() {
        val lyrics = source("com/nikhil/yt/ui/component/Lyrics.kt")
        assertTrue(lyrics.contains("findCurrentLineIndex(\n                lines,\n                playerConnection.player.currentPosition"))
        assertTrue(lyrics.contains("initialScrollDone = false"))
        assertTrue(lyrics.contains("suspend fun anchorLyricLine("))
        assertTrue(Regex("""anchorLyricLine\(\s*targetIndex = currentLineIndex,\s*animated = false,""").containsMatchIn(lyrics))
    }

    @Test
    fun `tapping a lyric leaves free scroll and restores follow mode`() {
        val lyrics = source("com/nikhil/yt/ui/component/Lyrics.kt")
        assertTrue(lyrics.contains("isManualScrolling = false"))
        assertTrue(lyrics.contains("currentLineIndex = index"))
        assertTrue(lyrics.contains("deferredCurrentLineIndex = index"))
        assertTrue(lyrics.contains("returnFocusHoldIndex = -1"))
        assertTrue(lyrics.contains("playerConnection.player.seekTo(seekPosition)"))
        assertTrue(lyrics.contains("val seekPosition = if (item.isInstrumental)"))
    }

    @Test
    fun `apple lyrics measure full glyph bounds and animate within them`() {
        val lyrics = source("com/nikhil/yt/ui/component/Lyrics.kt")
        assertTrue(lyrics.contains("appleLayoutFontSize = lyricsTextSize.sp"))
        assertFalse(lyrics.contains("appleLayoutFontSize = lyricsTextSize.sp * 0.96f"))
        assertTrue(lyrics.contains("val appleScaleTarget ="))
        assertTrue(lyrics.contains("0.96f"))
        assertTrue(lyrics.contains("appleVisualScale"))
        assertTrue(lyrics.contains("scaleX = appleVisualScale"))
        assertFalse(lyrics.contains("appleMusicStyle &&"))
    }

    @Test
    fun `lyrics renders the selected player theme through the shared background renderer`() {
        val content = source("com/nikhil/yt/ui/player/CapsuleLyricsContent.kt")
        assertTrue(content.contains("is CapsuleLyricsBackdrop.PlayerTheme -> PlayerBackground("))
        assertTrue(content.contains("playerBackground = backdrop.style"))
        assertTrue(content.contains("sharedAnimationTime = backdropAnimationTime"))
        assertTrue(content.contains("CapsuleLyricsBackdrop.ImmersiveColoring ->"))
        assertTrue(content.contains("animated = isVisible && onScreen && isPlaying"))
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
    fun `archive tune words use full stable glyph bounds and a bounded visual wave`() {
        val lyrics = source("com/nikhil/yt/ui/component/Lyrics.kt")
        assertTrue(lyrics.contains("archiveTuneMaxFontSize = lyricsTextSize.sp"))
        assertTrue(lyrics.contains("fontSize = archiveTuneMaxFontSize"))
        assertTrue(lyrics.contains("(0.96f + 0.04f * wave * safeLineFocus).coerceIn(0.96f, 1f)"))
        assertTrue(lyrics.contains("if (hasWordTimings && item.words != null)"))
        assertTrue(lyrics.contains("val fontWeight = FontWeight.Bold"))
    }

    // Display-frame cadence and pause behavior are exercised by CapsuleBackgroundClockTest.
    // Do not reintroduce the old test that required a visible-motion FPS cap.

    @Test
    fun `apple growth is text local and leaves viewport padding intact`() {
        val lyrics = source("com/nikhil/yt/ui/component/Lyrics.kt")
        assertTrue(lyrics.contains(".padding(horizontal = 4.dp, vertical = 2.dp)"))
        assertTrue(lyrics.contains("clip = false"))
        assertTrue(lyrics.contains("scaleX = appleVisualScale"))
        assertFalse(lyrics.contains("appleMusicStyle &&"))
    }
}
