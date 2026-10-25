package com.nikhil.yt.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class CapsuleOpusCodecSelectorTest {
    private val legacy = listOf(
        "c2.android.opus.decoder", "c2.android.inproc.opus.decoder", "OMX.google.opus.decoder"
    )

    @Test fun prefersNativeProcessOnlyOnAndroid17WhenLegacyOpusIsDefault() {
        assertEquals(1, inProcessOpusPreferenceIndex(legacy, 37, "audio/opus", false, false))
        assertEquals(-1, inProcessOpusPreferenceIndex(legacy, 36, "audio/opus", false, false))
        assertEquals(-1, inProcessOpusPreferenceIndex(legacy, 37, "audio/mp4a-latm", false, false))
        assertEquals(-1, inProcessOpusPreferenceIndex(legacy, 37, "audio/opus", true, false))
        assertEquals(-1, inProcessOpusPreferenceIndex(legacy, 37, "audio/opus", false, true))
    }

    @Test fun preservesVendorHardwareCodecAndSystemsWithoutNewDecoder() {
        assertEquals(-1, inProcessOpusPreferenceIndex(
            listOf("c2.qti.opus.decoder", "c2.android.opus.decoder",
                "c2.android.inproc.opus.decoder"), 37, "audio/opus", false, false,
        ))
        assertEquals(-1, inProcessOpusPreferenceIndex(
            listOf("c2.android.opus.decoder"), 37, "audio/opus", false, false,
        ))
    }
}
