package com.nikhil.yt.utils

import com.nikhil.yt.BuildConfig
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Every capture has to say which build produced it.
 *
 * A capture arrived showing exactly the improvement a change predicted and could not be credited
 * to it, because nothing in the file said whether the change was in the build. The export header
 * carries the marker, but a header is lost the moment somebody copies a fragment of the log, so
 * it is in the stream too.
 *
 * The ordering is the part worth pinning. The first attempt wrote this line in
 * Application.onCreate, which is before the log tree is planted — the tree is planted after an
 * asynchronous preference read — so the line went nowhere at all, silently. Writing it as part of
 * the planting is what makes that impossible.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BuildMarkerLogTest {
    @After fun tearDown() {
        DebugLoggingController.setEnabled(false)
        GlobalLog.clear()
    }

    @Test fun `turning logging on records the build before anything else`() {
        GlobalLog.clear()

        DebugLoggingController.setEnabled(true)

        val first = GlobalLog.snapshot().firstOrNull()
        assertTrue("nothing was logged at all", first != null)
        val line = GlobalLog.format(first!!)
        assertTrue("not the build marker: $line", line.contains("build version="))
        assertTrue("no commit in: $line", line.contains("commit=${BuildConfig.GIT_COMMIT}"))
        assertTrue("no version in: $line", line.contains(BuildConfig.VERSION_NAME))
    }

    @Test fun `the marker is not written at debug, so a debug-off capture keeps it`() {
        GlobalLog.clear()

        DebugLoggingController.setEnabled(true)

        val marker = GlobalLog.snapshot().first { GlobalLog.format(it).contains("build version=") }
        assertTrue(
            "the build marker must outrank debug",
            marker.level > android.util.Log.DEBUG,
        )
    }
}
