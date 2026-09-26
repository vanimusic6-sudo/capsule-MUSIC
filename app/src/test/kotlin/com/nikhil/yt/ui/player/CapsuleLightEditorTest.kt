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
            CapsuleLightBaseOrder.toMutableList().apply {
                remove(CapsuleLightElement.FAVORITE)
                add(indexOf(CapsuleLightElement.PANEL_BREAK) + 1, CapsuleLightElement.FAVORITE)
                remove(CapsuleLightElement.VIDEO)
                add(indexOf(CapsuleLightElement.AUDIO), CapsuleLightElement.VIDEO)
            }

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
                "ARTWORK,TITLE",
                CapsuleLightBaseOrderEncoded.replace("MENU", "NEXT"),
                CapsuleLightBaseOrderEncoded.replace("MENU", "UNKNOWN"),
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
