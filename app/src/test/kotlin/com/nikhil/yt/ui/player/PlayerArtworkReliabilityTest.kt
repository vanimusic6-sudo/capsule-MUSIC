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


    @Test fun artworkPaletteEntersWithoutSuddenColourSwapOnNormalPlayer() {
        val player = source("Player.kt")
        assertTrue("Only colour-backed styles should tween on a late bitmap",
            player.contains("animatePalette = effectivePlayerDesign != CapsulePlayerDesign.IMMERSIVE"))
        assertTrue("Spatial field and palette must stay atomic",
            player.contains("playerBackground != PlayerBackgroundStyle.SCENE_LIGHT"))
        assertTrue("The star's black-cover threshold must not flip midway through a colour tween",
            player.contains("playerBackground != PlayerBackgroundStyle.CAPSULE_STAR"))
        assertTrue("The lyric morph freezes the actually displayed gradient",
            player.contains("frozenCardPalette = transitioningPalette.toList()"))
        assertTrue("Freeze must end when the opening animation ends, not when Lyrics closes",
            player.contains("Freeze only the geometrical expansion"))
        assertTrue(player.contains("playerArtworkReady = artworkScene.ready"))
        assertTrue(player.contains("colors = transitioningPalette"))
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
        // Immersion now preloads while MINI is still visible so a rapid
        // expansion cannot expose a blank image slot.
        assertTrue(source("Player.kt").contains("val warmedImmersiveTone ="))
        assertTrue(source("Player.kt").contains("enabled = onScreen && !enrichedMetadata?.thumbnailUrl.isNullOrBlank()"))
        assertTrue(source("Player.kt").contains("warmedImmersiveTone = warmedImmersiveTone"))
        assertTrue(source("Player.kt").contains("refreshOnOpen = state.isExpandedOrExpanding"))
        assertTrue(source("CapsuleImmersiveFloor.kt").contains("if (refreshOnOpen && resolvedKey == key && resolved?.ready != true)"))
        assertTrue(source("CapsuleImmersiveFloor.kt").contains("LaunchedEffect(key, sourceUrl, enabled, failureRetry)"))
        assertTrue(source("Player.kt").contains("refreshOnOpen = state.isExpandedOrExpanding"))
        assertTrue(source("CapsuleArtworkPalette.kt").contains("refreshOnOpen,"))
        assertTrue(source("CapsuleArtworkPalette.kt").contains("if (cacheKey == resolvedKey) return@LaunchedEffect"))
        val immersive = source("CapsuleImmersiveContent.kt")
        assertTrue(immersive.contains("coverLoadRetry by remember(mediaMetadata.id, artworkTone.displayUrl)"))
        assertTrue(immersive.contains("key(mediaMetadata.id, artworkTone.displayUrl, coverLoadRetry)"))
        assertTrue(immersive.contains("if (coverLoadRetry < 1) coverLoadRetry++"))
        assertTrue(immersive.contains("decodedCoverPainter = success.painter"))
        assertTrue(immersive.contains("onError = { error ->"))
        assertTrue(immersive.contains(".size(960, 960)"))
        assertTrue("Full-size decode must complete before committing the next scene",
            immersive.contains("decodedCoverPainter != null"))
        assertTrue(immersive.contains("val readyPainter = decodedCoverPainter ?: return@LaunchedEffect"))
        assertTrue(immersive.contains("delay(8_000L)"))
    }

    @Test fun preloaderUsesShortActualDebounceRatherThanOldSecondLongTimer() {
        val code = java.io.File("src/main/kotlin/com/nikhil/yt/ui/player/CapsuleMediaPreload.kt")
            .takeIf { it.exists() }
            ?: java.io.File("app/src/main/kotlin/com/nikhil/yt/ui/player/CapsuleMediaPreload.kt")
        val preloader = code.readText()
        assertTrue(preloader.contains("CAPSULE_VISUAL_PREFETCH_SETTLE_MS = 420L"))
        assertTrue(preloader.contains("delay(CAPSULE_VISUAL_PREFETCH_SETTLE_MS)"))
        assertFalse(preloader.contains("CAPSULE_VISUAL_PREFETCH_SETTLE_MS = 1_200L"))
    }

    @Test fun decodedArtworkAndLightMeasurementsSurviveWhileHiddenWorkSleeps() {
        val player = source("Player.kt")
        val content = source("CapsulePlayerContent.kt")
        assertTrue(player.contains("val decodedArtworkCache = remember {"))
        assertTrue(player.contains("decodedArtworkCache = decodedArtworkCache,"))
        assertTrue(content.contains("decodedArtworkCache: MutableMap<String, Pair<String, Painter>>"))
        assertFalse(content.contains("val decodedArtworkCache = remember"))
        assertTrue(content.contains("while (decodedArtworkCache.size > 3)"))
        // Keep expensive visual work stopped when the app is backgrounded.
        assertTrue(content.contains("if (!open && expansionProgress <= 0.001f)"))
        assertTrue(content.contains("val visible = open && onScreen && motionActive"))
        assertTrue(content.contains("active = active && visible"))
        assertTrue(content.contains("if (visible && (retainedArtworkPainter"))
        assertTrue(content.contains("if (!visible || !artworkLoading"))
        assertTrue(content.contains("collectAsStateWithLifecycle"))
        assertTrue(player.contains("skipArtworkPrefetch = state.shouldLayerAboveCollapsedChrome"))
        val preload = source("CapsuleMediaPreload.kt")
        assertTrue(preload.contains("if (skipArtworkPrefetch) return@LaunchedEffect"))
    }

    @Test fun immersiveForegroundUsesRetainedFullSizeArtworkAndPreparedBackdrop() {
        val content = source("CapsuleImmersiveContent.kt")
        val player = source("Player.kt")
        assertTrue(player.substringAfter("CapsuleImmersiveContent(")
            .substringBefore("CapsulePlayerContent(").contains("decodedArtworkCache = decodedArtworkCache"))
        assertTrue(content.contains("decodedArtworkCache[mediaMetadata.id]"))
        assertTrue(content.contains("it.first == artworkTone.displayUrl"))
        assertTrue(content.contains("mutableStateOf(restoredFrame)"))
        assertTrue(content.contains("entryScene?.frame"))
        assertTrue(content.contains("sceneMemory.prepared = ImmersivePreparedScene"))
        assertTrue(content.contains("val currentSceneReady = frame?.key == visualKey && matchedPaint != null"))
        assertTrue(content.contains("sceneAlpha = { sceneRevealAlpha.value }"))
        assertFalse("No full-page opacity sweep on each track", content.contains("gradientAlpha.animateTo("))
        assertTrue(content.contains("mutableStateOf(immersivePaintCache[paintKey] ?: entryScene"))
        assertTrue(player.contains("val immersiveSceneMemory = remember { ImmersiveSceneMemory() }"))
        assertTrue(content.contains("decodedArtworkCache[mediaMetadata.id] = url to success.painter"))
        assertFalse(content.contains("bitmap = leaving.preview"))
        assertFalse(content.contains("model = mediaMetadata.thumbnailUrl"))
    }

    @Test fun lightKeepsImageFallbackLocalToItsOwnArtwork() {
        val full = source("CapsulePlayerContent.kt")
        assertTrue(full.contains("val originalArtworkUrl = pinnedArtworkUrl"))
        assertTrue(full.contains("if (pinnedArtworkUrl == null) pinnedArtworkUrl = originalArtworkUrl"))
        assertTrue(full.contains("model = imageRequest"))
        assertTrue(full.contains(".size(960, 960)"))
        assertTrue(full.contains(".crossfade(false)"))
        assertTrue(full.contains("artworkRebind"))
        assertTrue(full.contains("retainedArtworkPainter = success.painter"))
        assertTrue(full.contains("CapsuleTrackArtworkHandoff("))
        assertFalse("Do not request a 360px preview in the full player",
            full.contains("thumbnailUrl = mediaMetadata.thumbnailUrl"))
        assertTrue(full.contains("delay(8_000L)"))
        assertTrue(full.contains("val fullArtworkCandidates ="))
        assertTrue(full.contains("capsuleFullPlayerArtworkCandidates(originalArtworkUrl)"))
        assertTrue(full.contains("capsulePlayerPosterLargeEnough("))
        assertFalse("Tiny preview must never be decoded as fullscreen artwork",
            full.contains("previewRequest") || full.contains(".size(256, 256)") ||
            full.contains("useOriginalArtwork"))
        val handoff = source("CapsuleTrackArtworkHandoff.kt")
        assertTrue("Only the current song's fully decoded cover is rendered",
            handoff.contains("key(mediaId)") && handoff.contains("if (decodedPainter != null)"))
        assertFalse("No separate low-resolution image request",
            handoff.contains("AsyncImage") || handoff.contains("previewRequest") ||
                handoff.contains("CAPSULE_PREVIEW_ARRIVAL_MS"))
        assertTrue("Keep one brief high-resolution arrival, never two stages",
            handoff.contains("CAPSULE_COVER_HANDOFF_MS = 205"))
        assertTrue(full.contains("onError = {"))
        assertTrue(full.contains("artworkFailed = true"))
        assertTrue(full.contains("if (artworkFailed && retainedArtworkPainter == null)"))
        // The animation and gradient extraction algorithms must not be replaced.
        assertTrue(full.contains("CapsuleVideoArtworkTransition("))
    }
    @Test fun noLowResolutionCoverIsEverShownBeforeFinalArtwork() {
        val handoff = source("CapsuleTrackArtworkHandoff.kt")
        val immersion = source("CapsuleImmersiveContent.kt")
        val sampler = source("CapsuleImmersiveFloor.kt")
        val preload = source("CapsuleMediaPreload.kt")
        assertFalse(handoff.contains("AsyncImage"))
        assertFalse(handoff.contains("360, 360"))
        assertFalse(immersion.contains("CapsuleImmersiveCurrentTrackPreview("))
        assertFalse(immersion.contains(".size(640, 640)"))
        assertTrue(sampler.contains("ARTWORK_SAMPLE_SIZE = 960"))
        assertTrue(immersion.contains("coverAndGradientReady && frame?.painter != null"))
        assertTrue(preload.contains("capsuleFullPlayerArtworkCandidates(mediaMetadata?.thumbnailUrl).firstOrNull()"))
    }

    @Test fun lyricsAndFullPlayerUseOnePaletteAndOneBackgroundClock() {
        val player = source("Player.kt")
        val lyrics = source("CapsuleLyricsContent.kt")
        val compact = source("CapsuleInlineLyricsCard.kt")
        assertTrue(player.contains("playerArtworkField = artworkScene.field"))
        assertTrue(lyrics.contains("if (parentSuppliesScene) playerArtworkColors else localArtworkScene.colors"))
        assertTrue(lyrics.contains("artworkField = artworkField"))
        assertTrue(lyrics.contains("sharedAnimationTime = backdropAnimationTime"))
        assertFalse(lyrics.contains("tween(1150"))
        assertFalse(compact.contains("tween(1400"))
        assertFalse(compact.contains("animateColorAsState"))
    }

}
