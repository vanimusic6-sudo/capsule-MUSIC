package com.nikhil.yt.playback

import android.app.Application
import android.content.Intent
import android.os.PowerManager
import com.nikhil.yt.utils.DebugLoggingController
import com.nikhil.yt.utils.GlobalLog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Naming what suspends playback when the screen goes off.
 *
 * A capture showed six stretches where the player believed it was playing — playWhenReady true,
 * state READY, ninety seconds decoded and waiting — while the position advanced four seconds in
 * five minutes. Doze, an OEM power saver and a wake lock that is not what it is believed to be
 * all look identical from inside the app, and they have different remedies, so the capture has
 * to say which.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PlaybackPowerWatchTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun enableLogging() {
        GlobalLog.clear()
        DebugLoggingController.setEnabled(true)
    }

    @After fun tidy() {
        DebugLoggingController.setEnabled(false)
        GlobalLog.clear()
    }

    /** Broadcasts are posted to the main looper; nothing is delivered until it runs. */
    private fun linesFor(watch: PlaybackPowerWatch): List<String> {
        shadowOf(android.os.Looper.getMainLooper()).idle()
        return GlobalLog.snapshot().map(GlobalLog::format).filter { it.contains("power ") }
    }

    @Test fun `turning the screen off is recorded, with what playback thought it was doing`() {
        val watch = PlaybackPowerWatch(context, isPlaying = { true })
        watch.start()

        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))

        val lines = linesFor(watch)
        assertEquals(lines.toString(), 1, lines.size)
        assertTrue(lines[0], lines[0].contains("screen-off"))
        assertTrue(lines[0], lines[0].contains("playing=true"))
        watch.stop()
    }

    @Test fun `the screen coming back is recorded too, so a frozen stretch has two ends`() {
        val watch = PlaybackPowerWatch(context, isPlaying = { true })
        watch.start()

        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_ON))

        assertEquals(2, linesFor(watch).size)
        watch.stop()
    }

    @Test fun `power save and doze changes are recorded, because they are the suspects`() {
        val watch = PlaybackPowerWatch(context, isPlaying = { false })
        watch.start()

        context.sendBroadcast(Intent(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
        context.sendBroadcast(Intent(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED))

        val lines = linesFor(watch)
        assertEquals(lines.toString(), 2, lines.size)
        assertTrue(lines.any { it.contains("power-save-changed") })
        assertTrue(lines.any { it.contains("doze-changed") })
        watch.stop()
    }

    @Test fun `the line names every suspect, so one capture can rule them out`() {
        val described = PlaybackPowerWatch(context, isPlaying = { true }).describe()

        for (field in listOf("interactive=", "doze=", "powerSave=", "batteryOptimised=")) {
            assertTrue("$field missing from: $described", described.contains(field))
        }
    }

    @Test fun `nothing is recorded once the service has gone`() {
        val watch = PlaybackPowerWatch(context, isPlaying = { true })
        watch.start()
        watch.stop()

        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))

        assertEquals(0, linesFor(watch).size)
    }

    @Test fun `stopping twice, or before starting, is not an error`() {
        val watch = PlaybackPowerWatch(context, isPlaying = { true })

        watch.stop()
        watch.start()
        watch.start()
        watch.stop()
        watch.stop()

        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        assertEquals(0, linesFor(watch).size)
    }
}
