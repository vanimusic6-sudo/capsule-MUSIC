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
        val actions = labels.map { compose.onNodeWithContentDescription(it).assertIsDisplayed() }
        val bounds = actions.map { it.fetchSemanticsNode().boundsInRoot }
        assertTrue(bounds.all { it.top > counter.bottom })
        bounds.zipWithNext().forEach { (left, right) -> assertTrue(left.right <= right.left + 1f) }
        actions.forEach { it.performClick() }
        assertEquals(labels, clicked)
        savePreview()
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
