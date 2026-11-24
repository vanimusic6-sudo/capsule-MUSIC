package com.nikhil.yt.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CapsuleEqPresetsTest {
    @Test fun soundModesAreBoundedAndResolveOldIds() {
        val modes = CapsuleEqPresets.entries
        assertEquals(4, modes.size)
        assertEquals(4, modes.map { it.sound }.distinct().size)
        assertEquals(CapsuleEqPresets.WARM, CapsuleEqPresets.byId("capsule:soft_bass_v2"))
        assertEquals(CapsuleEqPresets.CLEAR, CapsuleEqPresets.byId("vocal_v2"))
        assertNull(CapsuleEqPresets.byId("unknown"))
        assertTrue(CapsuleEqPresets.AUTO.sound.adaptive)
        assertFalse(CapsuleEqPresets.TAPE.sound.mixer.enabled)
        assertFalse(CapsuleEqPresets.WARM.sound.mixer.enabled)
        for (mode in modes) {
            val curve = mode.sound.levelsMb
            assertEquals(5, curve.size)
            assertEquals(mode, CapsuleEqPresets.byId("capsule:${mode.id}"))
            assertTrue(curve.indices.all { i -> curve[i] in capsuleStudioBandLimit(CAPSULE_STUDIO_FREQUENCIES_HZ[i]) })
            if (mode == CapsuleEqPresets.WARM || mode == CapsuleEqPresets.CLEAR) {
                assertTrue(curve.max() - curve.min() in 300..750)
            }
        }
    }
}
