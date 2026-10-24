package com.nikhil.yt

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CrashReportTest {
    @Test fun reportUsesCapsuleTitleAndIncludesBuildWithoutChangingTheStack() {
        val stack = "java.lang.IllegalArgumentException: Cannot round NaN value.\n\tat frame"
        val report = buildCrashReport(RuntimeEnvironment.getApplication(), "2026-10-24 15:53:59", stack)
        assertEquals("Capsule crash report", report.lineSequence().first())
        assertTrue(report.contains("Build: ${BuildConfig.GIT_COMMIT} ${BuildConfig.BUILD_TYPE} ${BuildConfig.ARCHITECTURE}"))
        assertTrue(report.endsWith(stack + "\n"))
        assertFalse(report.contains("Velune crash report"))
    }
}
