package com.nikhil.yt.together

import org.junit.Assert.*
import org.junit.Test

class TogetherLanCipherTest {
    private fun hello() = ClientHello(
        TogetherProtocolVersion, "secret-session-id", "join-secret", "client", "Alice"
    )

    @Test fun encryptsHandshakeAndRoundTrips() {
        val sender = TogetherLanCipher("join-secret")
        val receiver = TogetherLanCipher("join-secret")
        val message = sender.encode(hello())
        assertTrue(message.startsWith("capsule-lan-v2."))
        assertFalse(message.contains("join-secret"))
        assertFalse(message.contains("Alice"))
        assertEquals(hello(), receiver.decode(message))
        assertEquals(hello(), sender.decode(receiver.encode(hello())))
    }

    @Test fun rejectsPlaintextAndWrongSecret() {
        val cipher = TogetherLanCipher("join-secret")
        try {
            cipher.decode(TogetherJson.json.encodeToString(TogetherMessage.serializer(), hello()))
            fail("Plaintext accepted")
        } catch (_: IllegalArgumentException) { }
        try {
            TogetherLanCipher("wrong-secret").decode(cipher.encode(hello()))
            fail("Wrong key accepted")
        } catch (_: Exception) { }
    }

    @Test fun rejectsTamperingAndReplay() {
        val cipher = TogetherLanCipher("join-secret")
        val packet = cipher.encode(hello())
        cipher.decode(packet)
        try { cipher.decode(packet); fail("Replay accepted") }
        catch (_: IllegalArgumentException) { }
        val fresh = cipher.encode(hello())
        try { cipher.decode(fresh.dropLast(5) + "AAAAA"); fail("Tampering accepted") }
        catch (_: Exception) { }
    }
}
