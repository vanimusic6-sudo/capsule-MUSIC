package com.nikhil.yt.together

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TogetherOnlineCredentialsTest {
    @Test
    fun blankTokenIsNotConfigured() {
        assertNull(normalizeTogetherBearerToken(null))
        assertNull(normalizeTogetherBearerToken(""))
        assertNull(normalizeTogetherBearerToken("   \n\t "))
    }

    @Test
    fun configuredTokenIsTrimmedButOtherwisePreserved() {
        assertEquals("token-value", normalizeTogetherBearerToken("  token-value  "))
    }
}
