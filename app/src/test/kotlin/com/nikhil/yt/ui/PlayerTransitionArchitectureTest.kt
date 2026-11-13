package com.nikhil.yt.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Structural safeguards for subtle regressions Robolectric cannot simulate:
 * Material3 OS predictive Back is NOT the same thing as holding an action tile,
 * and two independent full-screen masks must not be reintroduced for Lyrics.
 */
class PlayerTransitionArchitectureTest {
    private fun source(path: String): String =
        listOf(File("src/main/kotlin/$path"), File("app/src/main/kotlin/$path"))
            .firstOrNull(File::isFile)?.readText() ?: error("Missing $path")

    @Test fun allMenusKeepNativeSwipesButAvoidPlatformPredictiveBackScaling() {
        val menu = source("com/nikhil/yt/ui/component/BottomSheetMenu.kt")
        assertTrue(menu.contains("ModalBottomSheet("))
        assertTrue(menu.contains("skipPartiallyExpanded = false"))
        assertTrue(menu.contains("PredictiveBackHandler("))
        assertTrue(menu.contains("shouldDismissOnBackPress = false"))
        assertTrue(menu.contains("PredictiveBackHandler(enabled = !state.dismissRequested)"))
        assertTrue(menu.contains("LocalCapsuleMenuPressGuard provides true,"))
        assertTrue(menu.contains("LocalOverscrollFactory provides null,"))
        assertTrue(menu.contains("state.completeAnimatedDismiss(generation)"))
        assertTrue(menu.contains("observedGeneration != generation"))
        assertTrue(menu.contains("progress.collect { }"))
        assertFalse(menu.contains("\n    Dialog("))
        assertFalse(menu.contains("rememberDraggableState"))
    }

    @Test fun immersiveArtworkUsesOnePhotographicBackgroundAndNoPressedWhiteOverlay() {
        val immersive = source("com/nikhil/yt/ui/player/CapsuleImmersiveContent.kt")
        // The old two-coroutine readiness gate, tall ray projection and
        // independent audio fade must never return.
        assertTrue(immersive.contains("matchedPaint = withContext(Dispatchers.Default)"))
        assertTrue(immersive.contains("matchedPaint?.let { bitmap ->"))
        assertTrue(immersive.contains("if (frame != null) ImmersiveAudioScene("))
        assertFalse("No second tinted photo overlay", immersive.contains("matchedPaint?.second?.let"))
        assertFalse("A raw thumbnail cannot flash during foreground restoration",
            immersive.contains("model = mediaMetadata.thumbnailUrl"))
        assertFalse("The photo must not be faded separately from its track scene",
            immersive.contains("alpha = capsuleImmersiveArtworkOpacity("))
        assertFalse("The palette miniature is not an outgoing fullscreen photo",
            immersive.contains("painter = frame.preview") || immersive.contains("bitmap = frame.preview"))
        assertTrue(immersive.contains("painter = frame.painter"))
        assertTrue(immersive.contains("painter = leaving.painter"))
        assertTrue(immersive.contains("sharedProgress = sharedVideoProgress"))
        assertTrue(immersive.contains("capsuleImmersiveVideoBackplateAlpha(sharedVideoProgress.value)"))
        assertFalse(immersive.contains("pageTextureReadyFor"))
        assertFalse(immersive.contains("preparedPhotoOverlay"))
        assertFalse(immersive.contains("alpha = if (frame == null || pairReady) 1f else 0f"))
        val player = source("com/nikhil/yt/ui/player/Player.kt")
        assertTrue(player.contains("val warmedImmersiveTone ="))
        assertTrue(player.contains("enabled = onScreen && !enrichedMetadata?.thumbnailUrl.isNullOrBlank()"))
        assertTrue(player.contains("warmedImmersiveTone = warmedImmersiveTone"))
        val backdrop = source("com/nikhil/yt/ui/player/CapsuleVideoAmbientBackdrop.kt")
        assertTrue(backdrop.contains("onFirstTextureReady"))
        assertTrue(backdrop.contains("blend.animateTo("))
        assertTrue(backdrop.contains("capsuleVideoSpatialBitmap("))

        val floor = source("com/nikhil/yt/ui/player/CapsuleImmersiveFloor.kt")
        assertTrue(floor.contains("val visibleField = image.capsuleImmersiveVisibleField(artworkAspect, landscape)"))
        assertFalse(floor.contains("val rawEdge = capsuleDominantEdgeColor(visibleTail)"))
        assertFalse(immersive.contains("immersiveSpatialFieldBitmap("))
        assertFalse(immersive.contains("ImmersiveSoftArtwork("))
        assertFalse(floor.contains("val edgeBands = image.immersiveBottomEdgeBands("))
        assertTrue(floor.contains("val edgeBands: List<Color> = emptyList()"))
        assertTrue(floor.contains("val preview: ImageBitmap? = null"))
        assertTrue(floor.contains("preview = image.copy(Bitmap.Config.ARGB_8888, false).asImageBitmap()"))
        assertTrue(floor.contains("fun Bitmap.immersiveBottomBackground(artworkAspect: Float)"))
        assertFalse(floor.contains("immersiveArtworkBackground("))
        assertTrue(floor.contains("nearWhiteBackground = nearWhite"))
        // Palette and crop behaviour are covered with native bitmap inputs in
        // CapsuleImmersiveImageBackdropTest, not obsolete extraction strings.

        val videoArtwork = source("com/nikhil/yt/ui/player/CapsuleVideoArtworkTransition.kt")
        assertTrue(videoArtwork.contains("interactionSource = artworkTapInteraction"))
        assertTrue(videoArtwork.contains("indication = null"))
        assertTrue(videoArtwork.contains("onClick = onArtworkClick"))
    }

    @Test fun lyricsMovesOneContiguousCanvasAndOpticsFollowPosition() {
        val player = source("com/nikhil/yt/ui/player/Player.kt")
        assertEquals(1, Regex("then\\(lyricsFrame\\)").findAll(player).count())
        assertTrue(player.contains("capsuleLyricsForegroundOpacity(sheetOffset.value)"))
        assertTrue(player.contains("scaleX = 1f"))
        assertTrue(player.contains("scaleY = 1f"))
        assertEquals(
            "Both heavy composables must stay screen-fixed beneath ONE mask",
            2,
            Regex("translationY = -displacement").findAll(player).count(),
        )
        assertTrue(player.contains("topStart = LyricsFrameCornerRadius"))
        assertTrue(player.contains("instantBackdrop = true"))
        assertFalse(player.contains("lipShade"))
        val backdrop = source("com/nikhil/yt/ui/player/CapsuleLyricsContent.kt")
        assertTrue(backdrop.contains("if (instantBackdrop)"))
        assertTrue(backdrop.contains("renderBackdrop(lyricsBackdrop)"))
        assertFalse(player.contains("val recoil = if (showLyrics)"))
        assertFalse(player.contains("capsuleLyricsRetreat("))
        assertFalse(player.contains("foregroundOpacity.animateTo"))
        assertFalse(player.contains("LyricsForegroundOpenDelayMillis"))
    }

    @Test
    fun playerSheetOpensOnTapAndOnlyAndroidBackClosesIt() {
        val player = source("com/nikhil/yt/ui/player/Player.kt")
        val bottomSheet = source("com/nikhil/yt/ui/component/BottomSheet.kt")
        assertTrue(player.contains("gesturesEnabled = false,"))
        assertTrue(player.contains("compactSwipeDismissEnabled = true,"))
        assertTrue(player.contains("allowSwipeDismiss = false,"))
        assertTrue(player.contains("BackHandler(enabled = state.isExpandedOrExpanding)"))
        assertTrue(bottomSheet.contains("onClick = state::expandSoft"))
        assertTrue("Expanded player must swallow taps outside controls",
            bottomSheet.contains(".pointerInput(renderExpandedContent)"))
    }

    @Test
    fun hidingInlineLyricDoesNotPersistTemporaryArtworkHeight() {
        val content = source("com/nikhil/yt/ui/player/CapsulePlayerContent.kt")
        assertTrue(content.contains("val safeArtworkHeightScale ="))
        assertTrue(content.contains("heightScale = safeArtworkHeightScale,"))
        assertFalse(content.contains("onArtworkHeightScaleChange(safeArtworkHeightScale)"))
        // Manual editor resize is still persisted as before.
        assertTrue(content.contains("onArtworkHeightScaleChange(heightScale)"))
    }
}
