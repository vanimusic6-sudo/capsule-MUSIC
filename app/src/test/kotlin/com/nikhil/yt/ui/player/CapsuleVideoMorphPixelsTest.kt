package com.nikhil.yt.ui.player

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
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

    @Test fun lightReturnsOpaquePhotoBeforeContainerFinishesClosing() {
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
            "Light cover must be completely opaque by ~160ms without corner leaks",
            AndroidColor.BLUE, centre("sculpted-cover-no-double-exposure"),
        )
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(AndroidColor.BLUE, centre("sculpted-cover-final"))
    }

    @Test fun lightCloseNeverLeaksVideoPastTheElasticAlbumEdge() =
        assertSolidCoverAtElasticEdges(CapsuleVideoBlendStyle.LIGHT, 220L)

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

    @Test fun immersionArtworkSeamStaysPinnedWhenVideoDissolvesAndWindowCloses() {
        val presenting = mutableStateOf(true)
        compose.setContent {
            Box(Modifier.size(320.dp, 440.dp).background(Color.Blue).testTag("media-slot")) {
                CapsuleVideoArtworkTransition(
                    presentingVideo = presenting.value,
                    needsSurface = true,
                    fillArtworkSlot = true,
                    fixedImmersiveArtwork = true,
                    motion = CapsuleVideoArtworkMotion.IMMERSIVE,
                    blendStyle = CapsuleVideoBlendStyle.IMMERSION,
                    artworkCornerRadius = 0.dp,
                    videoCornerRadius = 16.dp,
                    artworkBackground = Color.Blue,
                    video = { Box(Modifier.fillMaxSize().background(Color.Green)) },
                    artwork = {
                        Box(Modifier.fillMaxSize().background(
                            Brush.verticalGradient(
                                0f to Color.Red,
                                0.85f to Color.Blue,
                                1f to Color.Blue,
                            )
                        ))
                    },
                )
            }
        }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { presenting.value = false }
        compose.mainClock.advanceTimeByFrame()
        repeat(26) { step ->
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            val bitmap = snapshot("pinned-seam-$step")
            try {
                // The album's last line and the stationary player floor have
                // identical endpoint colour even while VIDEO is retained.
                for (x in listOf(bitmap.width / 4, bitmap.width / 2, bitmap.width * 3 / 4)) {
                    val bottom = bitmap.getPixel(x, bitmap.height - 3)
                    assertTrue("Immersion seam flashed at frame $step, x=$x",
                        AndroidColor.blue(bottom) > 245 &&
                            AndroidColor.red(bottom) < 12 &&
                            AndroidColor.green(bottom) < 12)
                }
            } finally { bitmap.recycle() }
        }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
    }

    @Test fun joinedTrackScenesNeverExposeASeparateFeatherStripe() {
        val fade = mutableStateOf(0f)
        val red = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            .apply { eraseColor(AndroidColor.RED) }.asImageBitmap()
        val blue = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            .apply { eraseColor(AndroidColor.BLUE) }.asImageBitmap()
        compose.setContent {
            Box(Modifier.size(320.dp, 440.dp).testTag("media-slot")) {
                ImmersiveAudioScene(red, 280.dp, sceneAlpha = { 1f }) {
                    Box(Modifier.fillMaxSize().background(Color.Red))
                }
                ImmersiveAudioScene(blue, 280.dp, sceneAlpha = { fade.value }) {
                    Box(Modifier.fillMaxSize().background(Color.Blue))
                }
            }
        }
        val first = snapshot("joined-endpoint-red")
        compose.runOnIdle { fade.value = 1f }
        val second = snapshot("joined-endpoint-blue")
        try {
            for (alpha in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
                compose.runOnIdle { fade.value = alpha }
                val bitmap = snapshot("joined-scene-$alpha")
                try {
                    // Include the stage scrim in each endpoint. Group opacity
                    // must interpolate the complete scene, including its mask.
                    for (row in 0..80) {
                        val y = ((0.34f + row * 0.006f) * bitmap.height).roundToInt()
                        val x = bitmap.width / 2
                        val pixel = bitmap.getPixel(x, y)
                        for (shift in listOf(0, 8, 16)) {
                            val start = first.getPixel(x, y) ushr shift and 255
                            val end = second.getPixel(x, y) ushr shift and 255
                            val expected = (start * (1f - alpha) + end * alpha).roundToInt()
                            assertTrue("Track alpha $alpha broke group blend at row $y",
                                kotlin.math.abs((pixel ushr shift and 255) - expected) <= 3)
                        }
                    }
                } finally { bitmap.recycle() }
            }
        } finally { first.recycle(); second.recycle() }
    }

    @Test fun videoBackplateAttenuatesPhotoAndFloorEqually() {
        val progress = mutableStateOf(0f)
        val red = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            .apply { eraseColor(AndroidColor.RED) }.asImageBitmap()
        compose.setContent {
            Box(Modifier.size(320.dp, 440.dp).testTag("media-slot")) {
                ImmersiveAudioScene(red, 280.dp, sceneAlpha = { 1f }) {
                    Box(Modifier.fillMaxSize().background(Color.Red))
                }
                Box(Modifier.fillMaxSize().graphicsLayer {
                    alpha = capsuleImmersiveVideoBackplateAlpha(progress.value)
                }.background(Color.Blue))
            }
        }
        for (p in listOf(0f, 0.2f, 0.4f, 0.6f, 0.8f, 1f, 0.6f, 0f)) {
            compose.runOnIdle { progress.value = p }
            val bitmap = snapshot("whole-scene-video-$p")
            try {
                val photo = bitmap.getPixel(bitmap.width / 2, bitmap.height / 4)
                val floor = bitmap.getPixel(bitmap.width / 2, bitmap.height * 4 / 5)
                assertEquals("VIDEO cannot fade the photo ahead of its floor", floor, photo)
            } finally { bitmap.recycle() }
        }
    }

    @Test fun busyArtworkAndItsContinuationHaveNoHardPhysicalCut() {
        val source = Bitmap.createBitmap(320, 280, Bitmap.Config.ARGB_8888)
        for (y in 0 until source.height) for (x in 0 until source.width) {
            source.setPixel(x, y, when {
                (x / 7 + y / 9) % 3 == 0 -> AndroidColor.rgb(18, 20, 25)
                x < 160 -> AndroidColor.rgb(184, 126, 66)
                else -> AndroidColor.rgb(46, 98, 145)
            })
        }
        val cover = source.asImageBitmap()
        val continuation = capsuleImmersiveImageBackdropBitmap(
            source.capsuleImmersiveVisibleField(320f / 280f, false), 280f / 440f)
        compose.setContent {
            Box(Modifier.size(320.dp, 440.dp).testTag("media-slot")) {
                ImmersiveAudioScene(continuation, 280.dp, sceneAlpha = { 1f }) {
                    Image(cover, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize())
                }
            }
        }
        val image = snapshot("busy-artwork-soft-join")
        try {
            val join = (image.height * 280f / 440f).roundToInt()
            for (x in listOf(image.width / 5, image.width / 2, image.width * 4 / 5)) {
                for (y in join - 3..join + 3) {
                    val a = image.getPixel(x, y)
                    val b = image.getPixel(x, y + 1)
                    for (shift in listOf(0, 8, 16)) {
                        assertTrue("No visible cut at x=$x, y=$y",
                            kotlin.math.abs((a shr shift and 255) - (b shr shift and 255)) < 10)
                    }
                }
            }
        } finally { image.recycle() }
    }

    @Test fun photographicBackdropKeepsColourWithoutASeparateFeatherStrip() {
        val source = Bitmap.createBitmap(320, 280, Bitmap.Config.ARGB_8888)
        source.eraseColor(AndroidColor.rgb(210, 140, 75))
        val field = source.capsuleImmersiveVisibleField(320f / 280f, false)
        val background = capsuleImmersiveImageBackdropBitmap(field, 280f / 440f)
        compose.setContent {
            Box(Modifier.size(320.dp, 440.dp).testTag("media-slot")) {
                ImmersiveAudioScene(background, 280.dp, sceneAlpha = { 1f }) {
                    Image(source.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize())
                }
            }
        }
        val image = snapshot("image-backed-photographic-colour")
        try {
            val belowStage = image.getPixel(image.width / 2, image.height * 3 / 4)
            assertTrue(AndroidColor.red(belowStage) > AndroidColor.blue(belowStage) + 50)
            val edge = (image.height * 281f / 440f).roundToInt()
            for (y in edge - 4..edge + 4) {
                val a = image.getPixel(image.width / 2, y)
                val b = image.getPixel(image.width / 2, y + 1)
                assertTrue(kotlin.math.abs(AndroidColor.red(a) - AndroidColor.red(b)) < 5)
            }
        } finally { image.recycle() }
    }

    @Test fun tallerCropKeepsRoundArtworkFeaturesRound() {
        val source = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(source)
        canvas.drawColor(AndroidColor.BLACK)
        canvas.drawCircle(128f, 70f, 26f, android.graphics.Paint().apply { color = AndroidColor.RED })
        val background = capsuleImmersiveImageBackdropBitmap(
            source.capsuleImmersiveVisibleField(240f / 300f, false), 300f / 440f)
        compose.setContent {
            Box(Modifier.size(240.dp, 440.dp).testTag("media-slot")) {
                ImmersiveAudioScene(background, 300.dp, sceneAlpha = { 1f }) {
                    Image(source.asImageBitmap(), contentDescription = null,
                        contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
            }
        }
        val image = snapshot("larger-cover-preserves-proportions")
        try {
            var left = image.width; var right = -1; var top = image.height; var bottom = -1
            for (y in 0 until image.height / 3) for (x in 0 until image.width) {
                val pixel = image.getPixel(x, y)
                if (AndroidColor.red(pixel) > 240 && AndroidColor.green(pixel) < 8 && AndroidColor.blue(pixel) < 8) {
                    left = minOf(left, x); right = maxOf(right, x)
                    top = minOf(top, y); bottom = maxOf(bottom, y)
                }
            }
            assertTrue(right > left && bottom > top)
            assertTrue("Crop must scale both image axes equally",
                kotlin.math.abs((right - left) - (bottom - top)) <= 3)
        } finally { image.recycle() }
    }

    @Test fun tinyProgressCannotProtrudeOutsideTheEmptyCapsuleSilhouette() {
        val position = mutableStateOf(0f)
        compose.setContent {
            Box(Modifier.size(320.dp, 80.dp).testTag("media-slot")) {
                CapsuleThinSlider(value = position.value, valueRange = 0f..1f, enabled = true,
                    activeColor = Color.White, inactiveColor = Color.Gray,
                    onValueChange = {}, onValueChangeFinished = {},
                    modifier = Modifier.fillMaxSize(), trackHeight = 8.dp)
            }
        }
        val empty = snapshot("slider-empty-cap")
        compose.runOnIdle { position.value = 0.006f }
        val tiny = snapshot("slider-tiny-progress")
        try {
            for (y in 0 until empty.height) for (x in 0 until 12) {
                if (AndroidColor.alpha(empty.getPixel(x, y)) == 0) {
                    assertTrue("Tiny progress must share the track's original rounded outline",
                        AndroidColor.alpha(tiny.getPixel(x, y)) <= 1)
                }
            }
        } finally { empty.recycle(); tiny.recycle() }
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
