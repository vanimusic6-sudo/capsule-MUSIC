package com.nikhil.yt.ui.component

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Finishing guards for the moving original sheet and app foreground handoff. */
class PlayerFinalPolishTest {
    @Test fun classicPlayerKeepsVisibleArtworkOrMiniThroughoutTheMotion() {
        for (i in 0..100) {
            val progress = i / 100f
            val mini = playerClassicMiniAlpha(progress)
            val artwork = playerClassicContentAlpha(progress)
            val backdrop = playerClassicBackdropAlpha(progress)
            assertTrue(
                "Missing visible player surface at $progress",
                maxOf(mini, artwork, backdrop) > 0.35f,
            )
        }
        assertEquals(1f, playerClassicMiniAlpha(0f), 0f)
        assertEquals(0f, playerClassicMiniAlpha(1f), 0f)
        assertEquals(0f, playerClassicContentAlpha(0f), 0f)
        assertEquals(1f, playerClassicContentAlpha(1f), 0f)
    }

    @Test fun foregroundRestoreRetainsTheLastPaintedColorField() {
        val player = File("src/main/kotlin/com/nikhil/yt/ui/player/Player.kt").readText()
        val palette = File("src/main/kotlin/com/nikhil/yt/ui/player/CapsuleArtworkPalette.kt").readText()
        assertTrue(player.contains("retainWhenDisabled = true"))
        assertTrue(palette.contains("if (!enabled) return if (retainWhenDisabled) targetScene"))
        assertFalse(player.contains("onScreen && playerSceneExposed"))
    }

    @Test fun explicitLabelUsesTheArtistRowsOwnVerticalAlignment() {
        val badge = File("src/main/kotlin/com/nikhil/yt/ui/player/ExplicitBadge.kt").readText()
        assertFalse(badge.contains("offset(y ="))
        assertTrue(badge.contains("contentAlignment = Alignment.Center"))
    }
}
