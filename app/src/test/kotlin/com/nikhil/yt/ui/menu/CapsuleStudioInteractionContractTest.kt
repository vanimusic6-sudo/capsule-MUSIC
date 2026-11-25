/* Capsule MUSIC — interactive studio queue contracts. GPL-3.0. */
package com.nikhil.yt.ui.menu

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class CapsuleStudioInteractionContractTest {
    @Test fun queueChangesDoNotSeekOrFlushAndRestoreOnUnderrun() {
        val renderer = File("src/main/kotlin/com/nikhil/yt/playback/CapsuleAudioRenderersFactory.kt").readText()
        val controller = renderer.substringAfter("private class CapsuleStudioInteractiveBuffer")
            .substringBefore("internal class CapsuleAudioRenderersFactory")
        assertTrue(controller.contains("output.underrunCount > baselineUnderruns"))
        assertTrue(controller.contains("setBufferSizeInFrames(originalFrames)"))
        assertTrue(controller.contains("450L"))
        assertTrue(controller.contains("attempted = interactive"))
        assertFalse(controller.contains(".flush("))
        assertFalse(controller.contains("seekTo("))
        val processor = File("src/main/kotlin/com/nikhil/yt/playback/CapsuleStudioEqProcessor.kt").readText()
        assertTrue(processor.contains("pendingCurve?.let { setCurve(it, pendingSmooth) }"))
        val menu = File("src/main/kotlin/com/nikhil/yt/ui/menu/PlayerMenu.kt").readText()
        assertTrue(menu.contains("service.setStudioInteractive(true)"))
        assertTrue(menu.contains("onDispose { service.setStudioInteractive(false) }"))
    }
}
