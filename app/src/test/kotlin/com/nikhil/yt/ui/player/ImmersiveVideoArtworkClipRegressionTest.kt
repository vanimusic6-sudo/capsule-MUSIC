package com.nikhil.yt.ui.player

import java.io.File
import org.junit.Assert.assertFalse
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
        val content = file.readText()
        assertTrue(content.contains("Box(Modifier.fillMaxSize().clipToBounds())"))
        assertFalse("Visible crop must match colour extraction", content.contains("1.06f"))
        assertTrue(content.contains("contentScale = ContentScale.Crop"))
        assertTrue(content.contains("fixedImmersiveArtwork = true"))
    }
}
