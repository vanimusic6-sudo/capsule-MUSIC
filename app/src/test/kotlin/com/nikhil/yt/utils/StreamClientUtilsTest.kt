package com.nikhil.yt.utils

import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test

class StreamClientUtilsTest {
    @Test fun authoritativeExtractionHeadersSurviveTheHttpInterceptor() {
        val request = Request.Builder()
            .url("https://rr.googlevideo.com/videoplayback?c=WEB_REMIX")
            .header("User-Agent", "required-agent")
            .header("Origin", "https://required.example")
            .header("Referer", "https://required.example/player")
            .header("Accept-Language", "ru-RU")
            .build()
        assertEquals(request.headers, StreamClientUtils.withFallbackHeaders(request).headers)
    }

    @Test fun lookalikeDomainsDoNotReceiveYoutubeHeaders() {
        val request = Request.Builder().url("https://notyoutube.com/track?c=WEB_REMIX").build()
        assertSame(request, StreamClientUtils.withFallbackHeaders(request))
    }
}
