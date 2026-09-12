package com.nikhil.yt.ui

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

    @Test fun lightIsAnIndependentLayoutWithAccessibleHeaderAndNonOverlappingSquareCover() {
        var design by mutableStateOf(CapsulePlayerDesign.SUPER)
        var height by mutableStateOf(760.dp)
        var collapse = 0
        var menu = 0
        compose.setContent {
            MaterialTheme {
                CapsulePlayerLayout(design, Color.White, { collapse++ }, { menu++ }, Modifier.size(320.dp, height).testTag("player"),
                    artwork = { Box(Modifier.fillMaxWidth().aspectRatio(1f).testTag("cover")) },
                    details = { Box(Modifier.fillMaxWidth().height(320.dp).testTag("details")) },
                )
            }
        }
        val collapseLabel = compose.activity.getString(R.string.capsule_collapse_player)
        val menuLabel = compose.activity.getString(R.string.more)
        compose.onNodeWithContentDescription(collapseLabel).assertDoesNotExist()
        compose.runOnIdle { design = CapsulePlayerDesign.LIGHT }
        compose.onNodeWithContentDescription(collapseLabel).performClick()
        compose.onNodeWithContentDescription(menuLabel).performClick()
        compose.runOnIdle { assertEquals(1, collapse); assertEquals(1, menu) }
        for (screenHeight in listOf(760.dp, 620.dp)) {
            compose.runOnIdle { height = screenHeight }
            val cover = compose.onNodeWithTag("cover").fetchSemanticsNode().boundsInRoot
            val details = compose.onNodeWithTag("details").fetchSemanticsNode().boundsInRoot
            val back = compose.onNodeWithContentDescription(collapseLabel).fetchSemanticsNode().boundsInRoot
            assertEquals(cover.width, cover.height, 1f)
            assertTrue(cover.width > 0f)
            assertTrue(back.bottom <= cover.top)
            assertTrue(cover.bottom <= details.top)
        }
        compose.runOnIdle { design = CapsulePlayerDesign.SUPER }
        compose.onNodeWithContentDescription(collapseLabel).assertDoesNotExist()
    }

    @Test fun lightTransportUsesFiveOrderedActionsAndRespectsGuestAndSkipRestrictions() {
        var enabled by mutableStateOf(true)
        val clicked = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxWidth()) {
                    CapsuleLightControls(Color.White, true, Player.REPEAT_MODE_ALL, enabled, true, true,
                        { clicked += "shuffle" }, { clicked += "previous" }, { clicked += "next" }, { clicked += "repeat" },
                        orbit = { Box(Modifier.size(70.dp).clickable(enabled = enabled) { clicked += "play" }.testTag("orbit")) },
                    )
                }
            }
        }
        val shuffle = compose.onNodeWithContentDescription(compose.activity.getString(R.string.shuffle))
        val previous = compose.onNodeWithContentDescription(compose.activity.getString(androidx.media3.ui.R.string.exo_controls_previous_description))
        val orbit = compose.onNodeWithTag("orbit")
        val next = compose.onNodeWithContentDescription(compose.activity.getString(androidx.media3.ui.R.string.exo_controls_next_description))
        val repeat = compose.onNodeWithContentDescription(compose.activity.getString(R.string.repeat_mode_all))
        val nodes = listOf(shuffle, previous, orbit, next, repeat)
        val centers = nodes.map { it.fetchSemanticsNode().boundsInRoot.center.x }
        assertEquals(centers.sorted(), centers)
        nodes.forEach { it.performClick() }
        compose.runOnIdle { assertEquals(listOf("shuffle", "previous", "play", "next", "repeat"), clicked); enabled = false }
        nodes.forEach { it.assertIsNotEnabled() }
    }
}
