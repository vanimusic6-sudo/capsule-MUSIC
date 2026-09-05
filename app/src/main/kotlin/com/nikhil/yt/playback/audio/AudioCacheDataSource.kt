package com.nikhil.yt.playback.audio

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

internal enum class AudioCacheSource { DOWNLOAD, PLAYER }

/** A partial download must never shadow a complete player-cache file with the same legacy id. */
internal class AudioCacheDataSource(
    private val player: DataSource,
    private val download: DataSource,
) : DataSource {
    private var active: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        player.addTransferListener(transferListener)
        download.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        check(active == null)
        val selected = if (dataSpec.customData == AudioCacheSource.DOWNLOAD) download else player
        active = selected
        try {
            return selected.open(dataSpec)
        } catch (failure: Throwable) {
            try { selected.close() } catch (closeFailure: Throwable) { failure.addSuppressed(closeFailure) }
            active = null
            throw failure
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        requireNotNull(active).read(buffer, offset, length)

    override fun getUri(): Uri? = active?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders.orEmpty()
    override fun close() {
        val source = active
        active = null
        source?.close()
    }
}
