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
        val content = file.readText()
        val scene = content.substringAfter("private fun ImmersiveAudioScene(")
            .substringBefore("/** VIDEO never inherits")
        assertTrue(scene.contains(".clipToBounds()"))
        assertTrue(scene.contains("CompositingStrategy.Offscreen"))
        assertTrue(scene.contains("BlendMode.DstIn"))
        assertTrue("Incoming and outgoing tracks must use the same joined scene",
            content.split("ImmersiveAudioScene(").size - 1 == 3)
        assertTrue("The overlap must reveal the cached backdrop continuously",
            scene.contains("immersivePhotoFeatherAlpha(y)"))
        assertTrue("A second independently faded bloom would recreate the stripe",
            !content.contains("seamBloomMask"))
        assertTrue(content.contains("scaleX = if (frame.landscape) 1.06f else 1f"))
        assertTrue(content.contains("scaleY = if (frame.landscape) 1.06f else 1f"))
        assertTrue(content.contains("matchedPaint?.let { bitmap ->"))
        assertTrue("Artwork must not be attenuated twice during VIDEO morph",
            !scene.contains("capsulePhotoOpacity("))
        assertTrue("VIDEO must attenuate the joined audio scene as a whole",
            content.contains("alpha = capsuleImmersiveVideoBackplateAlpha(sharedVideoProgress.value)"))
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
