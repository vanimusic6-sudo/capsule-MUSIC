package com.nikhil.yt.ui

import androidx.compose.ui.platform.LocalInspectionMode
import coil3.annotation.ExperimentalCoilApi
import coil3.asImage
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
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
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.nikhil.yt.R
import com.nikhil.yt.ui.component.ArtistHero
import com.nikhil.yt.ui.component.ArtistHeroLayout
import com.nikhil.yt.ui.component.ArtistToolbar
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
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArtistHeroTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun artistElementsFollowTheReferencePositionsAtPhoneWidth() {
        compose.setContent {
            ArtistHeroLayout(
                background = Color.Black,
                modifier = Modifier.width(360.dp).testTag("hero"),
                artwork = { Box(Modifier.fillMaxSize().background(Color.Red).testTag("portrait")) },
                title = { Box(Modifier.fillMaxWidth().height(36.dp).testTag("title")) },
                actions = { Box(Modifier.fillMaxWidth().height(110.dp).testTag("actions")) },
            )
        }
        val hero = compose.onNodeWithTag("hero").fetchSemanticsNode().boundsInRoot
        val portrait = compose.onNodeWithTag("portrait").fetchSemanticsNode().boundsInRoot
        val title = compose.onNodeWithTag("title").fetchSemanticsNode().boundsInRoot
        val actions = compose.onNodeWithTag("actions").fetchSemanticsNode().boundsInRoot
        val dp = hero.width / 360f
        // The original reference composition is lowered by 16 dp, without a separate title offset.
        assertEquals(hero.width * 1.69f + 16f * dp, hero.height, 2f)
        assertEquals(hero.top + hero.width * 0.06f, portrait.top, 2f)
        assertEquals(hero.width * 1.30f, portrait.height, 2f)
        assertEquals(hero.width, portrait.width, 1f)
        assertEquals(hero.top + 440.4f * dp, title.top, 2f)
        assertEquals(hero.left + 14f * dp, title.left, 1f)
        assertEquals(hero.top + 500.4f * dp, actions.top, 2f)
        assertEquals(hero.left + 22f * dp, actions.left, 1f)
        assertTrue(title.bottom < actions.top)
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
                    modifier = Modifier.width(360.dp).testTag("hero"),
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


    @OptIn(ExperimentalCoilApi::class)
    @Test fun wideArtistArtworkFillsThePortraitInsteadOfLeavingAPlaceholderBand() {
        // The real artist source is a 3:2 banner, not a tall portrait.
        val source = Bitmap.createBitmap(180, 120, Bitmap.Config.ARGB_8888)
        source.eraseColor(android.graphics.Color.rgb(180, 60, 30))
        val painter = android.graphics.Paint()
        val sourceCanvas = Canvas(source)
        painter.color = android.graphics.Color.rgb(16, 28, 48)
        sourceCanvas.drawRect(52f, 48f, 128f, 120f, painter)
        painter.color = android.graphics.Color.rgb(245, 148, 40)
        sourceCanvas.drawOval(67f, 6f, 101f, 48f, painter)
        val previewHandler = AsyncImagePreviewHandler { source.asImage() }
        compose.setContent {
            CompositionLocalProvider(
                LocalInspectionMode provides true,
                LocalAsyncImagePreviewHandler provides previewHandler,
            ) {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Box(Modifier.width(360.dp).testTag("hero")) {
                        ArtistHero(
                            name = "Pyrokinesis", thumbnailUrl = "test://wide-artist-artwork",
                            background = Color(0xFF090909), subscribed = false,
                            canSubscribe = true, canShuffle = true, showRadio = true, canRadio = true,
                            onSubscribe = {}, onShuffle = {}, onRadio = {},
                        )
                        ArtistToolbar("", true, true, {}, {}, {}, {})
                    }
                }
            }
        }
        savePreview("artist-full-portrait")
        val image = capture("hero")
        compose.onNodeWithTag("artist-artwork-placeholder").assertDoesNotExist()
        val side = image.getPixel((image.width * 0.04f).roundToInt(), (image.width * 0.82f).roundToInt())
        assertTrue("Wide photos must continue below the former empty band", android.graphics.Color.red(side) > 100)
        val head = image.getPixel((image.width * 0.48f).roundToInt(), (image.width * 0.30f).roundToInt())
        assertTrue("The subject must be enlarged and framed at the reference height",
            android.graphics.Color.red(head) > 200 && android.graphics.Color.green(head) > 100)
        val bottom = image.getPixel((image.width * 0.04f).roundToInt(), (image.width * 1.35f).roundToInt())
        assertTrue("The photograph must fade into the page without a hard lower edge",
            android.graphics.Color.red(bottom) < 20)
        val aboveJoin = image.getPixel((image.width * 0.04f).roundToInt(), (image.width * 1.355f).roundToInt())
        val belowJoin = image.getPixel((image.width * 0.04f).roundToInt(), (image.width * 1.365f).roundToInt())
        assertTrue("The photo edge must not create a visible seam",
            kotlin.math.abs(android.graphics.Color.red(aboveJoin) - android.graphics.Color.red(belowJoin)) <= 3)
    }

    @Test fun toolbarKeepsTitleAndIconsAlignedWhileCollapsingAndPreservesAllActions() {
        val clicks = mutableListOf<String>()
        var overArtwork by mutableStateOf(true)
        var safeTop = 0
        compose.setContent {
            safeTop = WindowInsets.safeDrawing.getTop(LocalDensity.current)
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.width(360.dp).background(Color(0xFF824634)).testTag("toolbar")) {
                    ArtistToolbar("Pyrokinesis", overArtwork, true,
                        onBack = { clicks += "back" },
                        onBackLongClick = {},
                        onCopyLink = { clicks += "copy" },
                        onShare = { clicks += "share" },
                    )
                }
            }
        }
        val bounds = compose.onNodeWithTag("toolbar").fetchSemanticsNode().boundsInRoot
        val dp = bounds.width / 360f
        val controls = listOf(R.string.back, R.string.copy_link, R.string.share).map {
            compose.onNodeWithContentDescription(compose.activity.getString(it)).assertIsDisplayed()
        }
        controls.forEach {
            assertEquals(bounds.top + safeTop + 48f * dp, it.fetchSemanticsNode().boundsInRoot.center.y, 1f)
        }
        val bitmap = capture("toolbar")
        val emptyCorner = bitmap.getPixel((28f * dp).roundToInt(), (18f * dp).roundToInt())
        assertEquals("No permanent circular backdrop behind navigation icons", 0x82, android.graphics.Color.red(emptyCorner))
        controls.forEach { it.performClick() }
        assertEquals(listOf("back", "copy", "share"), clicks)

        // Scroll changes the toolbar mode. Check alignment during the transition, not
        // just after it: a per-icon vertical offset previously left buttons below the name.
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { overArtwork = false }
        for (frameTime in listOf(80L, 80L, 160L)) {
            compose.mainClock.advanceTimeBy(frameTime)
            val title = compose.onNodeWithText("Pyrokinesis").fetchSemanticsNode().boundsInRoot
            controls.forEach {
                assertEquals("Navigation and artist name must share a centre throughout collapse",
                    title.center.y, it.fetchSemanticsNode().boundsInRoot.center.y, 1f)
            }
        }
        compose.mainClock.autoAdvance = true
        val compact = compose.onNodeWithTag("toolbar").fetchSemanticsNode().boundsInRoot
        assertEquals(safeTop + 64f * dp, compact.height, 1f)
        controls.forEach { it.performClick() }
        assertEquals(listOf("back", "copy", "share", "back", "copy", "share"), clicks)

        compose.runOnIdle { overArtwork = true }
        compose.onNodeWithText("Pyrokinesis").assertDoesNotExist()
        controls.forEach {
            assertEquals(bounds.top + safeTop + 48f * dp,
                it.fetchSemanticsNode().boundsInRoot.center.y, 1f)
        }
    }

    private fun capture(tag: String): Bitmap {
        compose.waitForIdle()
        val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        lateinit var bitmap: Bitmap
        compose.runOnIdle {
            bitmap = Bitmap.createBitmap(bounds.width.roundToInt(), bounds.height.roundToInt(), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.translate(-bounds.left, -bounds.top)
            compose.activity.findViewById<View>(android.R.id.content).draw(canvas)
        }
        return bitmap
    }

    private fun savePreview(name: String) {
        val bitmap = capture("hero")
        val file = File("build/reports/ui-previews/$name.png")
        file.parentFile.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
