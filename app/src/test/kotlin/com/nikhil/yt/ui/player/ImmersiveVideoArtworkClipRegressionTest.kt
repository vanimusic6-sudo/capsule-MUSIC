package com.nikhil.yt.ui.player

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-level guard: a landscape video thumbnail is uniformly overscanned,
 * so the photo AND its original fade must share the physical artwork clip.
 * Pixel alignment still requires real-device coverage.
 */
class ImmersiveVideoArtworkClipRegressionTest {
    @Test fun landscapeThumbnailOverscanCannotEscapeItsFadeContainer() {
        val relative = "src/main/kotlin/com/nikhil/yt/ui/player/CapsuleImmersiveContent.kt"
        val file = listOf(File(relative), File("app/$relative")).firstOrNull { it.isFile }
            ?: error("Immersive source not found")
        val content = file.readText().substringAfter("if (frame != null) {")
            .substringBefore("/*\n         * Immersive editing")
        assertTrue(content.contains("Box(Modifier.fillMaxSize().clipToBounds().graphicsLayer"))
        assertTrue(content.contains("scaleX = if (frame.landscape) 1.06f else 1f"))
        assertTrue(content.contains("scaleY = if (frame.landscape) 1.06f else 1f"))
        assertTrue(content.contains("immersiveContinuousCoverStops("))
        assertTrue(content.contains("immersiveOriginalFadeProfile("))
    }
}
