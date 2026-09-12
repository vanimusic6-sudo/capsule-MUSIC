package com.nikhil.yt.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.nikhil.yt.R
import com.nikhil.yt.ui.component.ArtistHero
import com.nikhil.yt.ui.component.ArtistHeroLayout
import java.io.File
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w393dp-h851dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArtistHeroTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun portraitHasRoomForTheFullCompositionAndControlsRemainOnIt() {
        compose.setContent {
            ArtistHeroLayout(
                background = Color.Black,
                modifier = Modifier.width(393.dp).testTag("hero"),
                topSafePadding = 24.dp,
                artwork = { Box(Modifier.fillMaxSize().background(Color.Red).testTag("portrait")) },
                title = { Box(Modifier.fillMaxWidth().height(39.dp).testTag("title")) },
                actions = { Box(Modifier.fillMaxWidth().height(106.dp).testTag("actions")) },
            )
        }
        val hero = compose.onNodeWithTag("hero").fetchSemanticsNode().boundsInRoot
        val portrait = compose.onNodeWithTag("portrait").fetchSemanticsNode().boundsInRoot
        val title = compose.onNodeWithTag("title").fetchSemanticsNode().boundsInRoot
        val actions = compose.onNodeWithTag("actions").fetchSemanticsNode().boundsInRoot
        assertTrue(portrait.height > portrait.width * 1.5f)
        assertEquals(hero.top, portrait.top, 1f)
        assertEquals(hero.width, portrait.width, 1f)
        assertTrue(title.bottom < actions.top)
        assertTrue(actions.bottom < portrait.bottom)
    }

    @Test fun loadingUsesTheSameHeaderAndActionsWorkAfterLoading() {
        var loading by mutableStateOf(true)
        var clicks = 0
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                ArtistHero(
                    name = if (loading) "" else "Pyrokinesis",
                    thumbnailUrl = null,
                    background = Color(0xFF090909),
                    subscribed = false,
                    canSubscribe = true,
                    canShuffle = true,
                    showRadio = true,
                    canRadio = true,
                    onSubscribe = { clicks++ },
                    onShuffle = { clicks++ },
                    onRadio = { clicks++ },
                    loading = loading,
                    modifier = Modifier.width(393.dp).testTag("hero"),
                )
            }
        }
        val before = compose.onNodeWithTag("hero").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { loading = false }
        val after = compose.onNodeWithTag("hero").fetchSemanticsNode().boundsInRoot
        assertEquals(before.height, after.height, 1f)
        listOf(R.string.subscribe, R.string.shuffle, R.string.radio).forEach {
            compose.onNodeWithText(compose.activity.getString(it)).assertIsDisplayed().performClick()
        }
        assertEquals(3, clicks)
        savePreview("artist-capsule-header")
    }

    @Test fun largeTextKeepsAllActionsReachableWithoutOverlap() {
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val density = LocalDensity.current.density
                CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
                    ArtistHero(
                        name = "Very long artist name",
                        thumbnailUrl = null,
                        background = Color.Black,
                        subscribed = false,
                        canSubscribe = true,
                        canShuffle = true,
                        showRadio = true,
                        canRadio = true,
                        onSubscribe = {}, onShuffle = {}, onRadio = {},
                        modifier = Modifier.width(320.dp).testTag("hero"),
                    )
                }
            }
        }
        val buttons = listOf(R.string.subscribe, R.string.shuffle, R.string.radio).map {
            compose.onNodeWithText(compose.activity.getString(it)).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        }
        assertTrue(buttons[0].bottom <= buttons[1].top)
        assertTrue(buttons[1].bottom <= buttons[2].top)
        val hero = compose.onNodeWithTag("hero").fetchSemanticsNode().boundsInRoot
        assertTrue(buttons.last().bottom <= hero.bottom)
        savePreview("artist-large-text")
    }


    @Test fun uncroppedPhotoKeepsItsEdgesAndLowerBodyVisible() {
        val source = Bitmap.createBitmap(120, 180, Bitmap.Config.ARGB_8888)
        source.eraseColor(android.graphics.Color.rgb(40, 70, 150))
        val painter = android.graphics.Paint()
        val sourceCanvas = Canvas(source)
        painter.color = android.graphics.Color.rgb(220, 50, 47)
        sourceCanvas.drawRect(0f, 0f, 4f, 180f, painter)
        painter.color = android.graphics.Color.rgb(13, 187, 170)
        sourceCanvas.drawRect(116f, 0f, 120f, 180f, painter)
        val photo = File(compose.activity.cacheDir, "artist-portrait-fixture.png")
        photo.outputStream().use { source.compress(Bitmap.CompressFormat.PNG, 100, it) }
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                ArtistHero(
                    name = "Pyrokinesis", thumbnailUrl = "file://${photo.absolutePath}",
                    background = Color(0xFF090909), subscribed = false,
                    canSubscribe = true, canShuffle = true, showRadio = true, canRadio = true,
                    onSubscribe = {}, onShuffle = {}, onRadio = {},
                    modifier = Modifier.width(393.dp).testTag("hero"),
                )
            }
        }
        fun capture(): Bitmap {
            val bounds = compose.onNodeWithTag("hero").fetchSemanticsNode().boundsInRoot
            lateinit var result: Bitmap
            compose.runOnIdle {
                result = Bitmap.createBitmap(bounds.width.roundToInt(), bounds.height.roundToInt(), Bitmap.Config.ARGB_8888)
                val canvas = Canvas(result)
                canvas.translate(-bounds.left, -bounds.top)
                compose.activity.findViewById<View>(android.R.id.content).draw(canvas)
            }
            return result
        }
        compose.waitUntil(timeoutMillis = 15_000) {
            val image = capture()
            val x = (image.width * 0.02f).roundToInt()
            val y = (image.width * 1.5f * 0.24f).roundToInt()
            android.graphics.Color.red(image.getPixel(x, y)) > 100
        }
        val image = capture()
        val edgeY = (image.width * 1.5f * 0.24f).roundToInt()
        val right = image.getPixel((image.width * 0.98f).roundToInt(), edgeY)
        assertTrue("The right edge of the original photograph is cropped", android.graphics.Color.green(right) > 100)
        val lowerBody = image.getPixel(image.width / 2, (image.width * 1.5f * 0.7f).roundToInt())
        assertTrue("The fade hides the lower portrait too early", android.graphics.Color.blue(lowerBody) > 70)
        savePreview("artist-full-portrait")
    }

    private fun savePreview(name: String) {
        compose.waitForIdle()
        val bounds = compose.onNodeWithTag("hero").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle {
            val bitmap = Bitmap.createBitmap(bounds.width.roundToInt(), bounds.height.roundToInt(), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.translate(-bounds.left, -bounds.top)
            compose.activity.findViewById<View>(android.R.id.content).draw(canvas)
            val file = File("build/reports/ui-previews/$name.png")
            file.parentFile.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
