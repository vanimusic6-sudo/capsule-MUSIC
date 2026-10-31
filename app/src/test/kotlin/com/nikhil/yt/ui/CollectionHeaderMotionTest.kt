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
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.nikhil.yt.LocalPlayerAwareWindowInsets
import com.nikhil.yt.ui.component.*
import kotlinx.coroutines.runBlocking
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

    @Test fun decodedPixelsReachWindowEdgeAndRemainVisibleBelowToolbarInBothThemes() {
        var decoded by mutableStateOf(false)
        var light by mutableStateOf(false)
        compose.setContent {
            MaterialTheme(colorScheme = if (light) lightColorScheme() else darkColorScheme()) {
                CompositionLocalProvider(LocalPlayerAwareWindowInsets provides WindowInsets()) {
                    val state = rememberLazyListState()
                    val motion = rememberDetailHeaderMotion(state, hasHeader = true)
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
                assertEquals(1f, progress + motion.artworkAlpha(), 0.00001f)
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
