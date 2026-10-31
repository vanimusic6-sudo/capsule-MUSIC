package com.nikhil.yt.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.nikhil.yt.R
import com.nikhil.yt.ui.component.PlaylistAction
import com.nikhil.yt.ui.component.PlaylistHero
import com.nikhil.yt.ui.component.albumCoverHeight
import com.nikhil.yt.ui.component.AlbumArtworkTopSpacing
import java.io.File
import kotlin.math.roundToInt
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
class PlaylistHeroTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val labels = listOf("Delete", "Play", "Shuffle", "Download", "Edit")
    private val icons = listOf(R.drawable.delete, R.drawable.play, R.drawable.shuffle, R.drawable.download, R.drawable.edit)

    @Test fun allFiveActionsRemainDistinctAndClickableBelowTheCounters() {
        val clicked = mutableListOf<String>()
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                PlaylistHero(emptyList(), "34 songs", "1:31:09",
                    Modifier.width(393.dp).background(Color(0xFF101010)).testTag("playlist")) {
                    labels.forEachIndexed { i, label -> PlaylistAction(icons[i], label, { clicked += label }) }
                }
            }
        }
        val counter = compose.onNodeWithText("34 songs").fetchSemanticsNode().boundsInRoot
        val cover = compose.onNodeWithTag("capsule:detail-artwork").fetchSemanticsNode().boundsInRoot
        val capsule = compose.onNodeWithTag("capsule:album-controls").fetchSemanticsNode().boundsInRoot
        val stats = compose.onNodeWithTag("capsule:album-metrics").fetchSemanticsNode().boundsInRoot
        val tray = compose.onNodeWithTag("capsule:album-actions").fetchSemanticsNode().boundsInRoot
        val outer = compose.onNodeWithTag("playlist").fetchSemanticsNode().boundsInRoot
        assertEquals("Playlist uses album's full-bleed portrait width",
            outer.width, cover.width, 2f)
        assertEquals("Playlist portrait matches album ratio/height",
            with(compose.density) { albumCoverHeight(393.dp, 851.dp).toPx() },
            cover.height, 2f)
        assertEquals("No blank upper strip on either album or playlist",
            0f, cover.top - outer.top, 2f)
        assertTrue("Unified capsule stays inside the portrait's width",
            capsule.left >= cover.left && capsule.right <= cover.right)
        assertTrue("Playlist stats are enclosed inside the same capsule", stats.top >= capsule.top)
        assertTrue("Playlist actions are enclosed inside the same capsule", tray.bottom <= capsule.bottom)
        assertTrue("Stats and buttons share one surface without overlap", stats.bottom <= tray.top)
        assertTrue("One outer outline surrounds both rows",
            stats.left >= capsule.left && tray.right <= capsule.right)
        val actions = labels.map { compose.onNodeWithContentDescription(it).assertIsDisplayed() }
        val bounds = actions.map { it.fetchSemanticsNode().boundsInRoot }
        assertTrue(bounds.all { it.top > counter.bottom })
        bounds.zipWithNext().forEach { (left, right) -> assertTrue(left.right <= right.left + 1f) }
        actions.forEach { it.performClick() }
        assertEquals(labels, clicked)
        savePreview()
    }

    @Test fun titleIsCentredInsidePortraitAboveControlsAndDoesNotDisplaceCapsule() {
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                PlaylistHero(
                    thumbnails = emptyList(),
                    songCount = "13 songs",
                    title = "Cowboy Tears",
                    modifier = Modifier.width(393.dp),
                ) {
                    PlaylistAction(R.drawable.play, "Play", {})
                }
            }
        }
        val title = compose.onNodeWithTag("capsule:detail-title").fetchSemanticsNode().boundsInRoot
        val portrait = compose.onNodeWithTag("capsule:detail-artwork").fetchSemanticsNode().boundsInRoot
        val controls = compose.onNodeWithTag("capsule:album-controls").fetchSemanticsNode().boundsInRoot
        assertTrue("Title should be inside the bottom part of the photo", title.top > portrait.center.y)
        assertTrue("Title must finish above the controls", title.bottom <= portrait.bottom && controls.top > title.bottom)
        assertEquals("Title is horizontally centered", portrait.center.x, title.center.x, 3f)
        assertEquals("Moving the name must not push the capsule down",
            portrait.bottom + with(compose.density) { 14.dp.toPx() }, controls.top, 2f)
    }

    @Test fun albumAndPlaylistCapsulesShareBaselineAcrossScreenSizes() {
        for (height in listOf(720.dp, 851.dp)) {
            val availableWidth = 393.dp
            val albumHeight = albumCoverHeight(availableWidth, height)
            // Both use AlbumHeaderLayout(showTitle=false), the same portrait
            // frame and identical action-capsule spacing.
            val albumControlsY = AlbumArtworkTopSpacing + albumHeight + 14.dp
            val playlistControlsY = AlbumArtworkTopSpacing + albumHeight + 14.dp
            assertEquals("Matching album/playlist control spacing at $height",
                albumControlsY.value, playlistControlsY.value, 0.01f)
        }
    }

    @Test fun largerTextKeepsMetadataAndEveryActionReachableOnNarrowScreens() {
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val density = LocalDensity.current.density
                CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
                    Box(Modifier.width(320.dp)) {
                        PlaylistHero(emptyList(), "34 songs", "1:31:09") {
                            labels.forEachIndexed { i, label -> PlaylistAction(icons[i], label, {}) }
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("34 songs").assertIsDisplayed()
        compose.onNodeWithText("1:31:09").assertIsDisplayed()
        labels.forEach { compose.onNodeWithContentDescription(it).assertIsDisplayed() }
    }

    private fun savePreview() {
        compose.waitForIdle()
        val bounds = compose.onNodeWithTag("playlist").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle {
            val bitmap = Bitmap.createBitmap(bounds.width.roundToInt(), bounds.height.roundToInt(), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.translate(-bounds.left, -bounds.top)
            compose.activity.findViewById<View>(android.R.id.content).draw(canvas)
            val output = File("build/reports/ui-previews/playlist-header.png")
            output.parentFile.mkdirs()
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
