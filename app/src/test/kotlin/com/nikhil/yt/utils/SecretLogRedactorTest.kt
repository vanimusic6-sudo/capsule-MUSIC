package com.nikhil.yt.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretLogRedactorTest {
    @Test fun bearerAndCookieAreRedacted() {
        val result = SecretLogRedactor.redact("Authorization: Bearer abcdefghijklmno cookie=SID123456; code=403")
        assertFalse(result.contains("abcdefghijklmno"))
        assertFalse(result.contains("SID123456"))
        assertTrue(result.contains("403"))
    }

    @Test fun queryKeyIsNeverExported() {
        val result = SecretLogRedactor.redact("join capsule://together?sid=room123&key=neverPrintThis")
        assertFalse(result.contains("neverPrintThis"))
        assertTrue(result.contains("together"))
    }
}
