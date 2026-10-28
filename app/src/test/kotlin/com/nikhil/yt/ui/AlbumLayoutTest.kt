package com.nikhil.yt.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.nikhil.yt.LocalPlayerAwareWindowInsets
import com.nikhil.yt.R
import com.nikhil.yt.ui.component.AlbumArtworkLayers
import com.nikhil.yt.ui.component.AlbumHeaderLayout
import com.nikhil.yt.ui.component.AlbumHeaderPlaceholder
import com.nikhil.yt.ui.component.AlbumScreenLayout
import com.nikhil.yt.ui.component.albumCoverHeight
import com.nikhil.yt.ui.component.CapsuleAlbumControls
import com.nikhil.yt.ui.component.CapsuleHeroMetric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w393dp-h851dp-xhdpi")
class AlbumLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @OptIn(ExperimentalMaterial3Api::class)
    @Test fun coverStartsAtTopAndBackButtonOverlaysItWithoutLosingSafeInsets() {
        lateinit var state: LazyListState
        var backClicks = 0
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                CompositionLocalProvider(
                    LocalPlayerAwareWindowInsets provides WindowInsets(left = 12.dp, top = 88.dp, right = 8.dp, bottom = 120.dp),
                ) {
                    state = rememberLazyListState()
                    AlbumScreenLayout(
                        background = Color.Black,
                        state = state,
                        modifier = Modifier.size(300.dp, 700.dp).testTag("screen"),
                        content = {
                            item {
                                AlbumArtworkLayers(ColorPainter(Color.White), null, Color.Black, Modifier.testTag("cover"))
                            }
                            item { Box(Modifier.fillMaxWidth().height(900.dp)) }
                        },
                    ) {
                        TopAppBar(
                            title = {},
                            windowInsets = WindowInsets(top = 24.dp),
                            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                            navigationIcon = {
                                IconButton(onClick = { backClicks++ }) {
                                    Icon(painterResource(R.drawable.arrow_back), contentDescription = "Back")
                                }
                            },
                        )
                    }
                }
            }
        }
        val screen = compose.onNodeWithTag("screen").fetchSemanticsNode().boundsInRoot
        val cover = compose.onNodeWithTag("cover").fetchSemanticsNode().boundsInRoot
        val back = compose.onNodeWithContentDescription("Back").fetchSemanticsNode().boundsInRoot
        val density = compose.density
        assertEquals("No toolbar-sized gap above the artwork", screen.top, cover.top, 1f)
        assertEquals(with(density) { 12.dp.toPx() }, cover.left - screen.left, 1f)
        assertEquals(with(density) { 8.dp.toPx() }, screen.right - cover.right, 1f)
        assertTrue("Back stays below the status bar", back.top >= screen.top + with(density) { 24.dp.toPx() })
        assertTrue("Back lies over the artwork", back.center.y in cover.top..cover.bottom)
        compose.runOnIdle {
            assertEquals(0, state.layoutInfo.beforeContentPadding)
            assertEquals(with(density) { 120.dp.roundToPx() }, state.layoutInfo.afterContentPadding)
        }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.runOnIdle { assertEquals(1, backClicks) }
    }

    @Test fun albumCoverCompactsOnlyWhenScreenHeightRequiresIt() {
        val narrow = albumCoverHeight(300.dp, 851.dp)
        val normal = albumCoverHeight(393.dp, 851.dp)
        val compact = albumCoverHeight(393.dp, 720.dp)
        assertEquals("The 44dp upper gap is absorbed into the photo itself",
            300.dp / 0.85f + 56.dp, narrow)
        assertTrue("A short screen should lift the album controls", compact < normal)
        assertTrue("A short screen still shows a substantial album cover", compact > 375.dp)
    }

    @Test fun loadingAlbumRetainsOriginalPortraitCoverAndUnifiedControlsOnLoad() {
        var loading by mutableStateOf(true)
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                CompositionLocalProvider(LocalPlayerAwareWindowInsets provides WindowInsets(top = 88.dp, bottom = 120.dp)) {
                    AlbumScreenLayout(
                        background = Color.Black,
                        state = rememberLazyListState(),
                        modifier = Modifier.size(300.dp, 700.dp),
                        content = {
                            item(key = "header") {
                                Box(Modifier.testTag("header")) {
                                    if (loading) {
                                        AlbumHeaderPlaceholder(Color.Black)
                                    } else {
                                        AlbumHeaderLayout(
                                            artwork = {
                                                AlbumArtworkLayers(
                                                    ColorPainter(Color.White), null, Color.Black,
                                                    Modifier.testTag("cover"),
                                                )
                                            },
                                            title = {},
                                            showTitle = false,
                                            artworkTopSpacing = com.nikhil.yt.ui.component.AlbumArtworkTopSpacing,
                                            metadata = {},
                                            actions = {
                                                CapsuleAlbumControls(
                                                    metrics = listOf(
                                                        CapsuleHeroMetric(R.drawable.music_note, "—"),
                                                        CapsuleHeroMetric(R.drawable.calendar_today, "—"),
                                                        CapsuleHeroMetric(R.drawable.timer, "—"),
                                                    ),
                                                ) {
                                                    repeat(5) {
                                                        Box(Modifier.weight(1f).height(60.dp))
                                                    }
                                                }
                                            },
                                            bottomSpacing = 34.dp,
                                        )
                                    }
                                }
                            }
                            item(key = "song") { Box(Modifier.fillMaxWidth().height(48.dp).testTag("song")) }
                        },
                        topBar = {},
                    )
                }
            }
        }
        val loadingHeader = compose.onNodeWithTag("header").fetchSemanticsNode().boundsInRoot
        val loadingSong = compose.onNodeWithTag("song").fetchSemanticsNode().boundsInRoot
        compose.onAllNodes(hasClickAction()).assertCountEquals(0)
        compose.runOnIdle { loading = false }
        val loadedHeader = compose.onNodeWithTag("header").fetchSemanticsNode().boundsInRoot
        val loadedSong = compose.onNodeWithTag("song").fetchSemanticsNode().boundsInRoot
        val cover = compose.onNodeWithTag("cover").fetchSemanticsNode().boundsInRoot
        val capsule = compose.onNodeWithTag("capsule:album-controls").fetchSemanticsNode().boundsInRoot
        val metrics = compose.onNodeWithTag("capsule:album-metrics").fetchSemanticsNode().boundsInRoot
        val actions = compose.onNodeWithTag("capsule:album-actions").fetchSemanticsNode().boundsInRoot
        assertEquals(loadingHeader.top, loadedHeader.top, 1f)
        assertEquals("No visible upper gap: portrait starts flush with the page",
            loadedHeader.top, cover.top, 2f)
        assertEquals(loadingHeader.height, loadedHeader.height, 2f)
        assertEquals("Loading and content must share the original album layout", loadingSong.top, loadedSong.top, 2f)
        assertEquals("Album cover should fill its width as before", loadedHeader.width, cover.width, 2f)
        assertEquals("Former top-gap pixels must extend the portrait instead",
            cover.width / 0.85f + with(compose.density) { 56.dp.toPx() }, cover.height, 2f)
        assertTrue("Statistics are inside the same rounded capsule", metrics.top >= capsule.top)
        assertTrue("Buttons are inside the same rounded capsule", actions.bottom <= capsule.bottom)
        assertTrue("Stats sit above controls in one uninterrupted surface", metrics.bottom <= actions.top)
        assertTrue("Album capsule surrounds its complete set of contents", capsule.left <= metrics.left && capsule.right >= actions.right)
    }
}
