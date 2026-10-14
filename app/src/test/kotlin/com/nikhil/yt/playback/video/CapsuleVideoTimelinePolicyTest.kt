package com.nikhil.yt.playback.video

import androidx.media3.common.C
import org.junit.Assert.*
import org.junit.Test

class CapsuleVideoTimelinePolicyTest {
    @Test fun enteringVideoAfterShorterClipEndsStartsAtFrameZero() {
        assertEquals(0L, capsuleVideoStartupSeek(180_000L, 100_000L))
        assertEquals(0L, capsuleVideoStartupSeek(100_000L, 100_000L))
        assertEquals(40_000L, capsuleVideoStartupSeek(40_000L, 100_000L))
        assertEquals(0L, capsuleVideoStartupSeek(-5_000L, 100_000L))
    }

    @Test fun exactEndAndPastEndAreCompletionNotRetry() {
        assertFalse(capsuleVideoSeekCompletes(99_999L, 100_000L))
        assertTrue(capsuleVideoSeekCompletes(100_000L, 100_000L))
        assertTrue(capsuleVideoSeekCompletes(400_000L, 100_000L))
        assertFalse(capsuleVideoSeekCompletes(500_000L, C.TIME_UNSET))
        assertFalse(capsuleVideoSeekCompletes(0L, 0L))
    }

    @Test fun seekBoundsAreSafeForDecoderAndAudio() {
        assertEquals(0L, capsuleClampDecoderSeek(-300L, 100L))
        assertEquals(99L, capsuleClampDecoderSeek(100L, 100L))
        assertEquals(99L, capsuleClampDecoderSeek(Long.MAX_VALUE, 100L))
        assertEquals(100L, capsuleClampSeek(100L, 100L))
        assertEquals(100L, capsuleClampSeek(Long.MAX_VALUE, 100L))
        assertEquals(0L, capsuleClampSeek(-900L, C.TIME_UNSET))
        assertEquals(20_000L, capsuleClampDecoderSeek(20_000L, C.TIME_UNSET))
    }
}
