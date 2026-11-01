package com.nikhil.yt.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.nikhil.yt.ui.component.rememberArtworkArrivalAlpha
import com.nikhil.yt.viewmodels.AlbumUiState
import com.nikhil.yt.viewmodels.resolveAlbumUiState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DetailLoadingRegressionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun anEmptyCachedAlbumTerminatesOnFailureOrSuccess() {
        assertEquals(AlbumUiState.Empty, resolveAlbumUiState(true, false, false, false))
        assertTrue(resolveAlbumUiState(true, false, false, true) is AlbumUiState.Error)
        assertEquals(AlbumUiState.Content, resolveAlbumUiState(true, true, false, true))
        assertEquals(AlbumUiState.Loading, resolveAlbumUiState(false, false, false, false))
    }

    @Test fun aCachedImageRevealsOnceAndALateLoadingCallbackCannotDimIt() {
        var decoded by mutableStateOf(false)
        var identity by mutableStateOf("cover-a")
        lateinit var alpha: androidx.compose.runtime.State<Float>
        compose.mainClock.autoAdvance = false
        compose.setContent {
            alpha = rememberArtworkArrivalAlpha(identity, decoded)
            Box(Modifier.size(40.dp).graphicsLayer { this.alpha = alpha.value })
        }
        compose.runOnIdle { decoded = true }
        compose.mainClock.advanceTimeBy(90)
        compose.runOnIdle { assertTrue(alpha.value > 0f && alpha.value < 1f); decoded = false }
        compose.mainClock.advanceTimeBy(250)
        compose.runOnIdle { assertEquals(1f, alpha.value, 0f); identity = "cover-b" }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { assertEquals(0f, alpha.value, 0f) }
    }
}
