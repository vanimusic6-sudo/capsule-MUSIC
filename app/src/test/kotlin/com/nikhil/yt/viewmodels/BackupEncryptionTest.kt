package com.nikhil.yt.viewmodels

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupEncryptionTest {
    private val password = "my safe test backup password"
    private val input = ByteArray(180_007) { (it * 37).toByte() }

    private fun encrypt(source: ByteArray): ByteArray =
        ByteArrayOutputStream().also { output ->
            BackupEncryption.encryptingStream(output, password).use { it.write(source) }
        }.toByteArray()

    private fun decrypt(source: ByteArray, pass: String = password): ByteArray =
        BackupEncryption.openBackup(ByteArrayInputStream(source), pass).use { it.readBytes() }

    @Test fun multiBlockStreamingRoundTripAndSmallChunkReads() {
        val encrypted = encrypt(input)
        assertTrue(encrypted.size > input.size)
        assertArrayEquals(input, decrypt(encrypted))
        assertArrayEquals(ByteArray(0), decrypt(encrypt(ByteArray(0))))
    }

    @Test fun rejectsIncorrectPasswordWithoutRevealingData() {
        assertThrows(IOException::class.java) {
            decrypt(encrypt(input), "a totally different password")
        }
    }

    @Test fun rejectsTamperingAndMissingAuthenticatedEndMarker() {
        val encrypted = encrypt(input)
        val changed = encrypted.clone()
        changed[50] = (changed[50].toInt() xor 1).toByte()
        assertThrows(IOException::class.java) { decrypt(changed) }
        assertThrows(IOException::class.java) {
            decrypt(encrypted.copyOf(encrypted.size - 8))
        }
        assertThrows(IOException::class.java) {
            decrypt(encrypted + byteArrayOf(1))
        }
    }

    @Test fun requiresPasswordForEncryptedBackup() {
        assertThrows(IOException::class.java) {
            BackupEncryption.openBackup(ByteArrayInputStream(encrypt(input)), null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackupEncryption.encryptingStream(ByteArrayOutputStream(), "short")
        }
    }

    @Test fun oldUnencryptedZipStillOpensUnmodified() {
        val legacy = byteArrayOf(0x50, 0x4b, 3, 4, 1, 2, 3, 4, 5)
        assertArrayEquals(legacy, BackupEncryption.openBackup(
            ByteArrayInputStream(legacy), null
        ).use { it.readBytes() })
    }
}
