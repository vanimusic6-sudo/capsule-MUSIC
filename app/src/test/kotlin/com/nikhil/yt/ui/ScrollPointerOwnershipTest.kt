package com.nikhil.yt.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.nikhil.yt.ui.utils.rememberCapsuleFlingBehavior
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Repro for the held-finger diagonal gesture seen in 10423.mp4.
 * The row and column must keep the axis they first accepted until UP/CANCEL.
 */
@OptIn(ExperimentalFoundationApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w393dp-h851dp-xhdpi")
class ScrollPointerOwnershipTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var vertical: LazyListState
    private lateinit var horizontal: LazyListState

    @Composable
    private fun Content() {
        MaterialTheme {
            LazyColumn(
                state = vertical,
                modifier = Modifier.fillMaxSize().testTag("feed"),
                flingBehavior = rememberCapsuleFlingBehavior(),
                overscrollEffect = null,
            ) {
                item(key = "shelf") {
                    LazyRow(
                        state = horizontal,
                        modifier = Modifier.fillMaxWidth().height(170.dp).testTag("shelf"),
                        flingBehavior = rememberCapsuleFlingBehavior(),
                        overscrollEffect = null,
                    ) {
                        items(32) { index ->
                            Box(
                                Modifier
                                    .width(126.dp)
                                    .fillMaxHeight()
                                    .combinedClickable(onClick = {}, onLongClick = {}),
                            )
                        }
                    }
                }
                items(90) { index ->
                    Box(Modifier.fillMaxWidth().height(90.dp))
                }
            }
        }
    }

    private fun showLists() {
        vertical = LazyListState()
        horizontal = LazyListState()
        compose.setContent { Content() }
        compose.waitForIdle()
    }

    @Test fun verticalDragWithLongHoldAndDiagonalCorrectionDoesNotStealHorizontalList() {
        showLists()
        compose.onNodeWithTag("shelf").performTouchInput {
            down(center)
            advanceEventTime(24)
            moveBy(Offset(0f, -120f)) // acquire vertical axis
            advanceEventTime(740)     // keep the SAME finger down
            moveBy(Offset(145f, -15f))
            advanceEventTime(30)
            moveBy(Offset(-110f, -60f))
            up()
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue("Vertical page must follow the original drag",
                vertical.firstVisibleItemIndex > 0 || vertical.firstVisibleItemScrollOffset > 0)
            assertEquals("Horizontal shelf must not start a separate drag during a held vertical gesture",
                0, horizontal.firstVisibleItemIndex)
            assertEquals(0, horizontal.firstVisibleItemScrollOffset)
        }
    }

    @Test fun horizontalDragKeepsItsAxisAcrossHoldAndDiagonalCorrection() {
        showLists()
        compose.onNodeWithTag("shelf").performTouchInput {
            down(center)
            advanceEventTime(24)
            moveBy(Offset(-140f, 0f)) // acquire horizontal axis
            advanceEventTime(740)
            moveBy(Offset(-90f, 54f))
            up()
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue("Horizontal shelf should respond even during a long held swipe",
                horizontal.firstVisibleItemIndex > 0 || horizontal.firstVisibleItemScrollOffset > 0)
            assertEquals("A horizontal gesture must not drag its vertical parent",
                0, vertical.firstVisibleItemIndex)
            assertEquals(0, vertical.firstVisibleItemScrollOffset)
        }
    }

    @Test fun stationaryLongPressCannotMoveEitherScrollPosition() {
        showLists()
        compose.onNodeWithTag("shelf").performTouchInput {
            down(center)
            advanceEventTime(850)
            up()
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(0, vertical.firstVisibleItemIndex)
            assertEquals(0, vertical.firstVisibleItemScrollOffset)
            assertEquals(0, horizontal.firstVisibleItemIndex)
            assertEquals(0, horizontal.firstVisibleItemScrollOffset)
        }
    }

    private fun source(relative: String) = listOf(
        File("src/main/kotlin/com/nikhil/yt/$relative"),
        File("app/src/main/kotlin/com/nikhil/yt/$relative"),
    ).first { it.isFile }.readText()

    @Test fun noComposableTogglesScrollOwnershipFromLiveDragState() {
        val files = listOf(
            "ui/utils/CapsuleScroll.kt",
            "ui/screens/HomeScreen.kt",
            "ui/screens/ExploreScreen.kt",
            "ui/screens/artist/ArtistScreen.kt",
            "ui/screens/AlbumScreen.kt",
        )
        files.forEach { path ->
            val code = source(path)
            assertFalse("$path must not toggle userScrollEnabled during a pointer session",
                code.contains("rememberCapsuleShelfScrollEnabled"))
        }
        val albums = source("ui/screens/library/LibraryAlbumsScreen.kt")
        assertFalse("Pull-to-refresh must not rebuild its recognizer at every scroll pixel",
            albums.contains("enabled = ytmSync && canPullRefresh"))
    }
}
