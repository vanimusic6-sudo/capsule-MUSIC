package com.nikhil.yt.ui.player

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CapsuleVideoColourMotionTest {
    private fun scene(left: Int) = CapsuleColorField(20, 16, List(320) { i ->
        if (i % 20 in left until left + 5 && i / 20 in 4..11) Color.Red else Color.Black
    })
    private fun centre(field: CapsuleColorField): Float {
        var weight = 0f; var x = 0f
        field.cells.forEachIndexed { i, color ->
            weight += color.red; x += (i % field.width + 0.5f) / field.width * color.red
        }
        return x / weight
    }

    @Test fun colourSourceMovesBetweenCoordinatesRatherThanCrossfadingInPlace() {
        val old = scene(2); val next = scene(8)
        val motion = capsuleVideoColourMotion(old, next)
        val middle = capsuleAdvectedField(old, next, motion, 0.5f)
        assertTrue(motion.dx.average() > 0.20)
        assertTrue(abs(centre(middle) - (centre(old) + centre(next)) / 2f) < 0.035f)
        assertTrue("The moving light keeps its strength instead of splitting into two ghosts",
            middle.cells.maxOf { it.red } > 0.95f)
        val interrupted = capsuleAdvectedField(middle, scene(10), capsuleVideoColourMotion(middle, scene(10)), 0f)
        assertEquals(middle, interrupted)
    }

    @Test fun unrelatedSceneCutDoesNotInventSpatialMovement() {
        val red = CapsuleColorField(20, 16, List(320) { Color.Red })
        val blue = CapsuleColorField(20, 16, List(320) { Color.Blue })
        val motion = capsuleVideoColourMotion(red, blue)
        assertTrue(motion.dx.all { it == 0f } && motion.dy.all { it == 0f })
    }

    @Test fun nativeMotionShaderCompilesAndRendersDirectionalColours() {
        val old = CapsuleColorField(20, 16, List(320) { if (it % 20 < 10) Color.Red else Color.Blue })
        val motion = capsuleVideoColourMotion(old, old)
        val engine = CapsuleVideoMotionShader()
        engine.bind(capsuleVideoFieldTexture(old), capsuleVideoFieldTexture(old),
            capsuleVideoMotionTexture(motion), Color(0xFF111116))
        engine.dimensions(160f, 240f, 0.5f)
        val image = Bitmap.createBitmap(160, 240, Bitmap.Config.ARGB_8888)
        Canvas(image).drawPaint(engine.paint)
        val left = image.getPixel(24, 90); val right = image.getPixel(136, 90)
        assertTrue(android.graphics.Color.red(left) > android.graphics.Color.red(right) + 20)
        assertTrue(android.graphics.Color.blue(right) > android.graphics.Color.blue(left) + 20)
        image.recycle()
    }

    @Test fun uniformShaderHasNoRadialLampOrAutonomousMovement() {
        val field = CapsuleColorField(20, 16, List(320) { Color(0xFF8AB1D6) })
        val engine = CapsuleVideoMotionShader()
        engine.bind(capsuleVideoFieldTexture(field), capsuleVideoFieldTexture(field),
            capsuleVideoMotionTexture(capsuleVideoColourMotion(field, field)), Color(0xFF111116))
        engine.dimensions(160f, 240f, 0.4f)
        val image = Bitmap.createBitmap(160, 240, Bitmap.Config.ARGB_8888)
        Canvas(image).drawPaint(engine.paint)
        assertEquals(image.getPixel(20, 100), image.getPixel(80, 100))
        assertEquals(image.getPixel(80, 100), image.getPixel(140, 100))
        image.recycle()
    }
}
