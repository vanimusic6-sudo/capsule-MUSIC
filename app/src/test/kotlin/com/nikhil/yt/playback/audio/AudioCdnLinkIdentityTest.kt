package com.nikhil.yt.playback.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Test

class AudioCdnLinkIdentityTest {
    @Test fun identicalLinksHaveTheSameAnonymousReferenceWithinThisProcess() {
        val url = "https://example.invalid/audio?sig=a-secret&pot=private"
        val ref = AudioCdnLinkIdentity.ref(url)
        assertEquals(ref, AudioCdnLinkIdentity.ref(url))
        assertEquals(16, ref.length)
        assertFalse(ref.contains("secret"))
        assertFalse(ref.contains("private"))
        assertFalse(ref.contains("example.invalid"))
    }

    @Test fun signedHeadersHaveStableSaltedReferenceWithoutExposingAnySecret() {
        val original = Request.Builder()
            .url("https://example.invalid/audio?sig=super-secret&pot=private")
            .header("Range", "bytes=0-1048575")
            .header("Cookie", "SESSION=ultra-private")
            .header("Authorization", "Bearer hidden-token")
            .build()
        val same = original.newBuilder().build()
        val changedCookie = original.newBuilder().header("Cookie", "SESSION=other").build()
        val changedRange = original.newBuilder().header("Range", "bytes=1048576-2097151").build()

        val fingerprint = audioCdnHeaderRef(original)
        assertEquals(fingerprint, audioCdnHeaderRef(same))
        assertNotEquals(fingerprint, audioCdnHeaderRef(changedCookie))
        assertNotEquals(fingerprint, audioCdnHeaderRef(changedRange))
        assertEquals(16, fingerprint.length)
        assertFalse(fingerprint.contains("ultra-private"))
        assertFalse(fingerprint.contains("hidden-token"))
    }

    @Test fun onlyValidatedByteRangesCanAppearInExportedDiagnostics() {
        assertEquals("bytes=0-1048575", audioCdnSafeRange("bytes=0-1048575"))
        assertEquals("bytes=1048576-", audioCdnSafeRange("bytes=1048576-"))
        assertEquals("none", audioCdnSafeRange("bytes=0-1\\r\\nCookie: secret"))
        assertEquals("none", audioCdnSafeRange("private"))
        assertEquals("none", audioCdnSafeRange(null))
        assertEquals("0-1048575", audioCdnSafeBakedRange("0-1048575"))
        assertEquals("none", audioCdnSafeBakedRange("0-1048575&pot=secret"))
        assertEquals("none", audioCdnSafeBakedRange(null))
    }

    @Test fun urlQueryReferencesAreStableButNeverPrintTokenValues() {
        val url = "https://example.invalid/audio?pot=secret-one&sig=private-sig".toHttpUrl()
        val other = "https://example.invalid/audio?pot=secret-two&sig=private-sig".toHttpUrl()
        assertEquals(audioCdnQueryRef(url, "pot"), audioCdnQueryRef(url, "pot"))
        assertNotEquals(audioCdnQueryRef(url, "pot"), audioCdnQueryRef(other, "pot"))
        assertFalse(audioCdnQueryRef(url, "pot").contains("secret-one"))
        assertEquals("none", audioCdnQueryRef(url, "missing"))
    }

    @Test fun aFreshSignedLinkCanBeDistinguishedFromARetry() {
        val first = AudioCdnLinkIdentity.ref("https://example.invalid/audio?sig=first")
        val renewed = AudioCdnLinkIdentity.ref("https://example.invalid/audio?sig=renewed")
        assertNotEquals(first, renewed)
    }
}
