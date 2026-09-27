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
    fun smallOuterBlockCanDisplaceLargeModeSwitchAtEitherEdge() {
        assertEquals(
            LightEdgeReplacement.TOP,
            chooseLightEdgeReplacement(
                currentIndex = 3,
                lastIndex = 5,
                commandTopPx = 55f,
                commandHeightPx = 50f,
                firstTopPx = 0f,
                firstHeightPx = 140f,
                lastTopPx = 900f,
                lastHeightPx = 140f,
            ),
        )

        assertEquals(
            LightEdgeReplacement.BOTTOM,
            chooseLightEdgeReplacement(
                currentIndex = 2,
                lastIndex = 5,
                commandTopPx = 995f,
                commandHeightPx = 50f,
                firstTopPx = 0f,
                firstHeightPx = 140f,
                lastTopPx = 900f,
                lastHeightPx = 140f,
            ),
        )

        assertEquals(
            null,
            chooseLightEdgeReplacement(
                currentIndex = 0,
                lastIndex = 5,
                commandTopPx = 0f,
                commandHeightPx = 50f,
                firstTopPx = 0f,
                firstHeightPx = 140f,
                lastTopPx = 900f,
                lastHeightPx = 140f,
            ),
        )
    }

    @Test
    fun artworkResizeKeepsItsAnchorAndDoesNotPullUnrelatedBlocks() {
        val order =
            listOf(
                CapsuleLightBlock.METADATA,
                CapsuleLightBlock.ARTWORK,
                CapsuleLightBlock.MODE_SWITCH,
                CapsuleLightBlock.CONTROLS,
            )
        val positions =
            mapOf(
                CapsuleLightBlock.METADATA to 20f,
                CapsuleLightBlock.ARTWORK to 200f,
                CapsuleLightBlock.MODE_SWITCH to 650f,
                CapsuleLightBlock.CONTROLS to 850f,
            )
        val shrinkHeights =
            mapOf(
                CapsuleLightBlock.METADATA to 80f,
                CapsuleLightBlock.ARTWORK to 200f,
                CapsuleLightBlock.MODE_SWITCH to 100f,
                CapsuleLightBlock.CONTROLS to 120f,
            )

        val shrunk =
            projectArtworkResizePositions(
                order = order,
                preferredPositions = positions,
                heights = shrinkHeights,
                canvasHeightPx = 1100f,
                gapPx = 8f,
            )!!

        assertEquals(200f, shrunk.getValue(CapsuleLightBlock.ARTWORK), 0.001f)
        assertEquals(650f, shrunk.getValue(CapsuleLightBlock.MODE_SWITCH), 0.001f)
        assertEquals(850f, shrunk.getValue(CapsuleLightBlock.CONTROLS), 0.001f)

        val growHeights =
            shrinkHeights + (CapsuleLightBlock.ARTWORK to 500f)
        val grown =
            projectArtworkResizePositions(
                order = order,
                preferredPositions = positions,
                heights = growHeights,
                canvasHeightPx = 1200f,
                gapPx = 8f,
            )!!

        assertEquals(200f, grown.getValue(CapsuleLightBlock.ARTWORK), 0.001f)
        assertEquals(708f, grown.getValue(CapsuleLightBlock.MODE_SWITCH), 0.001f)
        assertEquals(850f, grown.getValue(CapsuleLightBlock.CONTROLS), 0.001f)
    }

    @Test
    fun storedCoordinatesNeverOverrideCanonicalBlockOrder() {
        val order =
            listOf(
                CapsuleLightBlock.MODE_SWITCH,
                CapsuleLightBlock.ARTWORK,
                CapsuleLightBlock.CONTROLS,
            )
        val heights =
            mapOf(
                CapsuleLightBlock.MODE_SWITCH to 100f,
                CapsuleLightBlock.ARTWORK to 300f,
                CapsuleLightBlock.CONTROLS to 120f,
            )
        val scrambledCoordinates =
            mapOf(
                CapsuleLightBlock.MODE_SWITCH to 900f,
                CapsuleLightBlock.ARTWORK to 100f,
                CapsuleLightBlock.CONTROLS to 500f,
            )

        val (resolvedOrder, _) =
            normalizedStoredPositions(
                order = order,
                requested = scrambledCoordinates,
                heights = heights,
                canvasHeightPx = 1200f,
                gapPx = 8f,
            )

        assertEquals(order, resolvedOrder)
    }

    @Test
    fun rowSwapThresholdUsesCommandEdgesSoWideItemsCanReplaceNarrowEdges() {
        assertTrue(
            lightRowCrossedBefore(
                commandStartPx = 100f,
                neighbourCenterPx = 100f,
            ),
        )
        assertTrue(
            lightRowCrossedAfter(
                commandEndPx = 100f,
                neighbourCenterPx = 100f,
            ),
        )

        assertFalse(
            lightRowCrossedBefore(
                commandStartPx = 101f,
                neighbourCenterPx = 100f,
            ),
        )
        assertFalse(
            lightRowCrossedAfter(
                commandEndPx = 99f,
                neighbourCenterPx = 100f,
            ),
        )

        // A wide AUDIO/VIDEO command can reach the right edge with its trailing edge even when
        // its center can never reach the tiny SLEEP item's center.
        assertTrue(
            lightRowCrossedAfter(
                commandEndPx = 620f,
                neighbourCenterPx = 600f,
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
