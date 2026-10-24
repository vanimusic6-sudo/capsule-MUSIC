package com.nikhil.yt.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.unit.dp
import com.nikhil.yt.R
import com.nikhil.yt.ui.component.CapsuleSubscribeIcon
import com.nikhil.yt.ui.component.CapsuleSubscribeLabel
import com.nikhil.yt.ui.component.ItemThumbnail
import com.nikhil.yt.ui.component.VeluneLoader
import com.nikhil.yt.ui.screens.artist.artistItemPlacement
import com.nikhil.yt.ui.screens.artist.ArtistDescription
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h800dp-xhdpi")
class ArtistMotionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun subscriptionCanReverseDuringAMorphAndEndsWithOneStableLabel() {
        var subscribed by mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                Column(Modifier.width(220.dp)) {
                    CapsuleSubscribeIcon(subscribed, Color.White, Modifier.size(24.dp).testTag("glyph"))
                    CapsuleSubscribeLabel(subscribed)
                }
            }
        }
        val initial = compose.onNodeWithTag("glyph").fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        repeat(5) {
            compose.runOnIdle { subscribed = !subscribed }
            compose.mainClock.advanceTimeBy(80)
        }
        compose.mainClock.advanceTimeBy(600)
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText(compose.activity.getString(R.string.subscribed)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.subscribe)).assertDoesNotExist()
        assertEquals(initial, compose.onNodeWithTag("glyph").fetchSemanticsNode().boundsInRoot)
    }

    @Test fun descriptionReflowsThroughIntermediateHeightsAndCanCollapseBeforeFinishing() {
        compose.setContent {
            MaterialTheme { ArtistDescription("artist", "Biography line.\n".repeat(10), Modifier.width(320.dp).testTag("description")) }
        }
        val collapsed = compose.onNodeWithTag("description").fetchSemanticsNode().boundsInRoot.height
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText(compose.activity.getString(R.string.show_more)).performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(96)
        val middle = compose.onNodeWithTag("description").fetchSemanticsNode().boundsInRoot.height
        compose.mainClock.advanceTimeBy(400)
        val expanded = compose.onNodeWithTag("description").fetchSemanticsNode().boundsInRoot.height
        assertTrue("Expansion must have intermediate geometry: $collapsed -> $middle -> $expanded", middle > collapsed && middle < expanded)
        compose.onNodeWithText(compose.activity.getString(R.string.show_less)).performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(80)
        compose.waitForIdle()
        compose.onNodeWithText(compose.activity.getString(R.string.show_more)).performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.mainClock.advanceTimeBy(500)
        compose.mainClock.autoAdvance = true
        assertEquals(expanded, compose.onNodeWithTag("description").fetchSemanticsNode().boundsInRoot.height, 1f)
    }

    @Test fun collapsingKeepsFullTextGeometryWhileTheClipAndFollowingRowsMoveTogether() {
        val biography = "Biography line.\n".repeat(7)
        compose.setContent {
            var moving by androidx.compose.runtime.remember { mutableStateOf(false) }
            MaterialTheme {
                androidx.compose.foundation.lazy.LazyColumn(Modifier.width(320.dp)) {
                    item(key = "description") {
                        ArtistDescription("artist", biography, Modifier.testTag("description"), { moving = it })
                    }
                    items(4, key = { "row-$it" }) { index ->
                        Box(Modifier.then(artistItemPlacement(moving)).size(300.dp, 32.dp).testTag("row-$index"))
                    }
                }
            }
        }
        compose.onNodeWithText(compose.activity.getString(R.string.show_more)).performClick()
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithText(biography).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val fullTextHeight = layouts.single().size.height
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText(compose.activity.getString(R.string.show_less)).performClick()
        compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
        repeat(3) {
            compose.mainClock.advanceTimeBy(64); compose.waitForIdle()
            val description = compose.onNodeWithTag("description").fetchSemanticsNode().boundsInRoot
            val following = compose.onNodeWithTag("row-0").fetchSemanticsNode().boundsInRoot
            assertTrue("Rows must follow the measured clip without overlap", following.top >= description.bottom - 1f)
            layouts.clear()
            compose.onNodeWithText(biography).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals("Collapse must clip full-size text", fullTextHeight, layouts.single().size.height)
        }
        compose.mainClock.autoAdvance = true
    }

    @Test fun unavailableThumbnailExposesAReasonToAccessibilityAndCometDoesNotChangeItsSize() {
        compose.setContent {
            MaterialTheme {
                Column {
                    ItemThumbnail(null, false, false, androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                        Modifier.size(56.dp).testTag("unavailable"), unavailableReason = "Track unavailable")
                    VeluneLoader(Modifier.testTag("comet"), size = 24.dp)
                }
            }
        }
        compose.onNodeWithTag("unavailable").assertIsDisplayed()
        compose.onNodeWithContentDescription("Track unavailable").assertExists()
        val before = compose.onNodeWithTag("comet").fetchSemanticsNode().boundsInRoot
        compose.mainClock.advanceTimeBy(1500)
        assertEquals(before, compose.onNodeWithTag("comet").fetchSemanticsNode().boundsInRoot)
    }
}
