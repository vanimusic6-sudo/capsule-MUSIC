package com.nikhil.yt.ui.player

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import com.nikhil.yt.playback.video.CapsulePlaybackMode
import com.nikhil.yt.playback.video.CapsuleVideoPlaybackState
import com.nikhil.yt.playback.video.CapsuleVideoPhase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CapsuleVideoAmbientTest {
    @Test fun bufferingAndRecoveryKeepTheLastVideoPaletteVisible() {
        val initial = CapsuleVideoPlaybackState(
            preferredMode = CapsulePlaybackMode.VIDEO,
            mode = CapsulePlaybackMode.VIDEO,
            mediaId = "song", phase = CapsuleVideoPhase.PLAYING,
            firstFrameReady = true,
        )
        assertTrue(capsuleVideoAmbientShouldStayVisible(initial, "song", true))
        assertTrue(capsuleVideoAmbientShouldStayVisible(
            initial.copy(phase = CapsuleVideoPhase.BUFFERING, firstFrameReady = false),
            "song", true, everPresentedFrame = true))
        assertTrue(capsuleVideoAmbientShouldStayVisible(
            initial.copy(phase = CapsuleVideoPhase.RECOVERING, firstFrameReady = false),
            "song", true, everPresentedFrame = true))
        assertFalse("An old saved VIDEO palette must not show before the new first frame",
            capsuleVideoAmbientShouldStayVisible(
                initial.copy(phase = CapsuleVideoPhase.PREPARING, firstFrameReady = false),
                "song", true))
        assertFalse("A rebuffered clip without any presented frame must stay on AUDIO colours",
            capsuleVideoAmbientShouldStayVisible(
                initial.copy(phase = CapsuleVideoPhase.BUFFERING, firstFrameReady = false),
                "song", true))
        assertFalse(capsuleVideoAmbientShouldStayVisible(
            initial.copy(preferredMode = CapsulePlaybackMode.AUDIO), "song", true))
        assertFalse(capsuleVideoAmbientShouldStayVisible(initial, "next", true))
        assertFalse(capsuleVideoAmbientShouldStayVisible(initial, "song", false))
    }

    @Test fun rebufferKeepsLastSampleWithoutResettingTheScene() {
        val memory = CapsuleVideoAmbientCaptureMemory()
        val firstField = CapsuleColorField(20, 16, List(320) { androidx.compose.ui.graphics.Color.Blue })
        val nextField = CapsuleColorField(20, 16, List(320) { androidx.compose.ui.graphics.Color.Red })
        val first = CapsuleVideoAmbientFrame("song", capsuleBandsFromField(firstField), firstField)
        val second = CapsuleVideoAmbientFrame("song", capsuleBandsFromField(nextField), nextField)
        assertTrue(memory.shouldPublish(first))
        assertFalse(memory.shouldPublish(first))
        // Buffering only restarts the capture coroutine; it retains memory.
        assertTrue(memory.shouldPublish(second))
        assertFalse(memory.shouldPublish(second))
    }

    @Test fun subtleContinuousVideoColourChangeIsPublishedAtLiveCadence() {
        val memory = CapsuleVideoAmbientCaptureMemory()
        val original = CapsuleColorField(20, 16,
            List(320) { androidx.compose.ui.graphics.Color(0.45f, 0.3f, 0.5f) })
        val brighter = CapsuleColorField(20, 16,
            List(320) { androidx.compose.ui.graphics.Color(0.465f, 0.3f, 0.5f) })
        assertTrue(memory.shouldPublish(CapsuleVideoAmbientFrame(
            "track", capsuleBandsFromField(original), original)))
        assertTrue("Gradual scene changes must not be mistaken for static frames",
            memory.shouldPublish(CapsuleVideoAmbientFrame(
                "track", capsuleBandsFromField(brighter), brighter)))
        assertFalse(memory.shouldPublish(CapsuleVideoAmbientFrame(
            "track", capsuleBandsFromField(brighter), brighter)))
    }

    @Test fun liveFrameKeepsBlueAndRedSidesSeparate() {
        val bitmap = Bitmap.createBitmap(48, 28, Bitmap.Config.ARGB_8888)
        for (y in 0 until 28) for (x in 0 until 48) {
            bitmap.setPixel(x, y, if (x < 24) AndroidColor.rgb(219, 55, 65)
                else AndroidColor.rgb(35, 74, 214))
        }
        val sampled = capsuleVideoAmbientFrameFromBitmap(bitmap, "song")
        assertNotNull(sampled)
        val bands = requireNotNull(sampled).bands
        assertTrue(bands.first().red > bands.first().blue)
        assertTrue(sampled.field.width == CAPSULE_FIELD_COLUMNS)
        assertTrue(sampled.field.height == CAPSULE_FIELD_ROWS)
        assertTrue(sampled.field.cell(0, 0).red > sampled.field.cell(0, 0).blue)
        assertTrue(bands.last().blue > bands.last().red)
        assertTrue(capsuleVideoAmbientChanged(null, bands))
        assertFalse(capsuleVideoAmbientChanged(bands, bands))
        bitmap.recycle()
    }

    @Test fun interruptedVideoAmbientTransitionContinuesFromTheVisibleColour() {
        val warm = listOf(androidx.compose.ui.graphics.Color.Red, androidx.compose.ui.graphics.Color.Yellow)
        val cool = listOf(androidx.compose.ui.graphics.Color.Blue, androidx.compose.ui.graphics.Color.Cyan)
        val partlyMixed = capsuleAmbientPaletteAt(warm, cool, 0.25f)
        val interrupted = capsuleAmbientPaletteAt(partlyMixed, cool, 0f)
        assertTrue(kotlin.math.abs(partlyMixed[0].red - interrupted[0].red) < 0.0001f)
        assertTrue(partlyMixed[0].red > cool[0].red)
        assertTrue(VIDEO_AMBIENT_CAPTURE_INTERVAL_MS in 180L..260L)
        assertTrue("Live light must overlap adjacent samples, smoothing moving video rather than stepping",
            VIDEO_AMBIENT_BLEND_DURATION_MS in 380..520)
        assertTrue(capsuleVideoAmbientCaptureIntervalMs(0) < capsuleVideoAmbientCaptureIntervalMs(3))
        assertTrue(capsuleVideoAmbientCaptureIntervalMs(3) < capsuleVideoAmbientCaptureIntervalMs(8))
        assertTrue(capsuleVideoAmbientCaptureIntervalMs(8) <= 700L)
    }

    @Test fun blackOrInvalidVideoFramesDoNotReplaceExistingPalette() {
        val black = Bitmap.createBitmap(48, 28, Bitmap.Config.ARGB_8888)
        black.eraseColor(AndroidColor.BLACK)
        assertNull(capsuleVideoAmbientFrameFromBitmap(black, "song"))
        assertNull(capsuleVideoAmbientFrameFromBitmap(black, ""))
        black.recycle()
    }
}
