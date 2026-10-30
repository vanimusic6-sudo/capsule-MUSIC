package com.nikhil.yt.together

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Authenticated LAN frames, including hello. Plaintext v1 frames are rejected. */
internal class TogetherLanCipher(sessionKey: String) {
    private val key: SecretKeySpec
    private val random = SecureRandom()
    private val received = LinkedHashSet<String>()

    init {
        require(sessionKey.isNotBlank()) { "Missing invitation secret" }
        val derived = MessageDigest.getInstance("SHA-256").digest(
            ("Capsule Together LAN v2\u0000" + sessionKey).toByteArray(StandardCharsets.UTF_8)
        )
        key = SecretKeySpec(derived, "AES")
    }

    @Synchronized
    fun encode(message: TogetherMessage): String {
        val bytes = TogetherJson.json.encodeToString(
            TogetherMessage.serializer(), message
        ).toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_PLAINTEXT) { "Room frame too large" }
        val nonce = ByteArray(NONCE_SIZE).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
        cipher.updateAAD(AAD)
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(
            nonce + cipher.doFinal(bytes)
        )
    }

    @Synchronized
    fun decode(packet: String): TogetherMessage {
        require(packet.startsWith(PREFIX) && packet.length <= MAX_WIRE) {
            "Plaintext, unsupported or oversized frame"
        }
        val bytes = Base64.getUrlDecoder().decode(packet.removePrefix(PREFIX))
        require(bytes.size >= NONCE_SIZE + 16) { "Invalid encrypted room frame" }
        val nonce = bytes.copyOfRange(0, NONCE_SIZE)
        val nonceId = Base64.getUrlEncoder().withoutPadding().encodeToString(nonce)
        require(nonceId !in received) { "Replayed room frame" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce))
        cipher.updateAAD(AAD)
        val plaintext = cipher.doFinal(bytes, NONCE_SIZE, bytes.size - NONCE_SIZE)
        require(plaintext.size <= MAX_PLAINTEXT) { "Decrypted frame too large" }
        val message = TogetherJson.json.decodeFromString(
            TogetherMessage.serializer(), String(plaintext, StandardCharsets.UTF_8)
        )
        received.add(nonceId)
        if (received.size > 4096) {
            val it = received.iterator()
            it.next()
            it.remove()
        }
        return message
    }

    private companion object {
        const val NONCE_SIZE = 12
        const val MAX_PLAINTEXT = 160 * 1024
        const val MAX_WIRE = 240 * 1024
        const val PREFIX = "capsule-lan-v2."
        val AAD = "Capsule Together LAN frame v2".toByteArray(StandardCharsets.UTF_8)
    }
}
