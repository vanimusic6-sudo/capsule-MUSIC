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

    @Test fun playerMenuKeepsNativeSwipesButAvoidsPlatformPredictiveBackScaling() {
        val menu = source("com/nikhil/yt/ui/component/BottomSheetMenu.kt")
        assertTrue(menu.contains("ModalBottomSheet("))
        assertTrue(menu.contains("skipPartiallyExpanded = false"))
        assertTrue(menu.contains("PredictiveBackHandler("))
        assertTrue(menu.contains("shouldDismissOnBackPress = !physical"))
        assertTrue(menu.contains("progress.collect { }"))
        assertFalse(menu.contains("\n    Dialog("))
        assertFalse(menu.contains("rememberDraggableState"))
    }

    @Test fun immersiveArtworkHasNoGraphiteTargetOrPressedWhiteOverlay() {
        val immersive = source("com/nikhil/yt/ui/player/CapsuleImmersiveContent.kt")
        assertTrue(immersive.contains(".background(pageBackground)"))
        assertTrue(immersive.contains("immersiveContinuousPageStops("))
        assertTrue(immersive.contains("immersiveContinuousCoverStops("))
        assertTrue(immersive.contains("immersiveOriginalFadeProfile("))
        assertTrue(immersive.contains("immersiveOriginalSeamColor("))
        assertTrue(immersive.contains("if (nearWhite)"))
        assertTrue(immersive.contains("muted = pageFloor"))
        assertTrue(immersive.contains("val immersiveInk = ImmersiveControlInk"))
        assertFalse(immersive.contains("immersiveDeepTone("))
        assertTrue(immersive.contains("endY = pageHeightPx"))
        assertTrue(immersive.contains("endY = coverHeightPx"))
        assertFalse(immersive.contains("immersiveStagedFloorStops("))
        assertFalse(immersive.contains("immersiveCoverOverlayStops("))
        assertFalse(immersive.contains("ImmersiveSmokyGraphite"))
        assertFalse(immersive.contains("immersiveInkForFloor("))
        assertFalse(immersive.contains(".background(seamColor)"))

        val floor = source("com/nikhil/yt/ui/player/CapsuleImmersiveFloor.kt")
        assertTrue(floor.contains("val rawEdge = image.immersiveBottomBackground(artworkAspect)"))
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
}
