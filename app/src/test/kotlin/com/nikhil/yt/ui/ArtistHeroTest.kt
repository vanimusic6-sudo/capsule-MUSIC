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
import androidx.compose.foundation.layout.offset
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
        // The current Capsule hero lets artwork reach the physical top edge and uses the matte
        // overlay itself to soften the status/navigation area. Text/actions keep their reference
        // anchors so the magnetic lower composition remains unchanged.
        assertEquals(hero.width * 1.25f + 136f * dp, hero.height, 2f)
        assertEquals(hero.top, portrait.top, 2f)
        assertEquals(hero.width * 1.25f, portrait.height, 2f)
        assertEquals(hero.width, portrait.width, 1f)
        assertEquals(hero.top + 402f * dp, title.top, 2f)
        assertEquals(hero.left + 14f * dp, title.left, 1f)
        assertEquals(hero.top + 462f * dp, actions.top, 2f)
        assertEquals("First actions must start BELOW the full-height portrait",
            portrait.bottom + 12f * dp, actions.top, 2f)
        assertEquals(hero.left + 22f * dp, actions.left, 1f)
        assertTrue(title.bottom < actions.top)
    }

    @Test fun livingArtistPortraitExtendsToButtonsWithoutMovingNextContent() {
        var extension by mutableStateOf(62.dp)
        compose.setContent {
            ArtistHeroLayout(
                background = Color.Black,
                modifier = Modifier.width(360.dp).testTag("hero"),
                artworkExtension = extension,
                artwork = { Box(Modifier.fillMaxSize().background(Color.Red)) },
                title = {},
                actions = {
                    Box(
                        Modifier.offset(y = extension).fillMaxWidth()
                            .height(48.dp).testTag("first-action")
                    )
                },
            )
        }
        fun bounds(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        val dp = bounds("hero").width / 360f
        val baselineHeroHeight = bounds("hero").height
        val firstAction = bounds("first-action")
        val longerPhoto = bounds("artist-artwork-field")
        assertEquals("Living artist photo must end just 12dp above actions",
            longerPhoto.bottom + 12f * dp, firstAction.top, 2f)
        compose.runOnIdle { extension = 0.dp }
        val memorialPhoto = bounds("artist-artwork-field")
        val memorialAction = bounds("first-action")
        assertEquals("Memorial photo must also stop 12dp before actions",
            memorialPhoto.bottom + 12f * dp, memorialAction.top, 2f)
        assertEquals("The hero and following items must not jump as the candle appears",
            baselineHeroHeight, bounds("hero").height, 1f)
        assertEquals(62f * dp, longerPhoto.height - memorialPhoto.height, 2f)
    }

    @Test fun artistGradientSoftlyReachesPageInsteadOfHardCuttingAtPhotoEdge() {
        // Reproduces the pyrokinesis regression: the full-size portrait had
        // colour until its final few pixels, then jumped to flat dark surface.
        // Use an unmistakable red source to test the actual rendered gradient
        // (not just its end points) on the 62dp living-artist extension.
        compose.setContent {
            ArtistHeroLayout(
                background = Color.Black,
                modifier = Modifier.width(360.dp).testTag("hero"),
                artworkExtension = 62.dp,
                artwork = { Box(Modifier.fillMaxSize().background(Color.Red)) },
                title = {},
                actions = {},
            )
        }
        val photoHeight = compose.onNodeWithTag("artist-artwork-field")
            .fetchSemanticsNode().boundsInRoot.height
        val bitmap = capture("hero")
        fun redAt(fraction: Float): Int = android.graphics.Color.red(bitmap.getPixel(
            bitmap.width / 4,
            (photoHeight * fraction).roundToInt().coerceIn(0, bitmap.height - 1),
        ))
        val at65 = redAt(0.65f)
        val at80 = redAt(0.80f)
        val at90 = redAt(0.90f)
        val at97 = redAt(0.97f)
        val at995 = redAt(0.995f)
        assertTrue("Photo should still be visible well before the fade", at65 > 240)
        assertTrue("Gradient should already be visible by 80%: $at80", at80 < at65 - 25)
        assertTrue("The photo must fade gradually from 80 to 90%", at90 < at80 - 35)
        assertTrue("The photo must keep fading from 90 to 97%", at97 < at90 - 35)
        assertTrue("The last 3% must reach the exact page surface", at995 <= 3)
        assertTrue("The final 3% should not be an abrupt jump", at97 < 35)
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
                    showCandle = true,
                    
                    onSubscribe = { clicks++ },
                    onShuffle = { clicks++ },
                    onCandle = { clicks++ },
                    loading = loading,
                    modifier = Modifier.width(360.dp).testTag("hero"),
                )
            }
        }
        val before = compose.onNodeWithTag("hero").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { loading = false }
        val after = compose.onNodeWithTag("hero").fetchSemanticsNode().boundsInRoot
        assertEquals(before.height, after.height, 1f)
        listOf(R.string.subscribe, R.string.shuffle, R.string.artist_light_candle).forEach {
            compose.onNodeWithText(compose.activity.getString(it)).assertIsDisplayed().performClick()
        }
        assertEquals(3, clicks)
        savePreview("artist-capsule-header")
    }

    @Test fun livingArtistHasNoBlankMemorialSlotBelowButtons() {
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                ArtistHero(
                    name = "Living artist",
                    thumbnailUrl = null,
                    background = Color(0xFF141414),
                    subscribed = false,
                    canSubscribe = true,
                    canShuffle = true,
                    showCandle = false,
                    onSubscribe = {}, onShuffle = {}, onCandle = {},
                    modifier = Modifier.width(360.dp).testTag("hero"),
                )
            }
        }
        val hero = compose.onNodeWithTag("hero").fetchSemanticsNode().boundsInRoot
        val subscribe = compose.onNodeWithText(compose.activity.getString(R.string.subscribe))
            .fetchSemanticsNode().boundsInRoot
        // Buttons should sit just above the hero's normal 14dp bottom inset,
        // not leave a 62dp empty candle placeholder beneath them.
        val dp = hero.width / 360f
        assertTrue("Unexpected blank candle slot: ${hero.bottom - subscribe.bottom}",
            hero.bottom - subscribe.bottom <= 35f * dp)
    }

    @Test fun lateMemorialButtonOnlyMovesActionsInsideFixedHeader() {
        var hasMemorial by mutableStateOf(false)
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                ArtistHero(
                    name = "Late memorial",
                    thumbnailUrl = null,
                    background = Color(0xFF141414),
                    subscribed = false,
                    canSubscribe = true,
                    canShuffle = true,
                    showCandle = hasMemorial,
                    onSubscribe = {}, onShuffle = {}, onCandle = {},
                    modifier = Modifier.width(360.dp).testTag("hero"),
                )
            }
        }
        val beforeHeader = compose.onNodeWithTag("hero").fetchSemanticsNode().boundsInRoot
        val beforeSubscribe = compose.onNodeWithText(compose.activity.getString(R.string.subscribe))
            .fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { hasMemorial = true }
        compose.waitForIdle()
        val afterHeader = compose.onNodeWithTag("hero").fetchSemanticsNode().boundsInRoot
        val afterSubscribe = compose.onNodeWithText(compose.activity.getString(R.string.subscribe))
            .fetchSemanticsNode().boundsInRoot
        assertEquals(beforeHeader.height, afterHeader.height, 0.1f)
        assertTrue("Subscription should slide upward within the stable header",
            afterSubscribe.top < beforeSubscribe.top)
        compose.onNodeWithText(compose.activity.getString(R.string.artist_light_candle)).assertIsDisplayed()
    }

    @Test fun livingArtistHasNoRadioAndNoMemorialAction() {
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                ArtistHero(
                    name = "Living artist", thumbnailUrl = null,
                    background = Color.Black, subscribed = false,
                    canSubscribe = true, canShuffle = true, showCandle = false,
                    onSubscribe = {}, onShuffle = {}, onCandle = {},
                )
            }
        }
        compose.onNodeWithText(compose.activity.getString(R.string.radio)).assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.artist_light_candle)).assertDoesNotExist()
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
                        showCandle = true,
                        
                        onSubscribe = {}, onShuffle = {}, onCandle = {},
                        modifier = Modifier.width(320.dp).testTag("hero"),
                    )
                }
            }
        }
        val buttons = listOf(R.string.subscribe, R.string.shuffle, R.string.artist_light_candle).map {
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
                            canSubscribe = true, canShuffle = true, showCandle = true, 
                            onSubscribe = {}, onShuffle = {}, onCandle = {},
                        )
                        ArtistToolbar("", true, true, {}, {}, {}, {})
                    }
                }
            }
        }
        savePreview("artist-full-portrait")
        val image = capture("hero")
        compose.onNodeWithTag("artist-artwork-placeholder").assertDoesNotExist()
        val side = image.getPixel((image.width * 0.04f).roundToInt(), (image.width * 0.66f).roundToInt())
        assertTrue("Wide photos must continue below the former empty band", android.graphics.Color.red(side) > 100)
        val head = image.getPixel((image.width * 0.48f).roundToInt(), (image.width * 0.30f).roundToInt())
        assertTrue("The subject must be enlarged and framed at the reference height",
            android.graphics.Color.red(head) > 200 && android.graphics.Color.green(head) > 100)
        val bottom = image.getPixel((image.width * 0.04f).roundToInt(), (image.width * (com.nikhil.yt.ui.component.ArtistHeroArtworkHeightFactor - 0.01f)).roundToInt())
        assertTrue("The photograph must fade into the page without a hard lower edge",
            android.graphics.Color.red(bottom) < 20)
        val aboveJoin = image.getPixel((image.width * 0.04f).roundToInt(), (image.width * (com.nikhil.yt.ui.component.ArtistHeroArtworkHeightFactor - 0.005f)).roundToInt())
        val belowJoin = image.getPixel((image.width * 0.04f).roundToInt(), (image.width * (com.nikhil.yt.ui.component.ArtistHeroArtworkHeightFactor + 0.005f)).roundToInt())
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
            assertEquals(bounds.top + safeTop + 32f * dp, it.fetchSemanticsNode().boundsInRoot.center.y, 1f)
        }
        val bitmap = capture("toolbar")
        val emptyCorner = bitmap.getPixel((28f * dp).roundToInt(), (18f * dp).roundToInt())
        assertEquals("No permanent circular backdrop behind navigation icons", 0x82, android.graphics.Color.red(emptyCorner))
        controls.forEach { it.performClick() }
        assertEquals(listOf("back", "copy", "share"), clicks)

        // Scroll changes only the toolbar surface/title state. Its geometry stays pinned;
        // check alignment during the title fade as well as after it settles.
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { overArtwork = false }
        for (frameTime in listOf(80L, 80L, 160L)) {
            compose.mainClock.advanceTimeBy(frameTime)
            compose.waitForIdle()
            // The title is always present; only the toolbar surface/foreground mode changes.
            val title = compose.onNodeWithTag("artist-toolbar-title", useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
            controls.forEach {
                assertEquals("Navigation and artist name must share a centre throughout collapse",
                    title.center.y, it.fetchSemanticsNode().boundsInRoot.center.y, 1f)
            }
        }
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText("Pyrokinesis").assertIsDisplayed()
        val compact = compose.onNodeWithTag("toolbar").fetchSemanticsNode().boundsInRoot
        assertEquals(safeTop + 64f * dp, compact.height, 1f)
        controls.forEach { it.performClick() }
        assertEquals(listOf("back", "copy", "share", "back", "copy", "share"), clicks)

        compose.runOnIdle { overArtwork = true }
        compose.onNodeWithText("Pyrokinesis").assertIsDisplayed()
        controls.forEach {
            assertEquals(bounds.top + safeTop + 32f * dp,
                it.fetchSemanticsNode().boundsInRoot.center.y, 1f)
        }
    }

    @Test fun toolbarAndPhotoHaveIdenticalToneWhilePortraitIsVisible() {
        var fade by mutableStateOf(0.25f)
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.width(360.dp).background(Color(0xFF121212)).testTag("artist-seam")) {
                    ArtistHeroLayout(
                        background = Color(0xFF121212),
                        artwork = { Box(Modifier.fillMaxSize().background(Color(0xFF824634))) },
                        title = {}, actions = {},
                        artworkAlpha = { 1f - fade },
                    )
                    ArtistToolbar("Artist", true, false, {}, {}, {}, {}, artworkFade = fade)
                }
            }
        }
        for (fraction in listOf(0.25f, 0.50f, 0.75f)) {
            compose.runOnIdle { fade = fraction }
            val bitmap = capture("artist-seam")
            val px = bitmap.width / 360f
            // Both samples are inside the unchanged photograph; the first one
            // is underneath the pinned toolbar. A second alpha scrim previously
            // darkened only that top sample on every intermediate frame.
            val x = (4 * px).roundToInt()
            val toolbarRed = android.graphics.Color.red(bitmap.getPixel(x, (35 * px).roundToInt()))
            val portraitRed = android.graphics.Color.red(bitmap.getPixel(x, (115 * px).roundToInt()))
            assertTrue("Toolbar introduced a dark stripe at $fraction: $toolbarRed vs $portraitRed",
                kotlin.math.abs(toolbarRed - portraitRed) <= 3)
        }
    }

    @Test fun toolbarCoversScrollingRowsBeforePortraitCompletelyVanishes() {
        var fade by mutableStateOf(0.5f)
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.width(360.dp).background(Color(0xFF121212)).testTag("seam")) {
                    ArtistHeroLayout(background = Color(0xFF121212),
                        artwork = { Box(Modifier.fillMaxSize().background(Color(0xFF824634))) },
                        title = {}, actions = {}, artworkAlpha = { 1f - fade })
                    ArtistToolbar("Artist", true, false, {}, {}, {}, {}, artworkFade = fade)
                }
            }
        }
        // In the final 20% the toolbar is ALREADY opaque: no sudden last
        // frame tint and no song rows visible underneath when fully scrolled.
        val reds = listOf(0.995f, 0.998f, 1f).map { f ->
            compose.runOnIdle { fade = f }
            val bitmap = capture("seam")
            val dp = bitmap.width / 360f
            val toolbar = bitmap.getPixel((4 * dp).roundToInt(), (30 * dp).roundToInt())
            android.graphics.Color.red(toolbar)
        }
        assertTrue("Background must remain constant through final scroll frames: $reds",
            reds.maxOrNull()!! - reds.minOrNull()!! <= 2)
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
