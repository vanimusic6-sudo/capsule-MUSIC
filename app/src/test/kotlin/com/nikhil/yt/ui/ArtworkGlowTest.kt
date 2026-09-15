package com.nikhil.yt.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.nikhil.yt.ui.component.ArtworkGlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The bloom under a cover is decoration, and decoration must not be able to disturb anything.
 *
 * It draws behind its own bounds and takes no space of its own, so a screen laid out with it is
 * laid out identically without it. Worth pinning: the version of this idea that used a blur had to
 * be removed from the player because an offscreen buffer stalled the first open, and the next
 * person reaching for "a bit of glow" should find a drawn gradient here, not a filter.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35, 36], application = Application::class)
class ArtworkGlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun `the glow fills the box it is given and nothing more`() {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(200.dp).testTag("band")) {
                    ArtworkGlow(Color.White, Modifier.fillMaxSize())
                }
            }
        }
        compose.waitForIdle()

        compose.onNodeWithTag("band").assertWidthIsEqualTo(200.dp)
        compose.onNodeWithTag("band").assertHeightIsEqualTo(200.dp)
    }

    @Test fun `a broken intensity cannot paint an opaque block over the artwork`() {
        // Clamped rather than trusted: a stray value here would cover the cover.
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(120.dp).testTag("band")) {
                    ArtworkGlow(Color.White, Modifier.fillMaxSize(), intensity = 40f)
                    ArtworkGlow(Color.White, Modifier.fillMaxSize(), intensity = -3f)
                    ArtworkGlow(Color.White, Modifier.fillMaxSize(), intensity = Float.NaN)
                }
            }
        }
        compose.waitForIdle()

        compose.onNodeWithTag("band").assertWidthIsEqualTo(120.dp)
    }

    @Test fun `a zero-sized band does not produce an invalid gradient`() {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(0.dp).testTag("band")) {
                    ArtworkGlow(Color.White, Modifier.fillMaxSize())
                }
            }
        }
        compose.waitForIdle()

        compose.onNodeWithTag("band").assertWidthIsEqualTo(0.dp)
    }
}
