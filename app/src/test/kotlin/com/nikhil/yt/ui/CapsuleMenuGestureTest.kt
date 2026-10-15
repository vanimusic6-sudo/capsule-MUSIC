package com.nikhil.yt.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.down
import androidx.compose.ui.test.up
import androidx.compose.ui.unit.dp
import com.nikhil.yt.ui.component.BottomSheetMenu
import com.nikhil.yt.ui.component.MenuPresentation
import com.nikhil.yt.ui.component.MenuState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w393dp-h851dp-xhdpi")
class CapsuleMenuGestureTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun openMenu(): MenuState {
        val state = MenuState()
        state.show(presentation = MenuPresentation.CAPSULE_PLAYER) {
            repeat(16) { index ->
                Box(Modifier.fillMaxWidth().height(60.dp)) { Text("Action $index") }
            }
        }
        compose.setContent { MaterialTheme { BottomSheetMenu(state = state) } }
        compose.waitForIdle()
        return state
    }

    private fun cardTop(): Float =
        compose.onNodeWithTag("capsule-menu-card").fetchSemanticsNode().boundsInRoot.top

    @Test fun longHoldWithoutDraggingCannotMoveTheMenu() {
        val state = openMenu()
        val before = cardTop()
        compose.onNodeWithTag("capsule-menu-drag-handle").performTouchInput {
            down(center)
            advanceEventTime(850L)
            up()
        }
        compose.waitForIdle()
        assertTrue(state.isVisible)
        assertEquals("no panel retreat on press and hold", before, cardTop(), 1.5f)
    }

    @Test fun swipeExpandsAndSwipeClosesThePhysicalMenu() {
        val state = openMenu()
        val peekTop = cardTop()
        compose.onNodeWithTag("capsule-menu-drag-handle").performTouchInput {
            swipe(start = center, end = Offset(center.x, center.y - 280f), durationMillis = 180)
        }
        compose.waitForIdle()
        assertTrue("menu remains present while expanded", state.isVisible)
        assertTrue("upward swipe expands the sheet", cardTop() < peekTop - 20f)
        compose.onNodeWithTag("capsule-menu-drag-handle").performTouchInput {
            swipe(start = center, end = Offset(center.x, center.y + 420f), durationMillis = 180)
        }
        compose.waitForIdle()
        assertFalse("downward swipe dismisses after its spring", state.isVisible)
    }
}
