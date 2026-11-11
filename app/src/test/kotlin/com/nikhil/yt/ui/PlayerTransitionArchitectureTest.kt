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
        assertFalse(immersive.contains("gradientAlpha"))
        assertFalse(immersive.contains("visibleArtworkRebind"))
        assertFalse(immersive.contains("scaleX = if (frame.landscape)"))
        assertTrue(immersive.contains("prepareImmersiveFrame("))
        assertTrue(immersive.contains("image.asAndroidBitmap().capsuleColorField(artworkAspect)"))
        assertTrue(immersive.contains("frame = readyFrame"))
        assertTrue(immersive.contains("fixedImmersiveArtwork = true"))
        val player = source("com/nikhil/yt/ui/player/Player.kt")
        assertTrue(player.contains("val warmedImmersiveTone ="))
        assertTrue(player.contains("enabled = onScreen && !enrichedMetadata?.thumbnailUrl.isNullOrBlank()"))
        val sampler = source("com/nikhil/yt/ui/player/CapsuleImmersiveFloor.kt")
        assertTrue(sampler.contains("preview = image.copy(Bitmap.Config.ARGB_8888, false).asImageBitmap()"))
        assertTrue(sampler.contains("ARTWORK_SAMPLE_SIZE = 960"))
        val backdrop = source("com/nikhil/yt/ui/player/CapsuleVideoAmbientBackdrop.kt")
        assertTrue(backdrop.contains("blend.animateTo("))

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
