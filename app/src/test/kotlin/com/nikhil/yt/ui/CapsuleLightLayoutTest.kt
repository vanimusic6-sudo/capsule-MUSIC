package com.nikhil.yt.ui

import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.nikhil.yt.R
import com.nikhil.yt.constants.CapsulePlayerDesign
import com.nikhil.yt.ui.player.CapsuleLightControls
import com.nikhil.yt.ui.player.CapsulePlayerLayout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w393dp-h851dp-xhdpi")
class CapsuleLightLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun lightIsCoverFirstAndHasNoTopHeaderButtons() {
        var design by mutableStateOf(CapsulePlayerDesign.SUPER)
        var height by mutableStateOf(760.dp)
        compose.setContent {
            MaterialTheme {
                CapsulePlayerLayout(design, Color.White, {}, {}, Modifier.size(320.dp, height).testTag("player"),
                    artwork = { Box(Modifier.fillMaxWidth().aspectRatio(1f).testTag("cover")) },
                    details = { Box(Modifier.fillMaxWidth().height(320.dp).testTag("details")) },
                )
            }
        }
        val collapseLabel = compose.activity.getString(R.string.capsule_collapse_player)
        val menuLabel = compose.activity.getString(R.string.more)
        compose.runOnIdle { design = CapsulePlayerDesign.LIGHT }
        compose.onNodeWithContentDescription(collapseLabel).assertDoesNotExist()
        compose.onNodeWithContentDescription(menuLabel).assertDoesNotExist()
        for (screenHeight in listOf(760.dp, 620.dp)) {
            compose.runOnIdle { height = screenHeight }
            val cover = compose.onNodeWithTag("cover").fetchSemanticsNode().boundsInRoot
            val details = compose.onNodeWithTag("details").fetchSemanticsNode().boundsInRoot
            assertEquals(cover.width, cover.height, 1f)
            assertTrue(cover.width > 0f)
            assertTrue(cover.bottom <= details.top)
        }
    }

    @Test fun lightTransportUsesRepeatPreviousPlayNextMenuAndRespectsPlaybackRestrictions() {
        var enabled by mutableStateOf(true)
        val clicked = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxWidth()) {
                    CapsuleLightControls(
                        textColor = Color.White,
                        shuffleEnabled = false,
                        repeatMode = Player.REPEAT_MODE_ALL,
                        enabled = enabled,
                        canSkipPrevious = true,
                        canSkipNext = true,
                        onShuffle = {},
                        onPrevious = { clicked += "previous" },
                        onNext = { clicked += "next" },
                        onRepeat = { clicked += "repeat" },
                        orbit = { Box(Modifier.size(70.dp).clickable(enabled = enabled) { clicked += "play" }.testTag("orbit")) },
                        onMenuClick = { clicked += "menu" },
                    )
                }
            }
        }
        val repeat = compose.onNodeWithContentDescription(compose.activity.getString(R.string.repeat_mode_all))
        val previous = compose.onNodeWithContentDescription(compose.activity.getString(androidx.media3.ui.R.string.exo_controls_previous_description))
        val orbit = compose.onNodeWithTag("orbit")
        val next = compose.onNodeWithContentDescription(compose.activity.getString(androidx.media3.ui.R.string.exo_controls_next_description))
        val menu = compose.onNodeWithContentDescription(compose.activity.getString(R.string.more))
        val nodes = listOf(repeat, previous, orbit, next, menu)
        val centers = nodes.map { it.fetchSemanticsNode().boundsInRoot.center.x }
        assertEquals(centers.sorted(), centers)
        nodes.forEach { it.performClick() }
        compose.runOnIdle {
            assertEquals(listOf("repeat", "previous", "play", "next", "menu"), clicked)
            enabled = false
        }
        listOf(repeat, previous, orbit, next).forEach { it.assertIsNotEnabled() }
        menu.performClick()
        compose.runOnIdle { assertEquals("menu", clicked.last()) }
    }
    @Test fun shortLightPlayerCanScrollToItsLastControlWithoutLosingActions() {
        var clicks = 0
        compose.setContent {
            MaterialTheme {
                CapsulePlayerLayout(CapsulePlayerDesign.LIGHT, Color.White, {}, {},
                    Modifier.size(320.dp, 420.dp),
                    artwork = { Box(Modifier.fillMaxWidth().aspectRatio(1f).testTag("cover")) },
                    details = {
                        Column(Modifier.fillMaxWidth()) {
                            Box(Modifier.height(500.dp))
                            Box(Modifier.size(48.dp).testTag("last-control").clickable { clicks++ })
                        }
                    },
                )
            }
        }
        compose.onNodeWithTag("last-control").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1, clicks)
    }


    @Test fun lightScrollingKeepsTheDownwardQueueGestureAtTheTop() {
        var queueOpens = 0
        compose.setContent {
            MaterialTheme {
                CapsulePlayerLayout(
                    CapsulePlayerDesign.LIGHT, Color.White, {}, {},
                    Modifier.size(320.dp, 420.dp).testTag("player"),
                    onExpandQueue = { queueOpens++ },
                    artwork = { Box(Modifier.fillMaxWidth().aspectRatio(1f).testTag("cover")) },
                    details = {
                        Column(Modifier.fillMaxWidth()) {
                            Box(Modifier.height(700.dp))
                            Box(Modifier.size(48.dp).testTag("last-control"))
                        }
                    },
                )
            }
        }
        compose.onNodeWithTag("last-control").performScrollTo()
        compose.onNodeWithTag("player").performTouchInput {
            swipeDown(startY = height * 0.25f, endY = height * 0.5f, durationMillis = 600)
        }
        compose.runOnIdle { assertEquals("Scrolling the details must not open the queue", 0, queueOpens) }
        compose.onNodeWithTag("cover").performScrollTo()
        compose.onNodeWithTag("player").performTouchInput {
            swipeDown(startY = height * 0.2f, endY = height * 0.7f, durationMillis = 600)
        }
        compose.runOnIdle { assertEquals("A pull down from the top opens the queue once", 1, queueOpens) }
    }
}
