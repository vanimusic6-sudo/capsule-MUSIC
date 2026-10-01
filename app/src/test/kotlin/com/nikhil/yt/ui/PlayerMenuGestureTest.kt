package com.nikhil.yt.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import com.nikhil.yt.ui.component.BottomSheetMenu
import com.nikhil.yt.ui.component.MenuState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercise real pointer input through the production modal host and Material3 nested scrolling. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w393dp-h851dp-xhdpi")
class PlayerMenuGestureTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var menu: MenuState
    private lateinit var list: LazyListState

    private fun openMenu() {
        menu = MenuState()
        menu.show {
            // The fixed header and volume area above the menu's lazy list.
            Box(Modifier.fillMaxWidth().height(80.dp).testTag("header")) { Text("Now playing") }
            Box(Modifier.fillMaxWidth().height(112.dp)) { Text("Volume") }
            list = rememberLazyListState()
            LazyColumn(
                state = list,
                modifier = Modifier.fillMaxWidth().testTag("items"),
                contentPadding = PaddingValues(bottom = 32.dp),
            ) {
                items(30) { index ->
                    ListItem(
                        headlineContent = { Text("Action $index") },
                        modifier = Modifier.clickable {},
                    )
                }
            }
        }
        compose.setContent {
            MaterialTheme { BottomSheetMenu(state = menu, modifier = Modifier.testTag("sheet")) }
        }
        compose.waitForIdle()
    }

    private fun expandWithSwipe() {
        val initialTop = compose.onNodeWithTag("header").fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithTag("sheet").performTouchInput { swipeUp(durationMillis = 180) }
        compose.waitForIdle()
        val expandedTop = compose.onNodeWithTag("header").fetchSemanticsNode().boundsInRoot.top
        assertTrue("An upward swipe must fully expand the menu", expandedTop < initialTop)
    }

    @Test fun quickSwipeScrollsWithoutHoldingTheFinger() {
        openMenu()
        expandWithSwipe()
        compose.onNodeWithTag("items").performTouchInput { swipeUp(durationMillis = 120) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue("A released flick must advance the list", list.firstVisibleItemIndex > 0)
            assertTrue("Scrolling must keep the menu open", menu.isVisible)
        }
    }

    @Test fun downwardSwipeScrollsContentBeforeItDismissesTheSheetAtTheTop() {
        openMenu()
        expandWithSwipe()
        compose.onNodeWithTag("items").performScrollToIndex(20)
        compose.onNodeWithTag("items").performTouchInput { swipeDown(durationMillis = 450) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue("Downward input must scroll toward earlier actions", list.firstVisibleItemIndex < 20)
            assertTrue("The sheet must remain open while scrolling its contents", menu.isVisible)
        }
        compose.onNodeWithTag("items").performScrollToIndex(0)
        compose.waitForIdle()
        // ArchiveTune retains the partial anchor. A first swipe may settle there;
        // the next swipe must be able to dismiss, without Back or a long press.
        repeat(2) {
            if (menu.isVisible) {
                compose.onNodeWithTag("sheet").performTouchInput { swipeDown(durationMillis = 180) }
                compose.waitForIdle()
            }
        }
        compose.runOnIdle { assertFalse("Downward swipes at the list start must dismiss", menu.isVisible) }
    }
}
