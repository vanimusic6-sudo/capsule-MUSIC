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

    @Test fun basicAndSignedStreamUrlAreRedacted() {
        val result = SecretLogRedactor.redact(
            "Authorization: Basic QWxhZGRpbjpvcGVuIHNlc2FtZQ== " +
                "https://cdn.example/media?sig=keepThisPrivate&lsig=alsoPrivate&n=secretNonce&status=200"
        )
        assertFalse(result.contains("QWxhZGRpbjpvcGVuIHNlc2FtZQ"))
        assertFalse(result.contains("keepThisPrivate"))
        assertFalse(result.contains("alsoPrivate"))
        assertFalse(result.contains("secretNonce"))
        assertTrue(result.contains("status=200"))
    }

    @Test fun queryKeyIsNeverExported() {
        val result = SecretLogRedactor.redact("join capsule://together?sid=room123&key=neverPrintThis")
        assertFalse(result.contains("neverPrintThis"))
        assertTrue(result.contains("together"))
    }
}
