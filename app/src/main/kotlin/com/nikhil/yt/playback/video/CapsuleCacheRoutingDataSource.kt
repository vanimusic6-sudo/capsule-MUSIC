/*
 * Capsule MUSIC
 * Routes VIDEO media into the dedicated VIDEO cache while normal AUDIO keeps
 * using the existing download/player-cache chain.
 * GPL-3.0
 */
package com.nikhil.yt.playback.video

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

class CapsuleCacheRoutingDataSource private constructor(
    audioFactory: DataSource.Factory,
    videoFactory: DataSource.Factory,
) : DataSource {
    private val audioDataSource = audioFactory.createDataSource()
    private val videoDataSource = videoFactory.createDataSource()
    private var activeDataSource: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        audioDataSource.addTransferListener(transferListener)
        videoDataSource.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        check(activeDataSource == null) { "DataSource already open" }

        val selected =
            if (isCapsuleVideo(dataSpec)) {
                videoDataSource
            } else {
                audioDataSource
            }

        activeDataSource = selected

        return try {
            selected.open(dataSpec)
        } catch (throwable: Throwable) {
            activeDataSource = null
            throw throwable
        }
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int =
        requireNotNull(activeDataSource) { "DataSource is not open" }
            .read(buffer, offset, length)

    override fun getUri(): Uri? =
        activeDataSource?.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        activeDataSource?.responseHeaders.orEmpty()

    override fun close() {
        val selected = activeDataSource
        activeDataSource = null
        selected?.close()
    }

    class Factory(
        private val audioFactory: DataSource.Factory,
        private val videoFactory: DataSource.Factory,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            CapsuleCacheRoutingDataSource(
                audioFactory = audioFactory,
                videoFactory = videoFactory,
            )
    }

    companion object {
        internal fun isCapsuleVideoKey(
            key: String?,
            uriScheme: String?,
        ): Boolean {
            val normalizedKey = key.orEmpty()
            if (
                normalizedKey.startsWith(CAPSULE_VIDEO_CACHE_PREFIX) ||
                normalizedKey.startsWith(CAPSULE_VIDEO_STREAM_CACHE_PREFIX)
            ) {
                return true
            }

            return uriScheme.equals(
                CAPSULE_VIDEO_SCHEME,
                ignoreCase = true,
            )
        }

        internal fun isCapsuleVideo(dataSpec: DataSpec): Boolean =
            isCapsuleVideoKey(
                key = dataSpec.key,
                uriScheme = dataSpec.uri.scheme,
            )
    }
}
