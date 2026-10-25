package com.nikhil.yt.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Prevent reintroducing the exact VIDEO regression that made AUDIO slow/unavailable. */
class CapsuleCleanupVideoParityTest {
    private fun source(path: String): String =
        listOf(File(path), File("../$path")).first { it.isFile }.readText()

    @Test fun videoExtractionUsesOneOriginalNewPipeRouteNotSequentialFallbacks() {
        val provider = source("app/src/main/kotlin/com/nikhil/yt/playback/video/CapsuleVideoExtractorProvider.kt")
        assertTrue(provider.contains("CapsuleNewPipeExtractor.resolve("))
        assertFalse(provider.contains("resolveWithFallback("))
        assertFalse(provider.contains("CapsuleDirectVideoExtractor.resolve("))
        assertTrue(provider.contains("withRequest(requestId, 20_000L)"))
    }

    @Test fun originalProgressiveMuxedVideoComesBeforeSeparateAudioTracks() {
        val extractor = source("innertube/src/main/kotlin/com/nikhil/yt/innertube/video/CapsuleNewPipeExtractor.kt")
        assertTrue(extractor.contains("StreamInfo.getInfo("))
        val begin = extractor.indexOf("fun resolve(")
        val finish = extractor.indexOf("fun classify(", begin)
        val mainPath = extractor.substring(begin, finish)
        assertTrue(mainPath.contains("selectedMuxed?.let { return it.toResolved(id, audio = null) }"))
        assertFalse(mainPath.contains("indexedDashVideo("))
        assertFalse(mainPath.contains("selectDirectHlsManifest("))
    }

    @Test fun moreMenuKeepsPlayerOpenForDownloadAndRemoval() {
        val player = source("app/src/main/kotlin/com/nikhil/yt/ui/menu/PlayerMenu.kt")
        val control = source("app/src/main/kotlin/com/nikhil/yt/ui/menu/YouTubeDownloadMenuItems.kt")
        assertTrue(player.contains("keepOpenOnDownload = true, showActiveStopAction = false"))
        assertTrue(control.contains("busy = waitingForIndex"))
        assertTrue(control.contains("CapsuleDownloadComet()"))
        assertTrue(control.contains("destructive = true, closeMenu = !keepOpenOnDownload"))
    }
}
