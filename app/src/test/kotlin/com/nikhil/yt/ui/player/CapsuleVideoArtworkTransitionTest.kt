package com.nikhil.yt.ui.player

import android.app.Application

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.nikhil.yt.playback.video.CapsulePlaybackMode
import com.nikhil.yt.playback.video.CapsuleVideoPhase
import com.nikhil.yt.playback.video.CapsuleVideoPlaybackState
import com.nikhil.yt.playback.video.hasStableVideoBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w400dp-h800dp")
class CapsuleVideoArtworkTransitionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun noVideoDecoderOrBlackWindowBeforeArtworkLoadsInAudioMode() {
        val needsSurface = mutableStateOf(false)
        var videoCompositionCount = 0
        compose.setContent {
            CapsuleVideoArtworkTransition(
                presentingVideo = false,
                needsSurface = needsSurface.value,
                modifier = Modifier.size(320.dp, 280.dp),
                fillArtworkSlot = true,
                video = {
                    videoCompositionCount++
                    Box(Modifier.fillMaxSize())
                },
                // Mirrors the empty async-cover placeholder.
                artwork = { Box(Modifier.fillMaxSize()) },
            )
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("AUDIO placeholder must not mount VIDEO", 0, videoCompositionCount) }
        compose.runOnIdle { needsSurface.value = true }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue("VIDEO preparation still mounts the surface", videoCompositionCount > 0) }
    }

    @Test fun knownUnavailableAndCooldownRemainStableAcrossAudioVideoTaps() {
        val state = CapsuleVideoPlaybackState(
            mode = CapsulePlaybackMode.AUDIO,
            preferredMode = CapsulePlaybackMode.AUDIO,
            mediaId = "song",
            phase = CapsuleVideoPhase.UNAVAILABLE,
        )
        assertTrue(state.hasStableVideoBlock("song", 1000L))
        assertEquals(false, state.hasStableVideoBlock("next", 1000L))
        assertTrue(state.copy(phase = CapsuleVideoPhase.REQUEST_ERROR, requestBlockedUntilMs = 3000L)
            .hasStableVideoBlock("song", 1000L))
        assertEquals(false, state.copy(phase = CapsuleVideoPhase.REQUEST_ERROR, requestBlockedUntilMs = 3000L)
            .hasStableVideoBlock("song", 3000L))
        assertEquals(false, shouldShowVideoLoading(state.copy(
            preferredMode = CapsulePlaybackMode.VIDEO,
            phase = CapsuleVideoPhase.RESOLVING,
            requestBlockedUntilMs = 3000L,
        ), nowMs = 1000L))
    }

    @Test fun jellyStretchIsBoundedReversibleAndInvisibleAtBothEndpoints() {
        assertEquals(0f, immersiveJellyStretch(0f, 0.4f), 0.0001f)
        assertEquals(0f, immersiveJellyStretch(1f, 1.08f), 0.0001f)
        val opening = immersiveJellyStretch(0.45f, 0.64f)
        val closing = immersiveJellyStretch(0.45f, 0.26f)
        assertTrue("leading edge produces a gentle jelly pull", opening > 0.01f)
        assertTrue("reverse motion pulls in the opposite direction", closing < -0.01f)
        assertTrue(opening <= 0.042f)
        assertTrue(closing >= -0.042f)
        // The previous clamp to 0..1 erased physical rebound: once the body
        // passed the endpoint, its silhouette snapped flat while spring moved.
        assertTrue(immersiveJellyStretch(1.035f, 1.09f) > 0f)
        assertTrue(immersiveJellyStretch(-0.03f, -0.08f) < 0f)
        assertEquals(0f, immersiveJellyStretch(1f, 1f), 0.0001f)
        assertEquals(0f, immersiveJellyStretch(0f, 0f), 0.0001f)
        assertTrue(immersiveEdgeTension(0.50f, 0.80f) > 0f)
        assertTrue(immersiveEdgeTension(0.50f, 0.20f) < 0f)
    }

    @Test fun rendererSurvivesSpringZeroCrossingAndStopsOnlyAfterRecoil() {
        val presenting = mutableStateOf(true)
        val needed = mutableStateOf(true)
        var attached = 0
        var detached = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CapsuleVideoArtworkTransition(
                presentingVideo = presenting.value,
                needsSurface = needed.value,
                motion = CapsuleVideoArtworkMotion.IMMERSIVE,
                modifier = Modifier.size(320.dp, 440.dp),
                fillArtworkSlot = true,
                video = { if (it) DisposableEffect(Unit) {
                    attached++
                    onDispose { detached++ }
                } },
                artwork = { Box(Modifier.fillMaxSize()) },
            )
        }
        compose.waitForIdle()
        compose.runOnIdle {
            presenting.value = false
            needed.value = false
        }
        // Several frames around the zero-crossing where a bouncy spring can
        // go slightly negative. Do not release/recreate the decoder between.
        repeat(30) {
            compose.mainClock.advanceTimeBy(16)
            compose.waitForIdle()
            if (it < 12) compose.runOnIdle { assertEquals(0, detached) }
        }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, attached)
            assertEquals(1, detached)
        }
    }

    @Test fun sharedBoundsChangeWidthAndHeightTogetherWithoutTwoStepStaging() {
        val coverWidth = 320
        val videoWidth = 348
        val coverHeight = 320
        val videoHeight = 196
        assertEquals(320f, sharedVideoMorphDimension(coverWidth, videoWidth, 0f), 0.001f)
        assertEquals(320f, sharedVideoMorphDimension(coverHeight, videoHeight, 0f), 0.001f)
        for (p in listOf(0.20f, 0.35f, 0.50f, 0.75f)) {
            val width = sharedVideoMorphDimension(coverWidth, videoWidth, p)
            val height = sharedVideoMorphDimension(coverHeight, videoHeight, p)
            assertTrue("width must be moving even at early progress", width > coverWidth)
            assertTrue("height must be moving even at early progress", height < coverHeight)
            assertEquals(
                "the axes should share one progress to prevent corner catch-up",
                (width - coverWidth) / (videoWidth - coverWidth),
                (height - coverHeight) / (videoHeight - coverHeight),
                0.001f,
            )
        }
        assertEquals(348f, sharedVideoMorphDimension(coverWidth, videoWidth, 1f), 0.001f)
        assertEquals(196f, sharedVideoMorphDimension(coverHeight, videoHeight, 1f), 0.001f)
    }

    @Test fun loadingIndicatorOnlyAppearsWhileAnUnpreparedClipLoads() {
        val loading = CapsuleVideoPlaybackState(
            preferredMode = CapsulePlaybackMode.VIDEO, phase = CapsuleVideoPhase.PREPARING,
        )
        assertTrue(shouldShowVideoLoading(loading))
        assertEquals(false, shouldShowVideoLoading(loading.copy(preparedVideoReady = true)))
        assertEquals(false, shouldShowVideoLoading(loading.copy(firstFrameReady = true)))
        assertEquals(false, shouldShowVideoLoading(loading.copy(phase = CapsuleVideoPhase.BUFFERING)))
    }

    @Test fun switchLabelsKeepTheirBoundsThroughRapidSelectionReversals() {
        val state = mutableStateOf(CapsuleVideoPlaybackState())
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CapsuleAudioVideoToggle(state.value, Color.White, true, {}, {}, lightStyle = true)
        }
        compose.waitForIdle()
        val audio = compose.onNodeWithText("audio").fetchSemanticsNode().boundsInRoot
        val video = compose.onNodeWithText("video").fetchSemanticsNode().boundsInRoot
        repeat(8) { index ->
            compose.runOnIdle {
                state.value = if (index % 2 == 0) CapsuleVideoPlaybackState(
                    preferredMode = CapsulePlaybackMode.VIDEO, mode = CapsulePlaybackMode.VIDEO,
                    phase = CapsuleVideoPhase.PLAYING, firstFrameReady = true,
                ) else CapsuleVideoPlaybackState()
            }
            compose.mainClock.advanceTimeBy(48)
            compose.waitForIdle()
            assertEquals(audio, compose.onNodeWithText("audio").fetchSemanticsNode().boundsInRoot)
            assertEquals(video, compose.onNodeWithText("video").fetchSemanticsNode().boundsInRoot)
        }
    }

    @Test fun lightSwitchAcceptsEveryTapDuringLoadingAndReversal() = verifyTaps(light = true, cosmo = false)
    @Test fun cosmoSwitchAcceptsEveryTapDuringLoadingAndReversal() = verifyTaps(light = false, cosmo = true)
    @Test fun plainSwitchAcceptsEveryTapDuringLoadingAndReversal() = verifyTaps(light = false, cosmo = false)

    private fun verifyTaps(light: Boolean, cosmo: Boolean) {
        val state = mutableStateOf(CapsuleVideoPlaybackState())
        var audioClicks = 0
        var videoClicks = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CapsuleAudioVideoToggle(state.value, Color.White, true,
                onAudioClick = { audioClicks++; state.value = CapsuleVideoPlaybackState() },
                onVideoClick = {
                    videoClicks++
                    state.value = CapsuleVideoPlaybackState(
                        preferredMode = CapsulePlaybackMode.VIDEO, phase = CapsuleVideoPhase.RESOLVING)
                },
                modifier = Modifier.size(240.dp, 48.dp), lightStyle = light, cosmoStyle = cosmo,
            )
        }
        repeat(10) {
            compose.onNodeWithText(if (light || cosmo) "video" else "VIDEO").performTouchInput { click() }
            compose.mainClock.advanceTimeBy(32)
            // A repeat tap while loading reaches the service's idempotent
            // handler, instead of the UI dropping input for an animation.
            compose.onNodeWithText(if (light || cosmo) "video" else "VIDEO").performTouchInput { click() }
            compose.onNodeWithText(if (light || cosmo) "audio" else "AUDIO").performTouchInput { click() }
            compose.mainClock.advanceTimeBy(32)
        }
        compose.runOnIdle {
            assertEquals(20, videoClicks)
            assertEquals(10, audioClicks)
            assertEquals(CapsulePlaybackMode.AUDIO, state.value.preferredMode)
        }
    }

    @Test fun lightKeepsThePreparingSurfaceWhenFirstFrameArrives() = verifyStableOutput(320, 280, true, 0)
    @Test fun cosmoKeepsThePreparingSurfaceWhenFirstFrameArrives() = verifyStableOutput(340, 420, false, 0)
    @Test fun immersiveKeepsThePreparingSurfaceWhenFirstFrameArrives() = verifyStableOutput(380, 440, true, 12, motion = CapsuleVideoArtworkMotion.IMMERSIVE)
    @Test fun enlargedLightWindowKeepsOneFixedNativeViewport() = verifyStableOutput(320, 280, true, 0, 1.08f)

    private fun verifyStableOutput(width: Int, height: Int, fill: Boolean, inset: Int, widthScale: Float = 1f,
        motion: CapsuleVideoArtworkMotion = CapsuleVideoArtworkMotion.SCULPTED) {
        val presenting = mutableStateOf(false)
        val needed = mutableStateOf(true)
        var attaches = 0
        var detaches = 0
        val measured = mutableListOf<IntSize>()
        var coverSize = IntSize.Zero
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CapsuleVideoArtworkTransition(
                presentingVideo = presenting.value,
                needsSurface = needed.value,
                modifier = Modifier.size(width.dp, height.dp),
                fillArtworkSlot = fill,
                videoSidePadding = inset.dp,
                videoWidthScale = widthScale,
                motion = motion,
                video = { active ->
                    if (active) DisposableEffect(Unit) {
                        attaches++
                        onDispose { detaches++ }
                    }
                    Box(Modifier.fillMaxSize().onSizeChanged { measured.add(it) })
                },
                artwork = { Box(Modifier.fillMaxSize().onSizeChanged { coverSize = it }) },
            )
        }
        compose.waitForIdle()
        val preparingSize = measured.single()
        assertTrue(preparingSize.width > 0 && preparingSize.height > 0)
        assertEquals(16f / 9f, preparingSize.width.toFloat() / preparingSize.height, 0.02f)
        if (widthScale > 1f) assertTrue("VIDEO may grow beyond the cover without resizing its decoder", preparingSize.width > coverSize.width)
        compose.runOnIdle { presenting.value = true }
        compose.mainClock.advanceTimeBy(120)
        compose.waitForIdle()
        assertEquals("first-frame readiness must not replace the output", 1, attaches)
        assertEquals(0, detaches)
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        assertEquals("native viewport must not resize during the reveal", listOf(preparingSize), measured)
        // Rapid readiness reversals interrupt the animation, not the surface lease.
        compose.runOnIdle { presenting.value = false }
        compose.mainClock.advanceTimeBy(80)
        compose.runOnIdle { presenting.value = true }
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        assertEquals(1, attaches)
        assertEquals(0, detaches)
        assertEquals(listOf(preparingSize), measured)
        // Only leaving the decoder lifecycle may detach it.
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { needed.value = false; presenting.value = false }
        compose.mainClock.advanceTimeBy(80)
        compose.runOnIdle { assertEquals("exit still remains attached during reverse motion", 0, detaches) }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("AUDIO must release the renderer", 1, detaches) }
    }
}
