package com.nikhil.yt.ui.player

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Structural guards for stale-track artwork and bounded retry; not a pixel test. */
class PlayerArtworkReliabilityTest {
    private fun source(name: String): String {
        val relative = "src/main/kotlin/com/nikhil/yt/ui/player/$name"
        return listOf(File(relative), File("app/$relative")).firstOrNull { it.isFile }?.readText()
            ?: error("Missing $name")
    }

    @Test fun staleRoomTrackCannotOverrideMedia3Artwork() {
        val block = source("Player.kt")
            .substringAfter("val enrichedMetadata =").substringBefore("val onScreen = appIsOnScreen()")
        assertTrue(block.contains("currentSong?.takeIf { it.id == metadata.id }"))
        assertTrue(block.contains("thumbnailUrl = capsulePlayerArtworkUrl("))
        assertTrue(block.contains("metadata.thumbnailUrl, currentSongForTrack?.song?.thumbnailUrl,"))
        assertFalse(block.contains("thumbnailUrl = metadata.thumbnailUrl ?: currentSong?.song?.thumbnailUrl"))
    }

    @Test fun loadedLocalArtworkBeatsBlankButNotValidMedia3Artwork() {
        assertEquals("cached.jpg", capsulePlayerArtworkUrl("", "cached.jpg"))
        assertEquals("cached.jpg", capsulePlayerArtworkUrl(null, "cached.jpg"))
        assertEquals("playback.jpg", capsulePlayerArtworkUrl("playback.jpg", "cached.jpg"))
        assertNull(capsulePlayerArtworkUrl("  ", "  "))
        assertNull(capsulePlayerArtworkUrl(null, null))
    }

    @Test fun immersiveSamplerRetriesAfterSheetWasCollapsed() {
        assertTrue(source("Player.kt").contains("enabled = onScreen && playerState.isExpandedOrExpanding"))
        assertTrue(source("Player.kt").contains("refreshOnOpen = state.isExpandedOrExpanding"))
        assertTrue(source("CapsuleArtworkPalette.kt").contains("refreshOnOpen,"))
        assertTrue(source("CapsuleArtworkPalette.kt").contains("if (cacheKey == resolvedKey) return@LaunchedEffect"))
        val immersive = source("CapsuleImmersiveContent.kt")
        assertTrue(immersive.contains("coverLoadRetry by remember(mediaMetadata.id, artworkTone.displayUrl)"))
        assertTrue(immersive.contains("key(mediaMetadata.id, artworkTone.displayUrl, coverLoadRetry)"))
        assertTrue(immersive.contains("if (coverLoadRetry < 1) coverLoadRetry++"))
        assertTrue(immersive.contains("decodedCoverPainter = success.painter"))
        assertTrue(immersive.contains("visibleArtworkRebind"))
        assertTrue(immersive.contains("onError = { error ->"))
        assertTrue(immersive.contains(".size(960, 960)"))
        assertTrue(immersive.contains("decodedCoverPainter?.let { ready ->"))
        assertTrue(immersive.contains("delay(if (decodedCoverPainter != null) 1_000L else 3_000L)"))
    }

    @Test fun lightAndCosmoKeepImageFallbackLocalToTheirOwnArtwork() {
        val full = source("CapsulePlayerContent.kt")
        assertTrue(full.contains("val originalArtworkUrl = mediaMetadata.thumbnailUrl"))
        assertTrue(full.contains("model = imageRequest"))
        assertTrue(full.contains(".size(960, 960)"))
        assertTrue(full.contains(".crossfade(false)"))
        assertTrue(full.contains("artworkRebind"))
        assertTrue(full.contains("retainedArtworkPainter = success.painter"))
        assertTrue(full.contains("retainedArtworkPainter?.let { ready ->"))
        assertTrue(full.contains("delay(if (retainedArtworkPainter != null) 1_000L else 3_000L)"))
        assertTrue(full.contains("onError = {"))
        assertTrue(full.contains("artworkFailed = true"))
        assertTrue(full.contains("if (artworkFailed && retainedArtworkPainter == null)"))
        // The animation and gradient extraction algorithms must not be replaced.
        assertTrue(full.contains("CapsuleVideoArtworkTransition("))
    }
}
