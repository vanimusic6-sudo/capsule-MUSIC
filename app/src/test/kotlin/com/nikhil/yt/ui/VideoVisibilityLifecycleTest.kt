package com.nikhil.yt.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source-level lifecycle contracts; device test remains necessary. */
class VideoVisibilityLifecycleTest {
    private fun source(relative: String): String =
        listOf(File("src/main/kotlin/com/nikhil/yt/$relative"), File("app/src/main/kotlin/com/nikhil/yt/$relative"))
            .first { it.isFile }.readText()

    @Test
    fun activityReassertsVisibilityOnResumeAndRejectsLateBindingAfterStop() {
        val activity = source("MainActivity.kt")
        assertTrue(activity.contains("service.service.setVideoAppForeground(videoActivityStarted)"))
        assertTrue(activity.substringAfter("override fun onResume()").substringBefore("private fun safeUnbindMusicService").contains("setVideoAppForeground(true)"))
        assertTrue(activity.substringAfter("override fun onStop()").substringBefore("override fun onDestroy()").contains("videoActivityStarted = false"))
    }

    @Test
    fun screenOffDoesNotForgetActivityAndUnlockRecomputesVisibility() {
        val service = source("playback/MusicService.kt")
        assertTrue(service.contains("val visible = videoActivityForeground && canUseCapsuleVideoForScreen()"))
        assertTrue(service.substringAfter("private fun suspendCapsuleVideoForScreenOff()").substringBefore("private fun resumeCapsuleVideoAfterUnlockIfAllowed()").contains("updateVideoScreenVisibility()"))
        assertTrue(service.substringAfter("private fun resumeCapsuleVideoAfterUnlockIfAllowed()").substringBefore("private fun isCurrentCapsuleVideoItem()").contains("updateVideoScreenVisibility()"))
    }
}
