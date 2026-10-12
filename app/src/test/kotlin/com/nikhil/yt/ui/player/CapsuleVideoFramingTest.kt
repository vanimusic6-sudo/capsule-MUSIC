package com.nikhil.yt.ui.player

import org.junit.Assert.*
import org.junit.Test

class CapsuleVideoFramingTest {
    private fun frame(horizontal: Int = 0, vertical: Int = 0) = detectCapsuleVideoInsets(160, 90) { x, y ->
        if (x < horizontal || x >= 160 - horizontal || y < vertical || y >= 90 - vertical) 0xff000000.toInt()
        else 0xff6090c0.toInt()
    }
    @Test fun encodedLetterboxAndPillarboxAreRemovedWithoutStretching() {
        val letterbox = frame(vertical = 12)!!
        assertTrue(letterbox.zoom(16f / 9f, 16f / 9f) in 1.25f..1.4f)
        val pillarbox = frame(horizontal = 20)!!
        assertTrue(pillarbox.zoom(16f / 9f, 16f / 9f) in 1.25f..1.4f)
    }
    @Test fun darknessAndSingleSidedShadowCannotInventBlackBorders() {
        assertNull(detectCapsuleVideoInsets(160, 90) { _, _ -> 0xff080808.toInt() })
        val shadow = detectCapsuleVideoInsets(160, 90) { _, y ->
            if (y < 15) 0xff000000.toInt() else 0xff808080.toInt()
        }!!
        assertEquals(1f, shadow.zoom(16f / 9f, 16f / 9f), 0f)
    }
    @Test fun largeSideBordersAroundSquareAndPortraitClipsAreNotLeftBehind() {
        val square = frame(horizontal = 35)!!
        assertTrue(square.zoom(16f / 9f, 16f / 9f) > 1.7f)
        val portrait = frame(horizontal = 54)!!
        assertTrue(portrait.zoom(16f / 9f, 16f / 9f) > 2.8f)
    }
    @Test fun existingAspectCropIsNotAppliedTwice() {
        val insets = CapsuleVideoInsets(horizontal = 0.1f)
        assertEquals(1f, insets.zoom(2.4f, 16f / 9f), 0.001f)
        assertEquals(1f, frame()!!.zoom(4f / 3f, 16f / 9f), 0f)
    }
    @Test fun twoAgreeingFramesAreRequiredAndAnIntroChangeResetsConsensus() {
        val consensus = CapsuleVideoBorderConsensus()
        assertNull(consensus.offer(frame(vertical = 8)))
        assertNull(consensus.offer(null))
        assertNull(consensus.offer(frame(vertical = 14)))
        assertNotNull(consensus.offer(frame(vertical = 14)))
    }
}
