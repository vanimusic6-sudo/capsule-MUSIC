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

    @Test fun immersiveArtworkHasNoGraphiteTargetOrPressedWhiteOverlay() {
        val immersive = source("com/nikhil/yt/ui/player/CapsuleImmersiveContent.kt")
        // The old two-coroutine readiness gate, tall ray projection and
        // independent audio fade must never return.
        assertTrue(immersive.contains("matchedPaint = withContext(Dispatchers.Default)"))
        assertTrue(immersive.contains("val pairReady = frame != null && matchedPaint != null"))
        assertTrue(immersive.contains("matchedPaint?.first?.let { bitmap ->"))
        assertTrue(immersive.contains("matchedPaint?.second?.let { overlay ->"))
        assertTrue(immersive.contains("if (!pairReady)"))
        assertTrue(immersive.contains("artworkTone.preview"))
        assertTrue(immersive.contains("onFirstTextureReady = { videoTextureReady = true }"))
        assertTrue(immersive.contains("if (liveField != null && matchedPaint != null)"))
        assertFalse(immersive.contains("pageTextureReadyFor"))
        assertFalse(immersive.contains("preparedPhotoOverlay"))
        assertFalse(immersive.contains("alpha = if (frame == null || pairReady) 1f else 0f"))
        val player = source("com/nikhil/yt/ui/player/Player.kt")
        assertTrue(player.contains("val warmedImmersiveTone ="))
        assertTrue(player.contains("enabled = onScreen && !enrichedMetadata?.thumbnailUrl.isNullOrBlank()"))
        assertTrue(player.contains("warmedImmersiveTone = warmedImmersiveTone"))
        val field = source("com/nikhil/yt/ui/player/CapsuleImmersiveField.kt")
        assertTrue(field.contains("fun capsuleDominantEdgeColor("))
        assertTrue(field.contains("fun capsuleImmersiveBleedEnd("))
        assertTrue(field.contains("EDGE_WIDTH = 144"))
        assertFalse(field.contains("cubicWeights("))
        assertFalse(field.contains("sin(pageY"))
        val backdrop = source("com/nikhil/yt/ui/player/CapsuleVideoAmbientBackdrop.kt")
        assertTrue(backdrop.contains("onFirstTextureReady"))
        assertTrue(backdrop.contains("blend.animateTo("))

        val floor = source("com/nikhil/yt/ui/player/CapsuleImmersiveFloor.kt")
        assertTrue(floor.contains("val rawEdge = image.immersiveBottomBackground(artworkAspect)"))
        assertTrue(floor.contains("val colorField = image.capsuleColorField(artworkAspect)"))
        assertFalse(floor.contains("val edgeBands = image.immersiveBottomEdgeBands("))
        assertTrue(floor.contains("val edgeBands: List<Color> = emptyList()"))
        assertTrue(floor.contains("val preview: ImageBitmap? = null"))
        assertTrue(floor.contains("preview = image.asImageBitmap()"))
        assertTrue(floor.contains("fun Bitmap.immersiveBottomBackground(artworkAspect: Float)"))
        assertFalse(floor.contains("immersiveArtworkBackground("))
        assertTrue(floor.contains("nearWhiteBackground = nearWhite"))
        // Non-white artwork deliberately keeps the original gentle 10% darkening;
        // only near-white artwork uses the stronger muted-accent algorithm.
        // Earlier regression text assumed ALL artwork used the same muted accent,
        // contradicting the now-restored baseline and failing this suite.
        assertTrue(floor.contains("accent = if (nearWhite) {"))
        assertTrue(floor.contains("rawEdge.immersiveMutedAccent()"))
        assertTrue(floor.contains("lerp(background, Color.Black, 0.10f)"))
        assertFalse(floor.contains("accent = background.immersiveMutedAccent()"))
        assertTrue(floor.contains("fun Color.immersiveMutedAccent()"))
        assertTrue(floor.contains("comfortableImmersiveColor"))
        assertTrue(floor.contains("0.44f"))

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
