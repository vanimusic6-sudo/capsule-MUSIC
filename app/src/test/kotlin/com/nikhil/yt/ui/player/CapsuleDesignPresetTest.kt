package com.nikhil.yt.ui.player

import com.nikhil.yt.constants.CapsuleCustomizeTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CapsuleDesignPresetTest {
    @Test
    fun lightPresetRoundTripsWithoutJsonRuntime() {
        val original =
            CapsuleDesignPreset(
                target = CapsuleCustomizeTarget.LIGHT,
                layoutOrder = CapsuleLightBaseOrderEncoded,
                canvasPositions = "ARTWORK=12.50,METADATA=430.25",
                metadataOrder = CapsuleLightMetadataBaseOrderEncoded,
                modeOrder = CapsuleLightModeBaseOrderEncoded,
                avOrder = CapsuleLightAvBaseOrderEncoded,
                transportOrder = CapsuleLightTransportBaseOrderEncoded,
                artworkWidthScale = 0.91f,
                artworkHeightScale = 1.17f,
                blockGaps = CapsuleLightBaseGapsEncoded,
                lyricLineEnabled = false,
            )

        val encoded = CapsuleDesignPresetCodec.encode(original)
        val decoded = CapsuleDesignPresetCodec.decode(encoded)

        assertTrue(encoded.startsWith("CAPSULE-DESIGN/1"))
        assertFalse(encoded.contains('{'))
        assertEquals(CapsuleCustomizeTarget.LIGHT, decoded?.target)
        assertEquals(0.91f, decoded?.artworkWidthScale)
        assertEquals(1.17f, decoded?.artworkHeightScale)
        assertEquals(false, decoded?.lyricLineEnabled)
        assertEquals(
            decodeCapsuleLightCanvasPositions(original.canvasPositions),
            decodeCapsuleLightCanvasPositions(decoded?.canvasPositions.orEmpty()),
        )
    }

    @Test
    fun invalidPresetIsRejected() {
        assertNull(CapsuleDesignPresetCodec.decode("not-a-capsule-preset"))
    }
}
