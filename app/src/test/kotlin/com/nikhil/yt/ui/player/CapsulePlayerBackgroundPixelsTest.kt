package com.nikhil.yt.ui.player

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.nikhil.yt.constants.PlayerBackgroundStyle
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w400dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CapsulePlayerBackgroundPixelsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun nebulaRetainsRedInsteadOfPaintingLavenderWhiteFog() {
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.size(300.dp, 600.dp).testTag("background")) {
                    PlayerBackground(PlayerBackgroundStyle.NEBULA,
                        listOf(Color(0xFF380B08), Color(0xFFE62A1E), Color(0xFFB42814)), animated = false)
                }
            }
        }
        val bitmap = capture("nebula-real-red")
        val mean = mean(bitmap)
        assertTrue("Red fog must retain the source hue: $mean", mean[0] > mean[1] * 2 && mean[0] > mean[2] * 2)
    }

    @Test fun glowIlluminatesTheControlsAreaBelowTheOpaqueCover() {
        val style = mutableStateOf(PlayerBackgroundStyle.GLOW)
        val palette = listOf(Color(0xFF23191D), Color(0xFFE64E28), Color(0xFF326BD9))
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.size(300.dp, 600.dp).testTag("background")) {
                    PlayerBackground(style.value, palette, animated = false)
                }
            }
        }
        val glow = capture("glow-visible-below-cover")
        compose.runOnIdle { style.value = PlayerBackgroundStyle.GRADIENT }
        val matte = capture("matte-reference")
        assertTrue("Glow must be visibly luminous behind the controls",
            mean(glow, 0.55f, 0.85f).average() > mean(matte, 0.55f, 0.85f).average() + 10)
    }

    @Test fun neutralArtworkDoesNotAcquireBlueOrPurpleInProceduralStyles() {
        val style = mutableStateOf(PlayerBackgroundStyle.GRADIENT)
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.size(300.dp, 600.dp).testTag("background")) {
                    PlayerBackground(style.value,
                        listOf(Color(0xFF505050), Color(0xFFAAAAAA), Color(0xFFE0E0E0)), animated = false)
                }
            }
        }
        for (choice in PlayerBackgroundStyle.entries.filter {
            it != PlayerBackgroundStyle.DEFAULT && it != PlayerBackgroundStyle.SCENE_LIGHT
        }) {
            compose.runOnIdle { style.value = choice }
            val bitmap = capture("neutral-${choice.name.lowercase()}")
            val c = mean(bitmap)
            assertTrue("$choice invented a hue: $c", (c.maxOrNull()!! - c.minOrNull()!!) <= 3.0)
        }
    }

    @Test fun sceneLightUsesTheActualVideoFieldAndPreservesTwoDimensionalRegions() {
        val source = Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888)
        for (y in 0 until source.height) for (x in 0 until source.width) {
            source.setPixel(x, y, when {
                y < 45 && x < 80 -> 0xFFCC3020.toInt()
                y < 45 -> 0xFF2040CC.toInt()
                x < 80 -> 0xFF29B55A.toInt()
                else -> 0xFFE69B24.toInt()
            })
        }
        val scene = capsuleArtworkScene(source)
        val video = requireNotNull(capsuleVideoAmbientFrameFromBitmap(source, "same-scene"))
        assertEquals("Still artwork and video must use the same spatial processing", video.field, scene.field)
        val field = requireNotNull(scene.field)
        assertTrue(field.cell(1, 1).red > field.cell(1, 1).blue)
        assertTrue(field.cell(18, 1).blue > field.cell(18, 1).red)
        assertTrue(field.cell(1, 14).green > field.cell(1, 14).red)
        val projected = capsuleVideoSpatialBitmap(field, Color(0xFF101016), alreadyDiffused = true).asAndroidBitmap()
        save(projected, "scene-light-four-source-regions")
    }

    @Test fun equalPalettesOnDifferentTracksStillUpdateTheSpatialScene() {
        val colors = listOf(Color.Red, Color.Blue, Color.Green)
        val a = CapsuleColorField(2, 1, listOf(Color.Red, Color.Blue))
        val b = CapsuleColorField(2, 1, listOf(Color.Blue, Color.Red))
        val cache = ArtworkPaletteCache().apply {
            putScene("a", CapsuleArtworkScene(colors, a))
            putScene("b", CapsuleArtworkScene(colors, b))
        }
        val key = mutableStateOf("a")
        var observed: CapsuleColorField? = null
        compose.setContent {
            val scene = rememberArtworkScene(key.value, null, true, cache, false, animatePalette = false)
            SideEffect { observed = scene.field }
        }
        compose.runOnIdle { assertEquals(a, observed); key.value = "b" }
        compose.runOnIdle { assertEquals(b, observed) }
    }

    private fun mean(bitmap: Bitmap, top: Float = 0.1f, bottom: Float = 0.9f): List<Double> {
        val sum = DoubleArray(3)
        var count = 0
        for (y in (bitmap.height * top).toInt() until (bitmap.height * bottom).toInt() step 7) {
            for (x in bitmap.width / 10 until bitmap.width * 9 / 10 step 7) {
                val pixel = bitmap.getPixel(x, y)
                sum[0] += AndroidColor.red(pixel); sum[1] += AndroidColor.green(pixel); sum[2] += AndroidColor.blue(pixel)
                count++
            }
        }
        return sum.map { it / count }
    }

    private fun capture(name: String): Bitmap {
        compose.waitForIdle()
        val bounds = compose.onNodeWithTag("background").fetchSemanticsNode().boundsInRoot
        lateinit var bitmap: Bitmap
        compose.runOnIdle {
            bitmap = Bitmap.createBitmap(bounds.width.roundToInt(), bounds.height.roundToInt(), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.translate(-bounds.left, -bounds.top)
            compose.activity.findViewById<View>(android.R.id.content).draw(canvas)
        }
        save(bitmap, name)
        return bitmap
    }

    private fun save(bitmap: Bitmap, name: String) {
        val path = File("build/video-morph-preview/player-background-$name.png")
        path.parentFile.mkdirs()
        path.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
