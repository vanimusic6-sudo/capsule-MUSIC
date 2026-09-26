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
    fun blockGapsRoundTripAndClamp() {
        val custom =
            CapsuleLightBaseGaps.toMutableMap().apply {
                this[CapsuleLightBlock.METADATA] = 42.5f
                this[CapsuleLightBlock.CONTROLS] = 240f
            }

        val decoded = decodeCapsuleLightBlockGaps(encodeCapsuleLightBlockGaps(custom))

        assertEquals(42.5f, decoded[CapsuleLightBlock.METADATA] ?: -1f, 0.01f)
        assertEquals(240f, decoded[CapsuleLightBlock.CONTROLS] ?: -1f, 0.01f)
        assertEquals(0f, decoded[CapsuleLightBlock.ARTWORK] ?: -1f, 0.01f)
    }

    @Test
    fun crowdedCanvasPositionsAreProjectedWithoutInvertedRange() {
        val order = CapsuleLightBaseOrder
        val heights =
            mapOf(
                CapsuleLightBlock.ARTWORK to 500f,
                CapsuleLightBlock.LYRIC to 96f,
                CapsuleLightBlock.METADATA to 214f,
                CapsuleLightBlock.PROGRESS to 82f,
                CapsuleLightBlock.MODE_SWITCH to 110f,
                CapsuleLightBlock.CONTROLS to 188f,
            )
        val canvasHeight = 2016f
        val gap = 24f

        // This deliberately crowds the first stored item against the bottom. The old normalizer
        // clamped it near maxTop, then advanced minimumTop past the next block's maxTop and crashed
        // in coerceIn(minimumTop, maxTop).
        val requested =
            order.mapIndexed { index, block ->
                block to (1600f + index * 20f)
            }.toMap()

        val (resolvedOrder, positions) =
            normalizedStoredPositions(
                order = order,
                requested = requested,
                heights = heights,
                canvasHeightPx = canvasHeight,
                gapPx = gap,
            )

        assertEquals(order, resolvedOrder)
        assertEquals(order.toSet(), positions.keys)

        resolvedOrder.forEachIndexed { index, block ->
            val top = positions.getValue(block)
            val bottom = top + heights.getValue(block)
            assertTrue(top >= -0.001f)
            assertTrue(bottom <= canvasHeight + 0.001f)

            if (index < resolvedOrder.lastIndex) {
                val next = resolvedOrder[index + 1]
                assertTrue(
                    bottom + gap <=
                        positions.getValue(next) + 0.001f,
                )
            }
        }
    }

    @Test
    fun processGuardChecksRecoveryOnlyOncePerProcess() {
        assertTrue(CapsuleLightEditorProcessGuard.shouldCheckRecovery())
        assertFalse(CapsuleLightEditorProcessGuard.shouldCheckRecovery())

        CapsuleLightEditorProcessGuard.resetForTesting()

        assertTrue(CapsuleLightEditorProcessGuard.shouldCheckRecovery())
    }
}
