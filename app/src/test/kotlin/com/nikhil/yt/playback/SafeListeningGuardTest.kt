package com.nikhil.yt.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeListeningGuardTest {
    private fun cap(
        hardware: Float = 1f,
        headphones: Boolean = true,
        enabled: Boolean = true,
        override: Long = 0,
        now: Long = 100000L,
    ) = safeListeningAppVolumeCeiling(
        headphones, enabled, override, now, hardware
    )

    @Test fun defaultCapsOnlyHighHeadphoneOutput() {
        assertEquals(0.6f, cap(1f), 0.0001f)
        assertEquals(1f, cap(0.5f), 0.0001f)
        assertEquals(0.75f, cap(0.8f), 0.0001f)
        assertEquals(1f, cap(headphones = false), 0.0001f)
    }

    @Test fun quietTracksAreNotUnnecessarilyTurnedDown() {
        assertEquals(0.3f, hearingCareOutputVolume(0.3f, cap()), 0.001f)
        assertEquals(0.6f, hearingCareOutputVolume(1f, cap()), 0.001f)
        assertEquals(0f, hearingCareOutputVolume(Float.NaN, 0.6f), 0.001f)
        assertEquals(0f, hearingCareOutputVolume(1f, Float.NaN), 0.001f)
    }

    @Test fun accessibilityOverridesArePossibleAndTimeBound() {
        assertTrue(safeListeningOverrideActive(100000L + SAFE_LISTENING_OVERRIDE_MS, 100000L))
        assertFalse(safeListeningOverrideActive(100000L, 100000L))
        assertFalse(safeListeningOverrideActive(100000L + SAFE_LISTENING_OVERRIDE_MS + 1, 100000L))
        assertEquals(1f,
            cap(override = 100000L + SAFE_LISTENING_OVERRIDE_MS), 0.0001f)
        assertEquals(1f, cap(enabled = false), 0.0001f)
        assertEquals(0.6f, cap(now = 100000L + SAFE_LISTENING_OVERRIDE_MS + 1,
            override = 100000L + SAFE_LISTENING_OVERRIDE_MS), 0.0001f)
    }

    @Test fun invalidHardwareDataCannotProduceLoudGain() {
        assertEquals(SAFE_LISTENING_RELATIVE_CEILING, cap(hardware = Float.NaN), 0.001f)
        assertEquals(SAFE_LISTENING_RELATIVE_CEILING, cap(hardware = Float.POSITIVE_INFINITY), 0.001f)
        assertEquals(SAFE_LISTENING_RELATIVE_CEILING, cap(hardware = -4f), 0.001f)
        assertTrue(cap(hardware = 1.4f) <= 1f)
        assertEquals(1f, hearingCareOutputVolume(9f, 1f), 0.001f)
    }
    @Test fun crossfadeSharesTheHeadphoneCeiling() {
        val ceiling = cap()
        for (step in 0..100) {
            val t = step / 100f
            val main = hearingCareOutputVolume(1.4f, ceiling, 1f - t)
            val overlap = hearingCareOutputVolume(1.4f, ceiling, t)
            assertEquals(ceiling, main + overlap, 0.000001f)
        }
    }
}
