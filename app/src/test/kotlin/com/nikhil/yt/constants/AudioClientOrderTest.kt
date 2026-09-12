package com.nikhil.yt.constants

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AudioClientOrderTest {
    @Test
    fun legacyWebSelectionMigratesToFirstPosition() {
        val order = AudioClientOrder.resolve(null, AudioStreamPolicy.WEB)
        assertEquals(AudioClientOrder.WEB_REMIX, order.first())
        assertEquals(AudioClientOrder.supportedProfiles.size, order.size)
    }

    @Test
    fun persistedOrderWinsAndNewProfilesAreAppendedWithoutDuplicates() {
        val order =
            AudioClientOrder.resolve(
                "TVHTML5_SIMPLY,WEB_REMIX,TVHTML5_SIMPLY,UNKNOWN",
                AudioStreamPolicy.VISIONOS,
            )

        assertEquals(AudioClientOrder.TVHTML5_SIMPLY, order.first())
        assertEquals(AudioClientOrder.WEB_REMIX, order[1])
        assertEquals(AudioClientOrder.supportedProfiles.size, order.size)
        assertEquals(order.size, order.distinct().size)
        assertFalse("TVHTML5" in order)
    }

    @Test
    fun encodedOrderRoundTripsAsCompleteVettedList() {
        val custom =
            listOf(
                AudioClientOrder.WEB_EMBEDDED,
                AudioClientOrder.TVHTML5_SIMPLY,
                AudioClientOrder.VISIONOS_0_1,
                AudioClientOrder.WEB_REMIX,
                AudioClientOrder.VISIONOS,
                AudioClientOrder.WEB_CREATOR,
            )

        assertEquals(
            custom,
            AudioClientOrder.resolve(
                AudioClientOrder.encode(custom),
                AudioStreamPolicy.VISIONOS,
            ),
        )
    }
}
