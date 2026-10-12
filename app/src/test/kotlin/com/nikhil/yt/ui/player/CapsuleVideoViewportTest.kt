@file:Suppress("UnsafeOptInUsageError")
package com.nikhil.yt.ui.player

import android.app.Application
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w400dp-h800dp")
class CapsuleVideoViewportTest {
    @get:Rule val compose = createComposeRule()
    @Test fun portraitFrameKeepsItsScaleAndCentreOnPauseAndExit() = verify(Size(9f, 16f))
    @Test fun wideFrameKeepsItsScaleAndCentreOnPauseAndExit() = verify(Size(21f, 9f))
    @Test fun squareFrameKeepsItsScaleAndCentreOnPauseAndExit() = verify(Size(1f, 1f))

    private fun verify(aspect: Size) {
        val paused = mutableStateOf(false)
        val active = mutableStateOf(true)
        var liveBounds = Rect.Zero
        var stillBounds = Rect.Zero
        var creates = 0
        compose.setContent {
            Box(Modifier.size(320.dp, 180.dp)) {
                CapsuleVideoViewport(if (paused.value) Size(aspect.width * 100, aspect.height * 100) else aspect) {
                    if (active.value) AndroidView(
                        factory = { creates++; View(it) },
                        modifier = Modifier.fillMaxSize().onGloballyPositioned { liveBounds = it.boundsInRoot() },
                    )
                    if (paused.value) Box(Modifier.fillMaxSize().onGloballyPositioned { stillBounds = it.boundsInRoot() })
                }
            }
        }
        compose.waitForIdle()
        val playingBounds = liveBounds
        assertTrue(playingBounds.width > 0 && playingBounds.height > 0)
        assertEquals(aspect.width / aspect.height, playingBounds.width / playingBounds.height, 0.02f)
        compose.runOnIdle { paused.value = true }
        compose.waitForIdle()
        assertEquals(playingBounds, liveBounds)
        assertEquals(playingBounds, stillBounds)
        assertEquals(1, creates)
        compose.runOnIdle { active.value = false }
        compose.waitForIdle()
        assertEquals(playingBounds, stillBounds)
    }
}
