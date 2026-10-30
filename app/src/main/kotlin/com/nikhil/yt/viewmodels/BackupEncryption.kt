package com.nikhil.yt.viewmodels

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Cross-device, password-protected backups. Each 64 KiB record is separately
 * authenticated with AES-256-GCM and an incrementing nonce; the authenticated
 * zero-length final record detects truncated archives. No whole-file buffering.
 *
 * Format v1: magic(8) | salt(16) | random nonce prefix(4) |
 *            repeat [ encryptedLength(4) | AES-GCM(record, header+counter AAD) ].
 * The final record has zero plaintext (16-byte tag). Legacy ZIP remains
 * readable and can only be exported with an explicit privacy warning.
 *
 * A password, unlike Android Keystore, allows restoration on another device.
 */
internal object BackupEncryption {
    private val MAGIC = "CAPSENC1".toByteArray(StandardCharsets.US_ASCII)
    private const val SALT_SIZE = 16
    private const val NONCE_PREFIX_SIZE = 4
    private const val BLOCK_SIZE = 64 * 1024
    private const val TAG_SIZE = 16
    private const val PBKDF2_ROUNDS = 220_000
    private const val MIN_PASSWORD_LENGTH = 10

    fun isValidPassword(password: String) = password.length >= MIN_PASSWORD_LENGTH

    fun encryptingStream(output: OutputStream, password: String): OutputStream {
        require(isValidPassword(password)) { "Use a backup password with at least 10 characters" }
        val random = SecureRandom()
        val salt = ByteArray(SALT_SIZE).also(random::nextBytes)
        val prefix = ByteArray(NONCE_PREFIX_SIZE).also(random::nextBytes)
        val header = MAGIC + salt + prefix
        val key = deriveKey(password, salt)
        val target = DataOutputStream(output)
        target.write(header)
        return object : OutputStream() {
            private val pending = ByteArray(BLOCK_SIZE)
            private var size = 0
            private var nextCounter = 0L
            private var total = 0L
            private var closed = false

            private fun frame(length: Int) {
                val nonce = prefix + ByteBuffer.allocate(8).putLong(nextCounter).array()
                val aes = Cipher.getInstance("AES/GCM/NoPadding")
                aes.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
                aes.updateAAD(header)
                aes.updateAAD(ByteBuffer.allocate(8).putLong(nextCounter).array())
                val encrypted = aes.doFinal(pending, 0, length)
                target.writeInt(encrypted.size)
                target.write(encrypted)
                nextCounter++
            }

            override fun write(value: Int) {
                check(!closed) { "Backup stream closed" }
                pending[size++] = value.toByte()
                if (size == pending.size) {
                    total += size
                    require(total <= BackupSecurity.MAX_ARCHIVE_BYTES) { "Backup is too large" }
                    frame(size)
                    size = 0
                }
            }

            override fun write(bytes: ByteArray, off: Int, len: Int) {
                check(!closed) { "Backup stream closed" }
                if (off < 0 || len < 0 || off > bytes.size - len) {
                    throw IndexOutOfBoundsException()
                }
                var offset = off
                var remaining = len
                while (remaining > 0) {
                    val count = minOf(remaining, BLOCK_SIZE - size)
                    bytes.copyInto(pending, size, offset, offset + count)
                    size += count
                    offset += count
                    remaining -= count
                    if (size == BLOCK_SIZE) {
                        total += size
                        require(total <= BackupSecurity.MAX_ARCHIVE_BYTES) { "Backup is too large" }
                        frame(size)
                        size = 0
                    }
                }
            }

            override fun flush() = target.flush()

            override fun close() {
                if (closed) return
                closed = true
                try {
                    total += size
                    require(total <= BackupSecurity.MAX_ARCHIVE_BYTES) { "Backup is too large" }
                    if (size > 0) frame(size)
                    frame(0) // authenticated end marker
                    target.flush()
                } finally {
                    pending.fill(0)
                    target.close()
                }
            }
        }
    }

    /** Return the original ZIP stream or its authenticated decrypting wrapper. */
    fun openBackup(input: InputStream, password: String?): InputStream {
        val source = PushbackInputStream(input, MAGIC.size)
        val probe = ByteArray(MAGIC.size)
        var count = 0
        while (count < probe.size) {
            val read = source.read(probe, count, probe.size - count)
            if (read < 0) break
            count += read
        }
        source.unread(probe, 0, count)
        if (count != MAGIC.size || !probe.contentEquals(MAGIC)) return source
        if (password.isNullOrEmpty()) throw IOException("This backup is encrypted. Enter its password.")
        return decryptingStream(source, password)
    }

    private fun decryptingStream(source: InputStream, password: String): InputStream {
        val input = DataInputStream(source)
        val magic = ByteArray(MAGIC.size).also(input::readFully)
        if (!magic.contentEquals(MAGIC)) throw IOException("Not a Capsule encrypted backup")
        val salt = ByteArray(SALT_SIZE).also(input::readFully)
        val prefix = ByteArray(NONCE_PREFIX_SIZE).also(input::readFully)
        val header = magic + salt + prefix
        val key = deriveKey(password, salt)
        return object : InputStream() {
            private var nextCounter = 0L
            private var position = 0
            private var plaintext = ByteArray(0)
            private var total = 0L
            private var finished = false
            private var closed = false

            private fun nextFrame(): Boolean {
                val length = try { input.readInt() } catch (_: EOFException) {
                    throw IOException("Truncated encrypted backup: missing authentication footer")
                }
                if (length !in TAG_SIZE..(BLOCK_SIZE + TAG_SIZE)) {
                    throw IOException("Invalid encrypted backup record size")
                }
                val bytes = ByteArray(length)
                input.readFully(bytes)
                val nonce = prefix + ByteBuffer.allocate(8).putLong(nextCounter).array()
                val aes = Cipher.getInstance("AES/GCM/NoPadding")
                aes.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce))
                aes.updateAAD(header)
                aes.updateAAD(ByteBuffer.allocate(8).putLong(nextCounter).array())
                val decoded = try {
                    aes.doFinal(bytes)
                } catch (_: GeneralSecurityException) {
                    throw IOException("Incorrect password or altered encrypted backup")
                }
                nextCounter++
                if (decoded.isEmpty()) {
                    finished = true
                    if (input.read() != -1) throw IOException("Data after encrypted backup footer")
                    plaintext = ByteArray(0)
                    return false
                }
                total += decoded.size
                if (total > BackupSecurity.MAX_ARCHIVE_BYTES) {
                    throw IOException("Encrypted backup is too large")
                }
                plaintext = decoded
                position = 0
                return true
            }

            override fun read(): Int {
                val single = ByteArray(1)
                return if (read(single, 0, 1) < 0) -1 else single[0].toInt() and 255
            }

            override fun read(buffer: ByteArray, off: Int, len: Int): Int {
                if (off < 0 || len < 0 || off > buffer.size - len) {
                    throw IndexOutOfBoundsException()
                }
                if (len == 0) return 0
                check(!closed) { "Backup stream closed" }
                while (position >= plaintext.size) {
                    if (finished || !nextFrame()) return -1
                }
                val count = minOf(len, plaintext.size - position)
                plaintext.copyInto(buffer, off, position, position + count)
                position += count
                return count
            }

            override fun close() {
                if (closed) return
                try {
                    // ZipInputStream may stop at its ZIP directory rather than
                    // the authenticated end marker. Always verify the footer.
                    val scratch = ByteArray(16 * 1024)
                    while (read(scratch) != -1) { /* authentication */ }
                } finally {
                    closed = true
                    plaintext.fill(0)
                    source.close()
                }
            }
        }
    }

    private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
        val chars = password.toCharArray()
        val spec = PBEKeySpec(chars, salt, PBKDF2_ROUNDS, 256)
        return try {
            val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            try { SecretKeySpec(raw, "AES") } finally { raw.fill(0) }
        } finally {
            chars.fill('\u0000')
            spec.clearPassword()
        }
    }
}
