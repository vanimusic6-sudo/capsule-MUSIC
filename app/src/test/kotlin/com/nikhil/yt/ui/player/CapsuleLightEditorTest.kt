package com.nikhil.yt.ui.player

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapsuleLightEditorTest {
    @After
    fun tearDown() {
        CapsuleLightEditorProcessGuard.resetForTesting()
    }

    @Test
    fun baseContainerOrderRoundTrips() {
        assertEquals(
            CapsuleLightBaseOrder,
            decodeCapsuleLightOrder(CapsuleLightBaseOrderEncoded),
        )
    }

    @Test
    fun validContainerOrderIsPreserved() {
        val custom =
            listOf(
                CapsuleLightBlock.METADATA,
                CapsuleLightBlock.ARTWORK,
                CapsuleLightBlock.LYRIC,
                CapsuleLightBlock.CONTROLS,
                CapsuleLightBlock.PROGRESS,
                CapsuleLightBlock.MODE_SWITCH,
            )

        assertEquals(
            custom,
            decodeCapsuleLightOrder(encodeCapsuleLightOrder(custom)),
        )
    }

    @Test
    fun legacyFiveBlockOrderKeepsUserOrderAndAddsLyricAfterArtwork() {
        val legacy = "METADATA,ARTWORK,CONTROLS,PROGRESS,MODE_SWITCH"

        assertEquals(
            listOf(
                CapsuleLightBlock.METADATA,
                CapsuleLightBlock.ARTWORK,
                CapsuleLightBlock.LYRIC,
                CapsuleLightBlock.CONTROLS,
                CapsuleLightBlock.PROGRESS,
                CapsuleLightBlock.MODE_SWITCH,
            ),
            decodeCapsuleLightOrder(legacy),
        )
    }

    @Test
    fun nestedOrdersRoundTripWithoutFlatteningContainers() {
        val metadata = CapsuleLightMetadataBaseOrder.reversed()
        val mode =
            listOf(
                CapsuleLightModeItem.AUDIO_VIDEO,
                CapsuleLightModeItem.SHUFFLE,
                CapsuleLightModeItem.SLEEP,
            )
        val av = CapsuleLightAvBaseOrder.reversed()
        val transport =
            listOf(
                CapsuleLightTransportItem.MENU,
                CapsuleLightTransportItem.NEXT,
                CapsuleLightTransportItem.PLAY_PAUSE,
                CapsuleLightTransportItem.PREVIOUS,
                CapsuleLightTransportItem.REPEAT,
            )

        assertEquals(
            metadata,
            decodeCapsuleLightMetadataOrder(encodeCapsuleLightMetadataOrder(metadata)),
        )
        assertEquals(
            mode,
            decodeCapsuleLightModeOrder(encodeCapsuleLightModeOrder(mode)),
        )
        assertEquals(
            av,
            decodeCapsuleLightAvOrder(encodeCapsuleLightAvOrder(av)),
        )
        assertEquals(
            transport,
            decodeCapsuleLightTransportOrder(encodeCapsuleLightTransportOrder(transport)),
        )
    }

    @Test
    fun malformedOrdersFallBackToTheirOwnBase() {
        assertEquals(
            CapsuleLightBaseOrder,
            decodeCapsuleLightOrder("ARTWORK,METADATA"),
        )
        assertEquals(
            CapsuleLightModeBaseOrder,
            decodeCapsuleLightModeOrder("SHUFFLE,AUDIO,VIDEO,SLEEP"),
        )
        assertEquals(
            CapsuleLightTransportBaseOrder,
            decodeCapsuleLightTransportOrder("REPEAT,PREVIOUS,NEXT,NEXT,MENU"),
        )
    }

    @Test
    fun processGuardChecksRecoveryOnlyOncePerProcess() {
        assertTrue(CapsuleLightEditorProcessGuard.shouldCheckRecovery())
        assertFalse(CapsuleLightEditorProcessGuard.shouldCheckRecovery())

        CapsuleLightEditorProcessGuard.resetForTesting()

        assertTrue(CapsuleLightEditorProcessGuard.shouldCheckRecovery())
    }
}
