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
    fun suppliedCapsuleLightFileMatchesShippedLightDefault() {
        // Keep this fixture identical to the user's actual Capsule-light.capsule.
        val supplied = """
            CAPSULE-DESIGN/1
            target=LIGHT
            order=ARTWORK%2CLYRIC%2CMETADATA%2CPROGRESS%2CMODE_SWITCH%2CCONTROLS
            positions=ARTWORK%3D0.00%2CLYRIC%3D302.57%2CMETADATA%3D372.57%2CPROGRESS%3D433.71%2CMODE_SWITCH%3D508.00%2CCONTROLS%3D576.00
            metadata=TEXT%2CFAVORITE
            mode=SHUFFLE%2CAUDIO_VIDEO%2CSLEEP
            av=AUDIO%2CVIDEO
            transport=REPEAT%2CPREVIOUS%2CPLAY_PAUSE%2CNEXT%2CMENU
            artworkWidth=1.0
            artworkHeight=1.081483
            gaps=ARTWORK%3D0%2CLYRIC%3D0%2CMETADATA%3D0%2CPROGRESS%3D0%2CMODE_SWITCH%3D0%2CCONTROLS%3D0
            lyricLine=0
        """.trimIndent()

        val decoded = requireNotNull(CapsuleDesignPresetCodec.decode(supplied))
        val reference = CapsuleLightReferenceLayout.preset
        assertEquals(CapsuleCustomizeTarget.LIGHT, decoded.target)
        assertEquals(reference.layoutOrder, decoded.layoutOrder)
        assertEquals(reference.canvasPositions, decoded.canvasPositions)
        assertEquals(reference.metadataOrder, decoded.metadataOrder)
        assertEquals(reference.modeOrder, decoded.modeOrder)
        assertEquals(reference.avOrder, decoded.avOrder)
        assertEquals(reference.transportOrder, decoded.transportOrder)
        assertEquals(reference.artworkWidthScale, decoded.artworkWidthScale)
        assertEquals(reference.artworkHeightScale, decoded.artworkHeightScale)
        assertEquals(reference.lyricLineEnabled, decoded.lyricLineEnabled)
        assertEquals(
            decodeCapsuleLightBlockGaps(reference.blockGaps.orEmpty()),
            decodeCapsuleLightBlockGaps(decoded.blockGaps.orEmpty()),
        )
        assertEquals(reference.canvasPositions, CapsuleDesignPresetCodec.decode(
            CapsuleDesignPresetCodec.encode(reference),
        )?.canvasPositions)
    }

    @Test
    fun invalidPresetIsRejected() {
        assertNull(CapsuleDesignPresetCodec.decode("not-a-capsule-preset"))
    }
}
