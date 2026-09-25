package com.nikhil.yt.playback.queues

import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.offline.Download
import com.nikhil.yt.innertube.soundcloud.SoundCloudNewPipe
import com.nikhil.yt.models.MediaMetadata
import com.nikhil.yt.soundcloud.SoundCloudCatalog
import com.nikhil.yt.soundcloud.soundCloudMediaId
import com.nikhil.yt.soundcloud.toDownloadedSoundCloudMediaItem
import com.nikhil.yt.soundcloud.toSoundCloudMediaItem
import com.nikhil.yt.soundcloud.toSoundCloudMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible

internal class SoundCloudQueue private constructor(
    private val queueTitle: String?,
    private val firstTrack: SoundCloudCatalog.Track,
    private val pendingTracks: List<SoundCloudCatalog.Track>,
    private val downloads: Map<String, Download>,
) : Queue {
    override val preloadItem: MediaMetadata = firstTrack.toSoundCloudMetadata()

    private var nextTrackIndex = 0
    private var pageAnchor: MediaItem? = null

    override suspend fun getInitialStatus(): Queue.Status {
        val firstItem = resolveTrack(firstTrack)
            ?: return Queue.Status(queueTitle, emptyList(), 0, 0L)
        pageAnchor = firstItem
        return Queue.Status(queueTitle, listOf(firstItem), 0, 0L)
    }

    override fun hasNextPage(): Boolean = nextTrackIndex < pendingTracks.size

    override suspend fun nextPage(): List<MediaItem> {
        val anchor = pageAnchor ?: return emptyList()
        if (!hasNextPage()) return listOf(anchor)

        val end = (nextTrackIndex + PAGE_SIZE).coerceAtMost(pendingTracks.size)
        val batch = pendingTracks.subList(nextTrackIndex, end)
        nextTrackIndex = end

        val resolved = coroutineScope {
            batch.map { track ->
                async { resolveTrack(track) }
            }.awaitAll().filterNotNull()
        }

        if (resolved.isEmpty()) return listOf(anchor)

        return buildList {
            add(anchor)
            addAll(resolved)
        }.also {
            pageAnchor = resolved.last()
        }
    }

    private suspend fun resolveTrack(
        track: SoundCloudCatalog.Track,
    ): MediaItem? {
        val mediaId = soundCloudMediaId(track.permalink)
        downloads[mediaId]
            ?.takeIf { it.state == Download.STATE_COMPLETED }
            ?.let { completed ->
                return track.toDownloadedSoundCloudMediaItem(completed)
            }

        return try {
            runInterruptible(Dispatchers.IO) {
                track.toSoundCloudMediaItem(
                    SoundCloudNewPipe.resolve(track.permalink),
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val PAGE_SIZE = 8

        fun create(
            title: String?,
            tracks: List<SoundCloudCatalog.Track>,
            requestedStartUrl: String?,
            downloads: Map<String, Download> = emptyMap(),
        ): SoundCloudQueue? {
            val unique = tracks.distinctBy { it.permalink }
            if (unique.isEmpty()) return null

            val startIndex = requestedStartUrl
                ?.let { url -> unique.indexOfFirst { it.permalink == url } }
                ?.takeIf { it >= 0 }
                ?: 0

            return SoundCloudQueue(
                queueTitle = title,
                firstTrack = unique[startIndex],
                pendingTracks = buildList {
                    addAll(unique.drop(startIndex + 1))
                    addAll(unique.take(startIndex))
                },
                downloads = downloads,
            )
        }
    }
}
