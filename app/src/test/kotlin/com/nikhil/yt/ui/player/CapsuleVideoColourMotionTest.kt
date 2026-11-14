package com.nikhil.yt.ui.player

import android.app.Application
import android.graphics.Bitmap
import android.graphics.HardwareRenderer
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.media.ImageReader
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
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
    // RuntimeShader deliberately rejects a software Bitmap Canvas. Record a
    // hardware display list and read its rendered output, as the app does.
    private fun render(engine: CapsuleVideoMotionShader, name: String): Bitmap {
        val width = 160; val height = 240
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val node = RenderNode("Capsule video colour motion")
        node.setPosition(0, 0, width, height)
        node.beginRecording().drawPaint(engine.paint)
        node.endRecording()
        ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 1).use { reader ->
            val renderer = HardwareRenderer()
            val surface = reader.surface
            try {
                renderer.setSurface(surface)
                renderer.setContentRoot(node)
                renderer.createRenderRequest().syncAndDraw()
                checkNotNull(reader.acquireNextImage()).use { image ->
                    val plane = image.planes[0]
                    val buffer = plane.buffer
                    val row = ByteArray(width * 4)
                    val packed = java.nio.ByteBuffer.allocate(width * height * 4)
                    for (y in 0 until height) {
                        buffer.position(y * plane.rowStride)
                        buffer.get(row)
                        packed.put(row)
                    }
                    packed.rewind()
                    bitmap.copyPixelsFromBuffer(packed)
                }
            } finally {
                renderer.destroy()
                surface.release()
                node.discardDisplayList()
            }
        }
        val output = java.io.File("build/video-morph-preview/$name.png")
        output.parentFile?.mkdirs()
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bitmap
    }
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

    @Test fun actualColourRegionMovesSmoothlyWithoutInventedWarp() {
        val filter = CapsuleVideoLightFilter()
        val left = filter.apply(scene(2), 1_000_000_000L)
        val before = centre(left)
        var next = filter.apply(scene(8), 1_100_000_000L)
        assertTrue(centre(next) > before)
        assertTrue(centre(next) < centre(scene(8)))
        for (i in 2..8) next = filter.apply(scene(8), 1_000_000_000L + i * 100_000_000L)
        assertTrue(centre(next) > before + 0.15f)
        assertEquals(next, capsuleFieldAt(next, scene(10), 0f))
    }

    @Test fun stripesAndTheirPhaseChangesCannotBecomeWallpaperBars() {
        val filter = CapsuleVideoLightFilter()
        fun stripes(phase: Int) = CapsuleColorField(20, 16, List(320) {
            if ((it % 20 + it / 20 + phase) % 2 == 0) Color.White else Color.Black
        })
        val first = filter.apply(stripes(0), 1_000_000_000L)
        val second = filter.apply(stripes(1), 1_100_000_000L)
        assertTrue(first.cells.maxOf { it.red } - first.cells.minOf { it.red } < 0.04f)
        assertTrue(first.cells.indices.maxOf { abs(first.cells[it].red - second.cells[it].red) } < 0.03f)
        val engine = CapsuleVideoMotionShader()
        engine.bind(capsuleVideoFieldTexture(first), capsuleVideoFieldTexture(second), Color(0xFF111116))
        engine.dimensions(160f, 240f, 0.5f)
        render(engine, "live-video-no-stripes").recycle()
    }

    @Test fun oneFrameFlashIsDampedButASustainedSceneStillFollows() {
        val filter = CapsuleVideoLightFilter()
        val dark = CapsuleColorField(20, 16, List(320) { Color.Black })
        val light = CapsuleColorField(20, 16, List(320) { Color.White })
        filter.apply(dark, 1_000_000_000L)
        val flash = filter.apply(light, 1_100_000_000L)
        assertTrue(flash.cells.maxOf { it.red } < 0.45f)
        val back = filter.apply(dark, 1_200_000_000L)
        assertTrue(back.cells.maxOf { it.red } < flash.cells.first().red)
        var sustained = back
        for (i in 3..10) sustained = filter.apply(light, 1_000_000_000L + i * 100_000_000L)
        assertTrue(sustained.cells.minOf { it.red } > 0.95f)
    }

    @Test fun nativeMotionShaderCompilesAndRendersDirectionalColours() {
        val old = CapsuleColorField(20, 16, List(320) { if (it % 20 < 10) Color.Red else Color.Blue })
        val smooth = CapsuleVideoLightFilter().apply(old, 1_000_000_000L)
        val engine = CapsuleVideoMotionShader()
        engine.bind(capsuleVideoFieldTexture(smooth), capsuleVideoFieldTexture(smooth), Color(0xFF111116))
        engine.dimensions(160f, 240f, 0.5f)
        val image = render(engine, "live-video-spatial-shader")
        val left = image.getPixel(24, 90); val right = image.getPixel(136, 90)
        assertTrue(android.graphics.Color.red(left) > android.graphics.Color.red(right) + 20)
        assertTrue(android.graphics.Color.blue(right) > android.graphics.Color.blue(left) + 20)
        image.recycle()
    }

    @Test fun uniformShaderHasNoRadialLampOrAutonomousMovement() {
        val field = CapsuleColorField(20, 16, List(320) { Color(0xFF8AB1D6) })
        val engine = CapsuleVideoMotionShader()
        engine.bind(capsuleVideoFieldTexture(field), capsuleVideoFieldTexture(field), Color(0xFF111116))
        engine.dimensions(160f, 240f, 0.4f)
        val image = render(engine, "live-video-uniform-shader")
        assertEquals(image.getPixel(20, 100), image.getPixel(80, 100))
        assertEquals(image.getPixel(80, 100), image.getPixel(140, 100))
        image.recycle()
    }
    @Test fun reportPreparationCostWithoutTreatingHostTimingAsPhonePower() {
        val filter = CapsuleVideoLightFilter()
        val source = scene(4)
        repeat(20) { filter.apply(source, (it + 1) * 100_000_000L) }
        val cpu = java.lang.management.ManagementFactory.getThreadMXBean()
        val start = cpu.currentThreadCpuTime
        repeat(300) { i ->
            capsuleVideoFieldTexture(filter.apply(source, (i + 21) * 100_000_000L)).recycle()
        }
        val perFrameUs = (cpu.currentThreadCpuTime - start) / 300.0 / 1000.0
        val fieldStart = cpu.currentThreadCpuTime
        var outputBytes = 0
        repeat(5) {
            val image = capsuleImmersiveImageBackdropBitmap(source, 0.60f)
            outputBytes = image.width * image.height * 4
            image.asAndroidBitmap().recycle()
        }
        val perGradientMs = (cpu.currentThreadCpuTime - fieldStart) / 5.0 / 1_000_000.0
        val file = java.io.File("build/video-morph-preview/energy-preparation.json")
        file.parentFile?.mkdirs()
        file.writeText("""{"scope":"Robolectric host CPU, not phone power","video_filter_and_upload_us":$perFrameUs,"cover_gradient_once_ms":$perGradientMs,"cached_image_backdrop_bytes":$outputBytes,"capture_limit_per_second":10}""")
    }

}
