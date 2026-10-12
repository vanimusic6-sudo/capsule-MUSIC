/* Capsule MUSIC — bounded startup timing, silent during normal playback. GPL-3.0. */
@file:Suppress("UnsafeOptInUsageError")
package com.nikhil.yt.playback.video

import android.net.Uri
import android.os.SystemClock
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import timber.log.Timber

internal class CapsuleVideoStartupDataSource(
    private val upstream: DataSource,
    private val preparing: () -> Boolean,
) : DataSource {
    private var startedAt = 0L
    private var reportFirstByte = false
    override fun addTransferListener(listener: TransferListener) = upstream.addTransferListener(listener)
    override fun open(spec: DataSpec): Long {
        reportFirstByte = preparing()
        if (reportFirstByte) startedAt = SystemClock.elapsedRealtime()
        val result = upstream.open(spec)
        if (reportFirstByte) Timber.tag("CapsuleVideo").i("VIDEO timing HTTP headers host=%s ms=%s position=%s length=%s",
            spec.uri.host, SystemClock.elapsedRealtime() - startedAt, spec.position, spec.length)
        return result
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val count = upstream.read(buffer, offset, length)
        if (reportFirstByte && count > 0) {
            reportFirstByte = false
            Timber.tag("CapsuleVideo").i("VIDEO timing HTTP first-byte host=%s ms=%s", upstream.uri?.host,
                SystemClock.elapsedRealtime() - startedAt)
        }
        return count
    }
    override fun getUri(): Uri? = upstream.uri
    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders
    override fun close() { reportFirstByte = false; upstream.close() }

    class Factory(private val upstream: DataSource.Factory, private val preparing: () -> Boolean) : DataSource.Factory {
        override fun createDataSource(): DataSource = CapsuleVideoStartupDataSource(upstream.createDataSource(), preparing)
    }
}
