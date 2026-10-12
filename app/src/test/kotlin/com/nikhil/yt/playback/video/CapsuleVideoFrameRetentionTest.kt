@file:Suppress("UnsafeOptInUsageError")
package com.nikhil.yt.playback.video

import android.app.Application
import android.graphics.Bitmap
import androidx.media3.common.FlagSet
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CapsuleVideoFrameRetentionTest {
    private class Clip {
        var play = false
        var id: String? = "song"
        val listeners = mutableListOf<Player.Listener>()
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
            when (method.name) {
                "getPlayWhenReady" -> play
                "getCurrentMediaItem" -> id?.let { MediaItem.Builder().setMediaId(it).build() }
                "addListener" -> { listeners += args!![0] as Player.Listener; null }
                "removeListener" -> { listeners -= args!![0] as Player.Listener; null }
                else -> null
            }
        } as Player
        fun event(flag: Int) = listeners.toList().forEach {
            it.onEvents(player, Player.Events(FlagSet.Builder().add(flag).build()))
        }
    }
    @Test fun aRecreatedSurfaceCannotReplaceThePausedFrameWithItsNextDecodedFrame() = runTest {
        val clip = Clip()
        val retained = CapsuleVideoFrameRetention(clip.player, this)
        val frozen = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val next = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        var reads = 0
        val oldSurface = Any()
        retained.registerCapture(oldSurface) { reads++; frozen }
        retained.retainPausedFrame()
        retained.unregisterCapture(oldSurface)
        val replacement = Any()
        retained.registerCapture(replacement) { reads++; next }
        repeat(3) { retained.retainPausedFrame() }
        assertSame(frozen, retained.frame.value!!.bitmap)
        assertEquals("no readback loop while paused", 1, reads)
        clip.play = true; clip.event(Player.EVENT_PLAY_WHEN_READY_CHANGED)
        assertNull(retained.frame.value)
        retained.release()
    }
    @Test fun pausedSeekWaitsForTheNewFrameAndNeverKeepsTheOldSnapshot() = runTest {
        val clip = Clip(); val retained = CapsuleVideoFrameRetention(clip.player, this)
        var available: Bitmap? = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        retained.registerCapture(Any()) { available }
        retained.retainPausedFrame()
        available = null
        clip.event(Player.EVENT_POSITION_DISCONTINUITY)
        assertNull(retained.frame.value)
        retained.retainPausedFrame()
        assertNull(retained.frame.value)
        available = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        retained.retainPausedFrame()
        assertSame(available, retained.frame.value!!.bitmap)
        clip.id = "next"; clip.event(Player.EVENT_MEDIA_ITEM_TRANSITION)
        assertNull(retained.frame.value)
        retained.release()
    }
    @Test fun closingStillSurvivesStopEventsButExpiresAfterTheReverseMotion() = runTest {
        val clip = Clip().apply { play = true }
        val retained = CapsuleVideoFrameRetention(clip.player, this)
        val still = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        retained.registerCapture(Any()) { still }
        retained.retainPausedFrame()
        assertNull(retained.frame.value)
        retained.retainExitFrame()
        clip.play = false; clip.id = null; clip.event(Player.EVENT_POSITION_DISCONTINUITY)
        assertTrue(retained.frame.value!!.exiting)
        advanceTimeBy(500); runCurrent()
        assertSame(still, retained.frame.value!!.bitmap)
        advanceTimeBy(151); runCurrent()
        assertNull(retained.frame.value)
        retained.release()
    }
    @Test fun retiringAnOldSurfaceCannotRemoveTheReplacementCaptureSource() = runTest {
        val clip = Clip(); val retained = CapsuleVideoFrameRetention(clip.player, this)
        val old = Any(); val new = Any()
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        retained.registerCapture(old) { null }
        retained.registerCapture(new) { bitmap }
        retained.unregisterCapture(old)
        retained.retainPausedFrame()
        assertSame(bitmap, retained.frame.value!!.bitmap)
        retained.release()
    }
}
