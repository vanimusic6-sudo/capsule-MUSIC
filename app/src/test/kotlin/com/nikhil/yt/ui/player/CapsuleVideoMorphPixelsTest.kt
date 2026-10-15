package com.nikhil.yt.ui.player

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w400dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CapsuleVideoMorphPixelsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun sculptedMorphKeepsOpaqueContentWhileTheFrameChangesShape() {
        val presenting = mutableStateOf(false)
        val needed = mutableStateOf(true)
        compose.setContent {
            CapsuleVideoArtworkTransition(
                presentingVideo = presenting.value,
                needsSurface = needed.value,
                fillArtworkSlot = true,
                modifier = Modifier.size(320.dp, 280.dp).testTag("media-slot"),
                // Red is a live decoder frame in VIDEO, and its retained exit still in AUDIO.
                video = { Box(Modifier.fillMaxSize().background(Color.Red)) },
                artwork = { Box(Modifier.fillMaxSize().background(Color.Blue)) },
            )
        }
        assertEquals(AndroidColor.BLUE, centre("audio"))
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { presenting.value = true }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(64)
        assertEquals("cover remains opaque until the silhouette has moved", AndroidColor.BLUE, centre("open-64"))
        repeat(10) {
            compose.mainClock.advanceTimeBy(16)
            assertOpaqueContent("opening-$it")
        }
        compose.mainClock.advanceTimeBy(26)
        assertOpaqueContent("open-250")
        compose.mainClock.advanceTimeBy(500)
        assertEquals(AndroidColor.RED, centre("video"))
        compose.runOnIdle { needed.value = false; presenting.value = false }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(32)
        assertOpaqueContent("close-32")
        repeat(6) {
            compose.mainClock.advanceTimeBy(16)
            assertOpaqueContent("closing-$it")
        }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(AndroidColor.BLUE, centre("closed"))
    }

    private fun assertOpaqueContent(name: String) {
        val pixel = centre(name)
        assertTrue("$name must show an opaque cover/video blend, never a black gap",
            AndroidColor.alpha(pixel) == 255 &&
                (AndroidColor.red(pixel) > 0 || AndroidColor.blue(pixel) > 0))
    }

    @Test fun lightAndCosmoReturnOpaquePhotoBeforeContainerFinishesClosing() {
        val presenting = mutableStateOf(true)
        val surfaceNeeded = mutableStateOf(true)
        compose.setContent {
            CapsuleVideoArtworkTransition(
                presentingVideo = presenting.value,
                needsSurface = surfaceNeeded.value,
                modifier = Modifier.size(320.dp, 280.dp).testTag("media-slot"),
                fillArtworkSlot = true,
                artworkCornerRadius = 16.dp,
                videoCornerRadius = 16.dp,
                artworkBackground = Color(0xFF141414),
                video = { Box(Modifier.fillMaxSize().background(Color.Red)) },
                artwork = { Box(Modifier.fillMaxSize().background(Color.Blue)) },
            )
        }
        assertEquals(AndroidColor.RED, centre("sculpted-start-video"))
        compose.mainClock.autoAdvance = false
        compose.runOnIdle {
            presenting.value = false
            surfaceNeeded.value = false
        }
        compose.mainClock.advanceTimeByFrame()
        repeat(10) {
            // Exercise real scheduler iterations: a single bulk clock jump
            // can skip LaunchedEffect/Animatable recomposition on Robolectric.
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        assertEquals(
            "Cosmo cover must be completely opaque by ~160ms without corner leaks",
            AndroidColor.BLUE, centre("sculpted-cover-no-double-exposure"),
        )
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(AndroidColor.BLUE, centre("sculpted-cover-final"))
    }

    @Test fun lightCloseNeverLeaksVideoPastTheElasticAlbumEdge() =
        assertSolidCoverAtElasticEdges(CapsuleVideoBlendStyle.LIGHT, 220L)

    @Test fun cosmoCloseNeverLeaksVideoPastTheElasticAlbumEdge() =
        assertSolidCoverAtElasticEdges(CapsuleVideoBlendStyle.COSMO, 240L)

    @Test fun immersionCloseDissolvesVideoWithoutRubberEdgeLeaks() =
        assertSolidCoverAtElasticEdges(CapsuleVideoBlendStyle.IMMERSION, 325L)

    private fun assertSolidCoverAtElasticEdges(style: CapsuleVideoBlendStyle, atMs: Long) {
        val playing = mutableStateOf(true)
        val immersive = style == CapsuleVideoBlendStyle.IMMERSION
        compose.setContent {
            Box(Modifier.size(320.dp, 440.dp).background(Color.Black).testTag("media-slot")) {
                CapsuleVideoArtworkTransition(
                    presentingVideo = playing.value,
                    needsSurface = true, // Keep underlying red VIDEO in the tree.
                    blendStyle = style,
                    motion = if (immersive) CapsuleVideoArtworkMotion.IMMERSIVE
                        else CapsuleVideoArtworkMotion.SCULPTED,
                    artworkCornerRadius = if (immersive) 0.dp else 16.dp,
                    videoCornerRadius = 16.dp,
                    modifier = Modifier.fillMaxSize(),
                    fillArtworkSlot = true,
                    artworkBackground = Color.Blue,
                    video = { Box(Modifier.fillMaxSize().background(Color.Red)) },
                    artwork = { Box(Modifier.fillMaxSize().background(Color.Blue)) },
                )
            }
        }
        assertEquals(AndroidColor.RED, centre("before-$style"))
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { playing.value = false }
        compose.mainClock.advanceTimeByFrame()
        repeat((atMs / 16L).toInt()) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        val frame = snapshot("opaque-$style")
        try {
            val cx = frame.width / 2
            val cy = frame.height / 2
            val centre = frame.getPixel(cx, cy)
            assertTrue("the same album must be visible at the centre in $style",
                AndroidColor.blue(centre) > 225 && AndroidColor.red(centre) < 32)
            // If the deforming silhouette outruns the image's smaller
            // graphics layer, an exposed RED video wedge appears at its edge.
            // Masked-out pixels are BLACK, and album/matte pixels are BLUE.
            var leaks = 0
            for (y in 3 until frame.height - 3 step 3) {
                for (x in 3 until frame.width - 3 step 3) {
                    val c = frame.getPixel(x, y)
                    if (AndroidColor.red(c) > 68 &&
                        AndroidColor.red(c) > AndroidColor.blue(c) * 1.5f
                    ) leaks++
                }
            }
            assertEquals("$style leaked underlying VIDEO near the stretched album edges", 0, leaks)
        } finally {
            frame.recycle()
        }
    }

    @Test fun regularMorphHasNoHardHorizontalVideoGuttersMidTransition() {
        val presenting = mutableStateOf(false)
        compose.setContent {
            CapsuleVideoArtworkTransition(
                presentingVideo = presenting.value,
                needsSurface = true,
                fillArtworkSlot = true,
                modifier = Modifier.size(320.dp, 280.dp).testTag("media-slot"),
                video = { Box(Modifier.fillMaxSize().background(Color.Red)) },
                artwork = { Box(Modifier.fillMaxSize().background(Color.Blue)) },
            )
        }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { presenting.value = true }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(160)
        val bitmap = snapshot("no-internal-gutter")
        try {
            val midX = bitmap.width / 2
            val inside = (0 until bitmap.height).filter {
                val color = bitmap.getPixel(midX, it)
                AndroidColor.red(color) > 16 || AndroidColor.blue(color) > 16
            }
            assertTrue("a visible contour must exist", inside.size > 50)
            val first = bitmap.getPixel(midX, inside.first() + 12)
            val middle = bitmap.getPixel(midX, bitmap.height / 2)
            val last = bitmap.getPixel(midX, inside.last() - 12)
            // Video and cover are uniform test fills. Their colours should
            // blend uniformly all the way to the moving contour. Previously
            // the fixed 16:9 native viewport showed a hard red strip against
            // a blue/black band until the outer mask caught up.
            for (edge in listOf(first, last)) {
                assertTrue("no horizontal VIDEO boundary may jump under the shared mask",
                    kotlin.math.abs(AndroidColor.red(edge) - AndroidColor.red(middle)) <= 16 &&
                    kotlin.math.abs(AndroidColor.blue(edge) - AndroidColor.blue(middle)) <= 16)
            }
        } finally {
            bitmap.recycle()
        }
    }

    @Test fun immersiveUsesOneSoftRectangularHandoverAndSettlesWithoutAnEmptyFrame() {
        val presenting = mutableStateOf(false)
        val needed = mutableStateOf(true)
        compose.setContent {
            CapsuleVideoArtworkTransition(
                presentingVideo = presenting.value, needsSurface = needed.value,
                motion = CapsuleVideoArtworkMotion.IMMERSIVE, fillArtworkSlot = true,
                blendStyle = CapsuleVideoBlendStyle.IMMERSION,
                artworkCornerRadius = 0.dp,
                modifier = Modifier.size(320.dp, 440.dp).testTag("media-slot"),
                video = { Box(Modifier.fillMaxSize().background(Color.Red)) },
                artwork = { Box(Modifier.fillMaxSize().background(Color.Blue)) },
            )
        }
        assertEquals(AndroidColor.BLUE, centre("immersive-audio"))
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { presenting.value = true }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(64)
        assertEquals("cover survives the beginning of the silhouette movement", AndroidColor.BLUE, centre("immersive-open-64"))
        compose.mainClock.advanceTimeBy(176)
        assertRectangularBlend("immersive-opening")
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals(AndroidColor.RED, centre("immersive-video"))
        compose.runOnIdle { needed.value = false; presenting.value = false }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(160)
        // The new shorter handover prevents a ghost-photo over VIDEO during
        // most of closing. There must be opaque content, regardless of exact
        // spring progress at 60/120Hz.
        assertOpaqueContent("immersive-closing")
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(AndroidColor.BLUE, centre("immersive-closed"))
    }

    @Test fun recoveringReadinessDoesNotFlashTheOpaqueCover() {
        val presenting = mutableStateOf(true)
        compose.setContent {
            CapsuleVideoArtworkTransition(
                presentingVideo = presenting.value, needsSurface = true,
                modifier = Modifier.size(320.dp, 280.dp).testTag("media-slot"),
                video = { Box(Modifier.fillMaxSize().background(Color.Red)) },
                artwork = { Box(Modifier.fillMaxSize().background(Color.Blue)) },
            )
        }
        assertEquals(AndroidColor.RED, centre("before-recovery"))
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { presenting.value = false }
        compose.mainClock.advanceTimeByFrame()
        val first = centre("recovery-first-frame")
        assertTrue("readiness change must reverse the animation without a cover flash", AndroidColor.red(first) > 240)
        compose.mainClock.advanceTimeBy(160)
        assertOpaqueContent("recovery-blend")
        compose.runOnIdle { presenting.value = true }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(AndroidColor.RED, centre("recovered"))
    }

    private fun assertRectangularBlend(name: String) {
        val bitmap = snapshot(name)
        try {
            assertTrue("the centre never becomes an empty frame",
                bitmap.getPixel(bitmap.width / 2, bitmap.height / 2) != AndroidColor.BLACK)
            val centre = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
            assertTrue("the handover is soft, with both opaque contents covering the same frame",
                AndroidColor.red(centre) in 20..235 && AndroidColor.blue(centre) in 20..235)
            // A radial mask would reveal a different colour away from the centre.
            for (x in listOf(bitmap.width / 2 - 60, bitmap.width / 2 + 60)) {
                val edge = bitmap.getPixel(x, bitmap.height / 2)
                assertTrue(kotlin.math.abs(AndroidColor.red(centre) - AndroidColor.red(edge)) <= 3)
                assertTrue(kotlin.math.abs(AndroidColor.blue(centre) - AndroidColor.blue(edge)) <= 3)
            }
        } finally { bitmap.recycle() }
    }

    private fun centre(snapshotName: String): Int {
        val bitmap = snapshot(snapshotName)
        return bitmap.getPixel(bitmap.width / 2, bitmap.height / 2).also { bitmap.recycle() }
    }

    private fun snapshot(snapshotName: String): Bitmap {
        compose.waitForIdle()
        val bounds = compose.onNodeWithTag("media-slot").fetchSemanticsNode().boundsInRoot
        lateinit var bitmap: Bitmap
        compose.runOnIdle {
            bitmap = Bitmap.createBitmap(bounds.width.roundToInt(), bounds.height.roundToInt(), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.translate(-bounds.left, -bounds.top)
            compose.activity.findViewById<View>(android.R.id.content).draw(canvas)
            val output = File("build/video-morph-preview/$snapshotName.png")
            output.parentFile.mkdirs()
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        return bitmap
    }
}
