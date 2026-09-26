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
    fun baseOrderRoundTrips() {
        assertEquals(
            CapsuleLightBaseOrder,
            decodeCapsuleLightOrder(CapsuleLightBaseOrderEncoded),
        )
    }

    @Test
    fun validCustomOrderIsPreserved() {
        val custom =
            listOf(
                CapsuleLightBlock.METADATA,
                CapsuleLightBlock.ARTWORK,
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
    fun malformedOrdersFallBackToBase() {
        val malformed =
            listOf(
                "",
                "ARTWORK,METADATA",
                "ARTWORK,METADATA,PROGRESS,MODE_SWITCH,MODE_SWITCH",
                "ARTWORK,METADATA,PROGRESS,MODE_SWITCH,UNKNOWN",
            )

        malformed.forEach { raw ->
            assertEquals(CapsuleLightBaseOrder, decodeCapsuleLightOrder(raw))
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
