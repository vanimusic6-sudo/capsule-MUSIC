package com.nikhil.yt.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CapsuleEqPresetsTest {
    @Test fun fiveDistinctPresetsStayWithinLimitsAndKeepTheirSavedIds() {
        val presets = CapsuleEqPresets.all
        assertEquals(5, presets.size)
        assertEquals(5, presets.map { it.id }.distinct().size)
        assertEquals(5, presets.map { it.referenceLevelsMb }.distinct().size)
        assertNull(CapsuleEqPresets.byId("deep_bass"))
        for (preset in presets) {
            val curve = preset.referenceLevelsMb
            assertEquals(10, curve.size)
            assertEquals(preset, CapsuleEqPresets.byId("capsule:${preset.id}"))
            assertTrue(curve.indices.all { i -> curve[i] in capsuleStudioBandLimit(CAPSULE_STUDIO_FREQUENCIES_HZ[i]) })
            assertTrue(curve.max() - curve.min() >= 450)
            assertTrue(curve.zipWithNext().all { (a, b) -> kotlin.math.abs(a - b) <= 400 })
        }
    }
}
