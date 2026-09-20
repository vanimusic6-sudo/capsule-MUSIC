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
        watch.start(context)

        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))

        val lines = linesFor(watch)
        assertEquals(lines.toString(), 2, lines.size)
        assertTrue(lines[0], lines[0].contains("watch-started"))
        assertTrue(lines[1], lines[1].contains("screen-off"))
        assertTrue(lines[1], lines[1].contains("playing=true"))
        watch.stop(context)
    }

    @Test fun `the screen coming back is recorded too, so a frozen stretch has two ends`() {
        val watch = PlaybackPowerWatch(context, isPlaying = { true })
        watch.start(context)

        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_ON))

        assertEquals(3, linesFor(watch).size)
        watch.stop(context)
    }

    @Test fun `power save and doze changes are recorded, because they are the suspects`() {
        val watch = PlaybackPowerWatch(context, isPlaying = { false })
        watch.start(context)

        context.sendBroadcast(Intent(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
        context.sendBroadcast(Intent(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED))

        val lines = linesFor(watch)
        assertEquals(lines.toString(), 3, lines.size)
        assertTrue(lines.any { it.contains("power-save-changed") })
        assertTrue(lines.any { it.contains("doze-changed") })
        watch.stop(context)
    }

    @Test fun `the line names every suspect, so one capture can rule them out`() {
        val described = PlaybackPowerWatch(context, isPlaying = { true }).describe()

        for (field in listOf("interactive=", "doze=", "powerSave=", "batteryOptimised=")) {
            assertTrue("$field missing from: $described", described.contains(field))
        }
    }

    @Test fun `nothing is recorded once the service has gone`() {
        val watch = PlaybackPowerWatch(context, isPlaying = { true })
        watch.start(context)
        watch.stop(context)

        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))

        // Only the baseline from start(); the screen-off after stop() reaches nothing.
        assertEquals(1, linesFor(watch).size)
    }

    @Test fun `stopping twice, or before starting, is not an error`() {
        val watch = PlaybackPowerWatch(context, isPlaying = { true })

        watch.stop(context)
        watch.start(context)
        watch.start(context)
        watch.stop(context)
        watch.stop(context)

        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        assertEquals(1, linesFor(watch).size)
    }

    @Test fun `the baseline names the battery exemption, which never announces itself`() {
        // A capture with no screen-off in it still has to be able to answer the question.
        val watch = PlaybackPowerWatch(context, isPlaying = { false })

        watch.start(context)

        val baseline = linesFor(watch).single()
        assertTrue(baseline, baseline.contains("watch-started"))
        assertTrue(baseline, baseline.contains("batteryOptimised="))
        watch.stop(context)
    }

    @Test fun `the frozen-stretch reading is available while the service runs, and not after`() {
        val watch = PlaybackPowerWatch(context, isPlaying = { true })
        watch.start(context)

        assertTrue(PlaybackPowerWatch.describeNow().orEmpty().contains("doze="))

        watch.stop(context)
        assertEquals(null, PlaybackPowerWatch.describeNow())
    }
}

/**
 * A wake lock held only while audio plays.
 *
 * Media3's WAKE_MODE_LOCAL is supposed to do this and a capture says it does not: with the
 * screen off, doze off, power save off and the app exempt from battery optimisation, playback
 * advanced 2.4 seconds across 335 seconds while the whole track was already downloaded and
 * eighty-three seconds of it decoded.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PlaybackWakeLockTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun `the lock is taken while playing and dropped when playback stops`() {
        val lock = PlaybackWakeLock(context)

        lock.setPlaying(true)
        assertTrue("nothing was keeping the CPU awake", lock.isHeld)

        lock.setPlaying(false)
        assertTrue("the lock outlived playback", !lock.isHeld)
    }

    @Test fun `repeating an edge does not unbalance it`() {
        // Not reference counted on purpose: a release can never be one short.
        val lock = PlaybackWakeLock(context)

        lock.setPlaying(true)
        lock.setPlaying(true)
        lock.setPlaying(false)

        assertTrue(!lock.isHeld)
    }

    @Test fun `the service can always drop it, whatever the player last said`() {
        val lock = PlaybackWakeLock(context)
        lock.setPlaying(true)

        lock.release()

        assertTrue("a leaked wake lock is a battery fault", !lock.isHeld)
    }

    @Test fun `releasing one that was never taken is not an error`() {
        PlaybackWakeLock(context).release()
    }

    @Test fun `the power line states whether the lock is held, rather than implying it`() {
        val watch = PlaybackPowerWatch(context, isPlaying = { true }, isWakeLockHeld = { true })

        assertTrue(watch.describe().contains("wakeLock=true"))
    }
}
