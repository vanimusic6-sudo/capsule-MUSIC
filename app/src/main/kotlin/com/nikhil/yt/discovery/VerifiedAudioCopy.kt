package com.nikhil.yt.discovery

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Never publish an empty, oversized or truncated publisher-approved recording. */
internal object VerifiedAudioCopy {
    fun copy(
        input: InputStream,
        output: OutputStream,
        expectedBytes: Long,
        maxBytes: Long,
        checkActive: () -> Unit,
    ): Long {
        if (expectedBytes > maxBytes) throw IOException("Recording exceeds size limit")
        val buffer = ByteArray(32 * 1024)
        var total = 0L
        while (true) {
            checkActive()
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) throw IOException("Media source returned no data")
            if (count > maxBytes - total) throw IOException("Recording exceeds size limit")
            if (expectedBytes >= 0L && count > expectedBytes - total) {
                throw IOException("Recording exceeds its declared length")
            }
            output.write(buffer, 0, count)
            total += count
        }
        checkActive()
        if (total <= 0L || (expectedBytes >= 0L && total != expectedBytes)) {
            throw IOException("Recording is empty or truncated")
        }
        return total
    }
}
