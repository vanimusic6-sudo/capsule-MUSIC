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
        // The PHOTO now fades into the sampled lower-edge backdrop, not a
        // tinted overlay. The mask is cached and clipped to the same slot.
        assertTrue(content.contains(".clipToBounds()"))
        assertTrue(content.contains("CompositingStrategy.Offscreen"))
        assertTrue(content.contains("BlendMode.DstIn"))
        assertTrue(content.contains("0.944f to Color.White"))
        assertTrue(content.contains("scaleX = if (frame.landscape) 1.06f else 1f"))
        assertTrue(content.contains("scaleY = if (frame.landscape) 1.06f else 1f"))
        assertTrue(content.contains("matchedPaint?.let { bitmap ->"))
        assertTrue(!content.contains("matchedPaint?.second?.let"))
        assertTrue(content.contains("immersivePageBaseColor(false, frame != null, pageFloor)"))
    }

    @Test fun immersiveFixedArtworkCannotHideItsGradientBehindOpaqueMatte() {
        val relative = "src/main/kotlin/com/nikhil/yt/ui/player/CapsuleVideoArtworkTransition.kt"
        val file = listOf(File(relative), File("app/$relative")).firstOrNull { it.isFile }
            ?: error("Video transition source not found")
        val fixedArtwork = file.readText()
            .substringAfter("if (fixedImmersiveArtwork) {")
            .substringBefore("// Geometry is one shared bounds transformation.")
        assertTrue("Transparent photo pixels must show the page's real gradient",
            !fixedArtwork.contains(".background(artworkBackground)"))
        val immersive = listOf(
            File("src/main/kotlin/com/nikhil/yt/ui/player/CapsuleImmersiveContent.kt"),
            File("app/src/main/kotlin/com/nikhil/yt/ui/player/CapsuleImmersiveContent.kt"),
        ).firstOrNull { it.isFile }?.readText()
            ?: error("Immersive source not found")
        assertTrue("Immersion should not request another solid matte",
            immersive.contains("artworkBackground = Color.Transparent"))
    }
}
