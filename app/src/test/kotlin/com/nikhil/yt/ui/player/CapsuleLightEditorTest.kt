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
    fun outerBlocksCanReplaceBothEdgeSlots() {
        assertEquals(
            LightEdgeReplacement.TOP,
            chooseLightEdgeReplacement(
                currentIndex = 3,
                lastIndex = 5,
                commandCenterPx = 80f,
                firstCenterPx = 80f,
                lastCenterPx = 900f,
            ),
        )

        assertEquals(
            LightEdgeReplacement.BOTTOM,
            chooseLightEdgeReplacement(
                currentIndex = 2,
                lastIndex = 5,
                commandCenterPx = 900f,
                firstCenterPx = 80f,
                lastCenterPx = 900f,
            ),
        )

        assertEquals(
            null,
            chooseLightEdgeReplacement(
                currentIndex = 0,
                lastIndex = 5,
                commandCenterPx = 20f,
                firstCenterPx = 80f,
                lastCenterPx = 900f,
            ),
        )
    }

    @Test
    fun topArtworkResizeMovesOriginOppositeToBottomResize() {
        // Pulling a TOP handle downward shrinks the artwork and moves its top downward,
        // preserving the old bottom edge.
        assertEquals(
            60f,
            artworkTopEdgeShiftDp(
                startHeightScale = 1f,
                currentHeightScale = 0.8f,
                baseSideDp = 300f,
            ),
            0.001f,
        )

        // Pulling upward grows from the top, so the ARTWORK origin moves upward.
        assertEquals(
            -60f,
            artworkTopEdgeShiftDp(
                startHeightScale = 1f,
                currentHeightScale = 1.2f,
                baseSideDp = 300f,
            ),
            0.001f,
        )
    }

    @Test
    fun rowSwapThresholdIsSymmetricAtClampedEdge() {
        assertTrue(
            lightRowCrossedBefore(
                commandCenterPx = 100f,
                neighbourCenterPx = 100f,
            ),
        )
        assertTrue(
            lightRowCrossedAfter(
                commandCenterPx = 100f,
                neighbourCenterPx = 100f,
            ),
        )

        assertFalse(
            lightRowCrossedBefore(
                commandCenterPx = 101f,
                neighbourCenterPx = 100f,
            ),
        )
        assertFalse(
            lightRowCrossedAfter(
                commandCenterPx = 99f,
                neighbourCenterPx = 100f,
            ),
        )
    }

    @Test
    fun zeroHeightOptionalBlockStillCountsAsMeasured() {
        val measured =
            CapsuleLightBaseOrder.associateWith { block ->
                if (block == CapsuleLightBlock.LYRIC) 0f else 100f
            }

        assertTrue(
            lightCanvasMeasurementsReady(
                order = CapsuleLightBaseOrder,
                measuredHeightsPx = measured,
            ),
        )

        assertFalse(
            lightCanvasMeasurementsReady(
                order = CapsuleLightBaseOrder,
                measuredHeightsPx = measured - CapsuleLightBlock.LYRIC,
            ),
        )
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
