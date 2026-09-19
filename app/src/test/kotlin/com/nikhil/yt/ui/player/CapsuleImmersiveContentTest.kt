package com.nikhil.yt.ui.player

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CapsuleImmersiveContentTest {
    @Test
    fun `an ordinary phone gives most of the sheet to the cover`() {
        // 800dp is a typical tall phone; 62% of it is comfortably inside both bounds.
        assertEquals(496.dp, immersiveArtworkHeight(800.dp))
    }

    @Test
    fun `a short window keeps the controls their room`() {
        assertEquals(
            "a landscape phone must not be handed a cover that squeezes the panel out",
            220.dp,
            immersiveArtworkHeight(300.dp),
        )
    }

    @Test
    fun `a tall window does not hand over an unbounded cover`() {
        assertEquals(620.dp, immersiveArtworkHeight(2000.dp))
    }

    @Test
    fun `no window size produces a height a layout could read as negative`() {
        for (available in listOf(0, 1, 120, 480, 800, 1600, 4000)) {
            val height = immersiveArtworkHeight(available.dp)
            assertTrue("$available dp gave $height", height >= 220.dp && height <= 620.dp)
        }
    }
}
