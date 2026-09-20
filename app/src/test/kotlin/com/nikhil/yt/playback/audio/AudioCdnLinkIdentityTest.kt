package com.nikhil.yt.playback.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

    @Test fun aFreshSignedLinkCanBeDistinguishedFromARetry() {
        val first = AudioCdnLinkIdentity.ref("https://example.invalid/audio?sig=first")
        val renewed = AudioCdnLinkIdentity.ref("https://example.invalid/audio?sig=renewed")
        assertNotEquals(first, renewed)
    }
}
