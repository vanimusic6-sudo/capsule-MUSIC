package com.nikhil.yt.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Structural guardrails for the HONOR notification artwork and detail-screen insets.
 * System island rendering and uninterrupted playback still require testing on HONOR.
 */
class CapsuleDetailHeaderAndArtworkRegressionTest {
    private fun read(path: String): String =
        listOf(File("src/main/kotlin/com/nikhil/yt/$path"),
            File("app/src/main/kotlin/com/nikhil/yt/$path"),
        ).first { it.isFile }.readText()

    @Test
    fun honorCoverMetadataUsesBoundedByteArrayAndNeverClearsAudioQueue() {
        val service = read("playback/MusicService.kt")
        val code = service.substringAfter("private fun publishSystemArtwork(")
            .substringBefore("private fun updateNotification()")
        assertTrue(code.contains("systemArtworkJob?.cancel()"))
        assertTrue(code.contains("Build.MANUFACTURER.equals(\"HONOR\""))
        assertTrue(code.contains("withContext(Dispatchers.IO)"))
        assertTrue(code.contains(".get(8, TimeUnit.SECONDS)"))
        assertTrue(code.contains("it.size < 400_000"))
        assertTrue(code.contains("current.mediaId != requested.mediaId"))
        assertTrue(code.contains("current.mediaMetadata.artworkUri != uri"))
        assertTrue(code.contains("PICTURE_TYPE_FRONT_COVER"))
        assertTrue(code.contains("COMMAND_CHANGE_MEDIA_ITEMS"))
        assertTrue(code.contains("player.replaceMediaItem("))
        assertFalse(code.contains("player.setMediaItems("))
        assertFalse(code.contains("player.prepare()"))
        assertFalse(code.contains("player.seekTo("))
        assertTrue(service.contains("publishSystemArtwork(mediaItem)"))
    }

    @Test
    fun fullScreenDetailPagesReserveTheirOwnToolbarArea() {
        for (page in listOf(
            "MoodAndGenresScreen.kt", "BrowseScreen.kt",
            "NewReleaseScreen.kt", "YouTubeBrowseScreen.kt",
        )) {
            val source = read("ui/screens/$page")
            assertTrue("$page must reserve its own overlaid toolbar",
                source.contains("WindowInsets(top = com.nikhil.yt.constants.AppBarHeight)"))
        }
        val history = read("ui/screens/HistoryScreen.kt")
        assertTrue(history.contains("if (selection || isSearching) 64.dp"))
        assertTrue(history.contains("Modifier.padding(top = com.nikhil.yt.constants.AppBarHeight)"))
    }
}
