package com.nikhil.yt.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.nikhil.yt.LocalPlayerAwareWindowInsets
import com.nikhil.yt.ui.component.*
import com.nikhil.yt.ui.utils.rememberCapsuleFlingBehavior
import com.nikhil.yt.ui.screens.albumHasHeroHeader
import com.nikhil.yt.viewmodels.AlbumUiState
import kotlinx.coroutines.runBlocking
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.swipeDown
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w393dp-h851dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CollectionHeaderMotionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun artworkSwipeMovesTheListAndReverseSwipeIsResponsive() {
        lateinit var state: LazyListState
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalPlayerAwareWindowInsets provides WindowInsets()) {
                    state = rememberLazyListState()
                    val motion = rememberDetailHeaderMotion(state, true, "artwork-gesture")
                    AlbumScreenLayout(
                        background = Color.Black,
                        state = state,
                        modifier = Modifier.size(320.dp, 700.dp).testTag("gesture-screen"),
                        flingBehavior = rememberCapsuleFlingBehavior(),
                        content = {
                            item(key = "header") {
                                CapsuleDetailHero(
                                    title = "An album",
                                    metrics = emptyList(),
                                    artworkAlpha = motion::artworkAlpha,
                                    titleMotion = motion,
                                    artwork = { Box(Modifier.fillMaxSize().background(Color.Red)) },
                                    actions = {},
                                )
                            }
                            item { Spacer(Modifier.height(1500.dp)) }
                        },
                        topBar = {
                            DetailTopAppBar(motion, Color.Black, false, title = {}, navigationIcon = {})
                        },
                    )
                }
            }
        }
        // Reproduces the regression that the previous pre-fling handler
        // did NOT test: touch actually starts on the fullscreen artwork.
        compose.onNodeWithTag("capsule:detail-artwork").performTouchInput {
            swipeUp(startY = height * .80f, endY = height * .22f, durationMillis = 330)
        }
        compose.waitForIdle()
        var offsetAfter = 0
        compose.runOnIdle {
            offsetAfter = if (state.firstVisibleItemIndex > 0) 1500
                else state.firstVisibleItemScrollOffset
            assertTrue("Artwork swipe must move the collection", offsetAfter > 20)
        }
        // New touch must interrupt docking and restore scroll immediately.
        compose.onNodeWithTag("gesture-screen").performTouchInput {
            swipeDown(startY = height * .30f, endY = height * .75f, durationMillis = 330)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            val offsetNow = if (state.firstVisibleItemIndex > 0) 1500
                else state.firstVisibleItemScrollOffset
            assertTrue("Reverse swipe must not be blocked", offsetNow < offsetAfter)
        }
    }

    @Test fun decodedPixelsReachWindowEdgeAndRemainVisibleBelowToolbarInBothThemes() {
        var decoded by mutableStateOf(false)
        var light by mutableStateOf(false)
        compose.setContent {
            MaterialTheme(colorScheme = if (light) lightColorScheme() else darkColorScheme()) {
                CompositionLocalProvider(LocalPlayerAwareWindowInsets provides WindowInsets()) {
                    val state = rememberLazyListState()
                    val motion = rememberDetailHeaderMotion(state, hasHeader = decoded)
                    AlbumScreenLayout(
                        background = Color.Black,
                        state = state,
                        modifier = Modifier.size(320.dp, 700.dp).testTag("screen"),
                        content = {
                            item {
                                AlbumPortraitFrame(
                                    Color.Black,
                                    artworkAlpha = motion::artworkAlpha,
                                    artworkMotion = motion,
                                ) {
                                    Box(Modifier.fillMaxSize().background(if (decoded) Color.Red else Color.Black))
                                }
                            }
                        },
                        topBar = {
                            DetailTopAppBar(motion, Color.Black, false, title = {}, navigationIcon = {})
                        },
                    )
                }
            }
        }
        compose.runOnIdle { decoded = true }
        fun assertNoToolbarBand() {
            val bounds = compose.onNodeWithTag("screen").fetchSemanticsNode().boundsInRoot
            compose.runOnIdle {
                val bitmap = Bitmap.createBitmap(800, 1600, Bitmap.Config.ARGB_8888)
                compose.activity.findViewById<View>(android.R.id.content).draw(Canvas(bitmap))
                val x = bounds.center.x.toInt()
                for (yDp in listOf(2.dp, 35.dp, 65.dp)) {
                    val y = bounds.top.toInt() + with(compose.density) { yDp.roundToPx() }
                    assertEquals("Decoded photo hidden at $yDp", android.graphics.Color.RED, bitmap.getPixel(x, y))
                }
                bitmap.recycle()
            }
        }
        assertNoToolbarBand()
        compose.runOnIdle { light = true }
        assertNoToolbarBand()
    }

    @Test fun reversingScrollRestoresExactProgressAndCapsuleGeometryWithoutRecomposingBody() {
        lateinit var state: LazyListState
        lateinit var motion: DetailHeaderMotionState
        var bodyCompositions = 0
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalPlayerAwareWindowInsets provides WindowInsets()) {
                    state = rememberLazyListState()
                    motion = rememberDetailHeaderMotion(state, true, "album-a")
                    bodyCompositions++
                    AlbumScreenLayout(
                        background = Color.Black,
                        state = state,
                        modifier = Modifier.size(320.dp, 700.dp),
                        content = {
                            item(key = "header") {
                                CapsuleDetailHero(
                                    title = "An album",
                                    metrics = emptyList(),
                                    artworkAlpha = motion::artworkAlpha,
                                    titleMotion = motion,
                                    artwork = { Box(Modifier.fillMaxSize().background(Color.Red)) },
                                    actions = {},
                                )
                            }
                            item { Spacer(Modifier.height(1000.dp)) }
                        },
                        topBar = { DetailTopAppBar(motion, Color.Black, false, title = {}, navigationIcon = {}) },
                    )
                }
            }
        }
        val capsuleAtRest = compose.onNodeWithTag("capsule:album-controls").fetchSemanticsNode().boundsInRoot
        var initialCompositions = 0
        compose.runOnIdle { initialCompositions = bodyCompositions }
        val samples = mutableMapOf<Int, Float>()
        for (offset in listOf(0, 80, 160, 260, 160, 80, 0)) {
            compose.runOnIdle { runBlocking { state.scrollToItem(0, offset) } }
            compose.waitForIdle()
            compose.runOnIdle {
                val progress = motion.toolbarTitleAlpha()
                assertTrue(progress in 0f..1f)
                assertEquals("Photo fades independently of title crossing the toolbar",
                    1f - motion.artworkFade(), motion.artworkAlpha(), .00001f)
                samples[offset]?.let { assertEquals("No direction-dependent lag", it, progress, 0.0001f) }
                samples[offset] = progress
            }
            val capsule = compose.onNodeWithTag("capsule:album-controls").fetchSemanticsNode().boundsInRoot
            assertEquals("Scroll changes position, not the capsule baseline", capsuleAtRest.top - offset, capsule.top, 2f)
            assertEquals(capsuleAtRest.height, capsule.height, 1f)
        }
        compose.runOnIdle {
            assertEquals("Foreground scroll reads must stay inside DetailTopAppBar", initialCompositions, bodyCompositions)
            assertEquals(0f, motion.toolbarTitleAlpha(), 0f)
        }
    }

    @Test fun headerPresenceIsReadBeforePostCompositionEffectsCanFlashThePinnedTitle() {
        var header by mutableStateOf(false)
        val observed = mutableListOf<Pair<Boolean, Float>>()
        compose.setContent {
            val motion = rememberDetailHeaderMotion(rememberLazyListState(), header)
            observed += header to motion.toolbarTitleAlpha()
            Box(Modifier.size(100.dp))
        }
        for (present in listOf(true, false, true)) {
            compose.runOnIdle { observed.clear(); header = present }
            compose.runOnIdle {
                assertTrue(observed.isNotEmpty())
                assertEquals("The first composition must use this frame's header presence",
                    present to (if (present) 0f else 1f), observed.first())
            }
        }
    }

    @Test fun captionStaysInArtworkWhileTopTitleAppearsOnlyAfterOcclusion() {
        lateinit var state: LazyListState
        lateinit var motion: DetailHeaderMotionState
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                CompositionLocalProvider(LocalPlayerAwareWindowInsets provides WindowInsets()) {
                    state = rememberLazyListState()
                    motion = rememberDetailHeaderMotion(state, true, "independent-titles")
                    AlbumScreenLayout(
                        background = Color.Black,
                        state = state,
                        modifier = Modifier.size(320.dp, 700.dp),
                        flingBehavior = rememberCapsuleFlingBehavior(),
                        content = {
                            item(key = "header") {
                                CapsuleDetailHero(
                                    title = "An album",
                                    metrics = emptyList(),
                                    artworkAlpha = motion::artworkAlpha,
                                    titleMotion = motion,
                                    artwork = { Box(Modifier.fillMaxSize().background(Color.Red)) },
                                    actions = {},
                                )
                            }
                            item { Spacer(Modifier.height(1200.dp)) }
                        },
                        topBar = {
                            DetailTopAppBar(
                                motion, Color.Black, false,
                                title = { DetailToolbarTitle("An album", motion) },
                                navigationIcon = { Box(Modifier.size(48.dp)) },
                            )
                        },
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("At rest only the artwork caption is visible",
                0f, motion.toolbarTitleAlpha(), 0f)
            assertEquals(1f, motion.coverTitleAlpha(), 0f)
        }
        compose.onNodeWithTag("capsule:detail-title").assertExists()
        compose.runOnIdle { runBlocking { state.scrollToItem(1, 0) } }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("Once covered, the pinned title must show",
                1f, motion.toolbarTitleAlpha(), 0f)
            assertEquals(0f, motion.coverTitleAlpha(), 0f)
        }
        compose.runOnIdle { runBlocking { state.scrollToItem(0, 0) } }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("Returning to the cover must restore original title",
                0f, motion.toolbarTitleAlpha(), 0f)
            assertEquals(1f, motion.coverTitleAlpha(), 0f)
        }
    }

    @Test fun twoLineLargeFontCaptionDoesNotChangeCapsuleBaseline() {
        var title by mutableStateOf("Short name")
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                    PlaylistHero(
                        thumbnails = emptyList(),
                        songCount = "13 songs",
                        title = title,
                        modifier = Modifier.size(320.dp, 700.dp),
                    ) {}
                }
            }
        }
        val before = compose.onNodeWithTag("capsule:album-controls").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { title = "Ugly is Beautiful: Shorter, Thicker & Uglier — a very long album name repeated again" }
        val after = compose.onNodeWithTag("capsule:album-controls").fetchSemanticsNode().boundsInRoot
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("capsule:detail-title").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(2, layouts.single().lineCount)
        assertTrue(layouts.single().isLineEllipsized(1))
        assertEquals(before.top, after.top, 1f)
        val caption = compose.onNodeWithTag("capsule:detail-title").fetchSemanticsNode().boundsInRoot
        assertTrue(caption.bottom < after.top)
    }
}
