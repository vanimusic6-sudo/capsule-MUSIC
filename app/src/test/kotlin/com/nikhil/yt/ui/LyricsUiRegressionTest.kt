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
}
