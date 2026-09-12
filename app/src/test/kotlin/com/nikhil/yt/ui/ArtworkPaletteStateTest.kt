package com.nikhil.yt.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.nikhil.yt.ui.player.ArtworkPaletteCache
import com.nikhil.yt.ui.player.capsuleArtworkPaletteKey
import com.nikhil.yt.ui.player.rememberArtworkGradientColors
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ArtworkPaletteStateTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val red = listOf(Color.Red, Color(0xFFBD3344), Color(0xFF651929))
    private val blue = listOf(Color.Blue, Color(0xFF3355BD), Color(0xFF192965))

    @Test fun openingPlayerUsesCachedColorsImmediatelyAndThumbnailOrThemeChangesCannotRecolorTheSong() {
        val key = capsuleArtworkPaletteKey("current")
        val cache = ArtworkPaletteCache().apply { put(key, red) }
        var thumbnail by mutableStateOf("old-cover")
        var light by mutableStateOf(false)
        val frames = mutableListOf<List<Color>>()
        compose.setContent {
            MaterialTheme(colorScheme = if (light) lightColorScheme() else darkColorScheme()) {
                val colors = rememberArtworkGradientColors(key, thumbnail, paletteCache = cache)
                SideEffect { frames += colors }
            }
        }
        compose.runOnIdle { assertEquals(red, frames.first()) }
        // Simulates eviction/repopulation while album browsing and enriched URLs arrive.
        compose.runOnIdle {
            cache.put(key, blue)
            thumbnail = "high-resolution-cover"
            light = true
        }
        compose.runOnIdle { assertTrue(frames.all { it == red }) }
    }

    @Test fun newTrackRetainsPreviousColorsUntilItsCoverIsReadyThenTransitions() {
        var key by mutableStateOf(capsuleArtworkPaletteKey("first"))
        var thumbnail by mutableStateOf<String?>(null)
        val cache = ArtworkPaletteCache().apply { put(key, red) }
        var observed = emptyList<Color>()
        compose.setContent {
            MaterialTheme {
                val colors = rememberArtworkGradientColors(key, thumbnail, paletteCache = cache)
                SideEffect { observed = colors }
            }
        }
        compose.runOnIdle { key = capsuleArtworkPaletteKey("second") }
        compose.runOnIdle { assertEquals(red, observed) }
        compose.runOnIdle {
            cache.put(key, blue)
            thumbnail = "ready-cover"
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(blue, observed) }
    }
}
