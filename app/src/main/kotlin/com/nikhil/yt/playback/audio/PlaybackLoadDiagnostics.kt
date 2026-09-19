package com.nikhil.yt.playback.audio

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import com.nikhil.yt.utils.GlobalLog
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Debug-only timing probes for the part of playback that starts after a stream URL is resolved.
 *
 * The probes deliberately never log a URI, response headers, cookies, PoTokens or query strings.
 * When debug logging is disabled the callbacks return before allocating state or formatting text.
 */
@UnstableApi
internal object PlaybackLoadDiagnostics : AnalyticsListener {
    private const val TAG = "PlaybackLoad"

    override fun onLoadStarted(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
        retryCount: Int,
    ) {
        if (!GlobalLog.isEnabled) return
        Timber.tag(TAG).d(
            "media-load-start task=%d dataType=%d trackType=%d retry=%d",
            loadEventInfo.loadTaskId,
            mediaLoadData.dataType,
            mediaLoadData.trackType,
            retryCount,
        )
    }

    override fun onLoadCompleted(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
    ) {
        if (!GlobalLog.isEnabled) return
        Timber.tag(TAG).d(
            "media-load-complete task=%d dataType=%d trackType=%d elapsedMs=%d bytes=%d",
            loadEventInfo.loadTaskId,
            mediaLoadData.dataType,
            mediaLoadData.trackType,
            loadEventInfo.loadDurationMs,
            loadEventInfo.bytesLoaded,
        )
    }

    override fun onLoadCanceled(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
    ) {
        if (!GlobalLog.isEnabled) return
        Timber.tag(TAG).d(
            "media-load-cancel task=%d dataType=%d trackType=%d elapsedMs=%d bytes=%d",
            loadEventInfo.loadTaskId,
            mediaLoadData.dataType,
            mediaLoadData.trackType,
            loadEventInfo.loadDurationMs,
            loadEventInfo.bytesLoaded,
        )
    }

    override fun onLoadError(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
        error: IOException,
        wasCanceled: Boolean,
    ) {
        if (!GlobalLog.isEnabled) return
        Timber.tag(TAG).w(
            "media-load-error task=%d dataType=%d trackType=%d elapsedMs=%d bytes=%d canceled=%s error=%s",
            loadEventInfo.loadTaskId,
            mediaLoadData.dataType,
            mediaLoadData.trackType,
            loadEventInfo.loadDurationMs,
            loadEventInfo.bytesLoaded,
            wasCanceled,
            error.javaClass.simpleName,
        )
    }
}

/**
 * Network transfer probe used by the playback HTTP DataSource.
 * `onBytesTransferred` is the closest observation point to the real first body bytes reaching
 * Media3 and therefore lets us distinguish slow CDN/HTTP startup from later extractor/decoder work.
 */
@UnstableApi
internal object PlaybackTransferDiagnostics : TransferListener {
    private const val TAG = "PlaybackLoad"

    private data class TransferState(
        val startedAtNs: Long,
        var totalBytes: Long = 0L,
        var firstByteLogged: Boolean = false,
    )

    private val activeTransfers = ConcurrentHashMap<DataSource, TransferState>()

    override fun onTransferInitializing(
        source: DataSource,
        dataSpec: DataSpec,
        isNetwork: Boolean,
    ) {
        if (!GlobalLog.isEnabled || !isNetwork) return
        Timber.tag(TAG).d(
            "network-init key=%s pos=%d length=%d",
            safeKey(dataSpec),
            dataSpec.position,
            dataSpec.length,
        )
    }

    override fun onTransferStart(
        source: DataSource,
        dataSpec: DataSpec,
        isNetwork: Boolean,
    ) {
        if (!GlobalLog.isEnabled || !isNetwork) return
        activeTransfers[source] = TransferState(System.nanoTime())
        Timber.tag(TAG).d(
            "network-start key=%s pos=%d length=%d",
            safeKey(dataSpec),
            dataSpec.position,
            dataSpec.length,
        )
    }

    override fun onBytesTransferred(
        source: DataSource,
        dataSpec: DataSpec,
        isNetwork: Boolean,
        bytesTransferred: Int,
    ) {
        if (!isNetwork) return
        if (!GlobalLog.isEnabled) {
            activeTransfers.remove(source)
            return
        }
        val state = activeTransfers[source] ?: return
        state.totalBytes += bytesTransferred.toLong()
        if (!state.firstByteLogged && bytesTransferred > 0) {
            state.firstByteLogged = true
            Timber.tag(TAG).d(
                "network-first-bytes key=%s afterMs=%d chunkBytes=%d",
                safeKey(dataSpec),
                elapsedMs(state.startedAtNs),
                bytesTransferred,
            )
        }
    }

    override fun onTransferEnd(
        source: DataSource,
        dataSpec: DataSpec,
        isNetwork: Boolean,
    ) {
        if (!isNetwork) return
        val state = activeTransfers.remove(source) ?: return
        if (!GlobalLog.isEnabled) return
        Timber.tag(TAG).d(
            "network-end key=%s elapsedMs=%d bytes=%d",
            safeKey(dataSpec),
            elapsedMs(state.startedAtNs),
            state.totalBytes,
        )
    }

    private fun elapsedMs(startedAtNs: Long): Long =
        (System.nanoTime() - startedAtNs) / 1_000_000L

    private fun safeKey(dataSpec: DataSpec): String =
        dataSpec.key
            ?.take(80)
            ?.replace('\n', '_')
            ?.replace('\r', '_')
            ?: "<none>"
}
