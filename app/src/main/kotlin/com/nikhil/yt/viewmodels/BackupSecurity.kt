package com.nikhil.yt.viewmodels

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale

/**
 * Every manual backup and legacy restore must filter login credentials.
 * NOTE: downloaded database backups still contain history; warn on share.
 */
internal object BackupSecurity {
    const val MAX_SETTINGS_BYTES = 2L * 1024 * 1024
    const val MAX_DATABASE_BYTES = 256L * 1024 * 1024
    const val MAX_ARCHIVE_BYTES = 512L * 1024 * 1024
    const val MAX_PLAYLIST_BYTES = 8L * 1024 * 1024
    const val MAX_PLAYLIST_TRACKS = 20_000

    private val secretParts = listOf(
        "cookie", "token", "password", "secret", "authorization", "credential",
        "sessionkey", "session_key", "apikey", "api_key", "visitor",
        "datasync", "data_sync", "oauth", "refresh", "sapisid", "accountemail",
        "account_email", "accountname", "account_name", "channelhandle"
    )

    fun exportablePreference(name: String): Boolean {
        val key = name.lowercase(Locale.ROOT).replace("-", "_")
        return secretParts.none { it in key }
    }

    fun copyBounded(input: InputStream, output: OutputStream, maxBytes: Long): Long {
        require(maxBytes > 0)
        val buffer = ByteArray(16 * 1024)
        var copied = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return copied
            if (count == 0) continue
            if (count.toLong() > maxBytes - copied) throw IOException("Import exceeds safe size limit")
            output.write(buffer, 0, count)
            copied += count
        }
    }

    fun drainEntry(input: InputStream, maxBytes: Long): Long =
        copyBounded(input, object : OutputStream() {
            override fun write(b: Int) = Unit
            override fun write(b: ByteArray, off: Int, len: Int) = Unit
        }, maxBytes)

    fun readBounded(input: InputStream, maxBytes: Long): ByteArray =
        ByteArrayOutputStream().use { out ->
            copyBounded(input, out, maxBytes)
            out.toByteArray()
        }
}
