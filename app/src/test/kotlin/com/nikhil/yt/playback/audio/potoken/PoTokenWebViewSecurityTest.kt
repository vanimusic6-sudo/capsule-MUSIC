package com.nikhil.yt.playback.audio.potoken

import android.app.Application
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PoTokenWebViewSecurityTest {
    @Test fun botGuardIntegrityBodyRoundTripsQuotesBackslashesAndControlCharacters() {
        val input = "proof \"quoted\" \\ slash\ncarriage\r\u0000"
        val json = botguardGenerateIntegrityBody("fixed-request", input)
        val data = JSONArray(json)
        assertEquals(2, data.length())
        assertEquals("fixed-request", data.getString(0))
        assertEquals(input, data.getString(1))
        assertFalse("Newline should be escaped, not inserted as JSON text", json.contains("slash\ncarriage"))
    }

    @Test fun botGuardIntegrityBodyCannotInjectAdditionalRpcArguments() {
        val payload = """", "malicious-extra", {"nested": true}, """"
        val data = JSONArray(botguardGenerateIntegrityBody("key", payload))
        assertEquals(2, data.length())
        assertEquals("key", data.getString(0))
        assertEquals(payload, data.getString(1))
    }
}
