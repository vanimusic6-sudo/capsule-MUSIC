package com.nikhil.yt.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.nikhil.yt.constants.MiniPlayerBackgroundStyle
import com.nikhil.yt.ui.player.MiniPlayerSurface
import com.nikhil.yt.ui.player.CapsuleBackgroundEffect
import com.nikhil.yt.ui.player.CapsuleProceduralBackground
import com.nikhil.yt.ui.player.miniPlayerProgress
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
class MiniPlayerAppearanceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

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

    @Test fun switchingEveryMiniPlayerStyleChangesTheRenderedSurface() {
        var style by mutableStateOf(MiniPlayerBackgroundStyle.THEME)
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                MiniPlayerSurface(
                    style, pureBlack = false,
                    colors = listOf(Color(0xFF647BBE), Color(0xFFBF7791), Color(0xFF96A6AF)),
                    modifier = Modifier.fillMaxWidth().height(64.dp).testTag("mini"),
                    animated = false,
                ) { Text("Capsule") }
            }
        }
        val hashes = mutableSetOf<Int>()
        for (candidate in MiniPlayerBackgroundStyle.entries) {
            compose.runOnIdle { style = candidate }
            val bitmap = capture("mini")
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            hashes += pixels.contentHashCode()
            val output = File("build/reports/ui-previews/mini-${candidate.name.lowercase()}.png")
            output.parentFile.mkdirs()
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        assertEquals("A selected style must not be replaced by a fixed panel", MiniPlayerBackgroundStyle.entries.size, hashes.size)
    }

    @Test fun contentRemainsReadableWithEffectsSelectedInALightAppTheme() {
        var style by mutableStateOf(MiniPlayerBackgroundStyle.THEME)
        var observedColor = Color.Unspecified
        val scheme = lightColorScheme()
        compose.setContent {
            MaterialTheme(colorScheme = scheme) {
                MiniPlayerSurface(style, false, emptyList(), Modifier.size(100.dp, 64.dp), animated = false) {
                    val color = LocalContentColor.current
                    SideEffect { observedColor = color }
                    Text("Capsule")
                }
            }
        }
        compose.runOnIdle { assertEquals(scheme.onSurface, observedColor) }
        for (candidate in MiniPlayerBackgroundStyle.entries.filter { it != MiniPlayerBackgroundStyle.THEME }) {
            compose.runOnIdle { style = candidate }
            compose.runOnIdle { assertEquals(Color(0xFFF4F4F4), observedColor) }
        }
    }

    @Test fun capsuleGlowHasArtworkLightAtTheTopAndFadesToADarkLowerPlayer() {
        var effect by mutableStateOf(CapsuleBackgroundEffect.CAPSULE_GLOW)
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                CapsuleProceduralBackground(
                    effect = effect,
                    colors = listOf(Color(0xFFBC6242), Color(0xFFAB713E), Color(0xFF692842)),
                    modifier = Modifier.size(200.dp, 400.dp).testTag("glow"),
                    animated = false,
                )
            }
        }
        val glow = capture("glow")
        val top = glow.getPixel(glow.width / 2, glow.height / 10)
        val bottom = glow.getPixel(glow.width / 2, glow.height * 9 / 10)
        assertTrue(android.graphics.Color.red(top) > android.graphics.Color.red(bottom) + 20)
        assertTrue(android.graphics.Color.red(top) > android.graphics.Color.blue(top))
        assertTrue(android.graphics.Color.red(bottom) < 20)
        val output = File("build/reports/ui-previews/player-capsule-glow.png")
        output.parentFile.mkdirs()
        output.outputStream().use { glow.compress(Bitmap.CompressFormat.PNG, 100, it) }
        compose.runOnIdle { effect = CapsuleBackgroundEffect.AMBIENT_GLOW }
        assertTrue("Capsule Glow must have its own composition", !glow.sameAs(capture("glow")))
    }

    @Test fun progressHasADimUnplayedTrackAndResetsForUnknownDuration() {
        var position by mutableStateOf(0L)
        var duration by mutableStateOf(100L)
        compose.setContent {
            Box(Modifier.size(50.dp).background(Color.Black).miniPlayerProgress(position, duration).testTag("progress"))
        }
        fun rightEdge(bitmap: Bitmap) = android.graphics.Color.red(bitmap.getPixel(bitmap.width - 1, bitmap.height / 2))
        val empty = capture("progress")
        assertTrue("Unplayed track must not be a bright white ring", rightEdge(empty) in 1..30)
        compose.runOnIdle { position = 50L }
        val halfway = capture("progress")
        assertTrue(rightEdge(halfway) > 180)
        assertTrue(android.graphics.Color.red(halfway.getPixel(0, halfway.height / 2)) < 30)
        compose.runOnIdle { duration = 0L }
        assertTrue(empty.sameAs(capture("progress")))
    }
    @Test fun exportRefinedBackgroundGallery() {
        var effect by mutableStateOf(CapsuleBackgroundEffect.MATTE_GRADIENT)
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                CapsuleProceduralBackground(
                    effect = effect,
                    colors = listOf(Color(0xFFBC6242), Color(0xFFAB713E), Color(0xFF692842)),
                    modifier = Modifier.size(200.dp, 350.dp).testTag("background"),
                    animated = false,
                )
            }
        }
        val styles = CapsuleBackgroundEffect.entries.filter { it != CapsuleBackgroundEffect.CAPSULE_GLOW }
        val gallery = Bitmap.createBitmap(720, 864, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(gallery)
        canvas.drawColor(android.graphics.Color.BLACK)
        val label = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = 13f
        }
        styles.forEachIndexed { index, selected ->
            compose.runOnIdle { effect = selected }
            val bitmap = capture("background")
            val x = index % 3 * 240
            val y = index / 3 * 432
            canvas.drawBitmap(bitmap, null, android.graphics.Rect(x + 6, y + 6, x + 234, y + 405), null)
            canvas.drawText(selected.name.replace('_', ' '), x + 12f, y + 423f, label)
        }
        val output = File("build/reports/ui-previews/player-background-gallery.png")
        output.parentFile.mkdirs()
        output.outputStream().use { gallery.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

}
