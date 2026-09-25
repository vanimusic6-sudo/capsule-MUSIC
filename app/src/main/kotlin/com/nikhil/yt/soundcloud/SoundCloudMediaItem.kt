package com.nikhil.yt.soundcloud

import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata.MEDIA_TYPE_MUSIC
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.offline.Download
import com.nikhil.yt.innertube.soundcloud.SoundCloudNewPipe
import com.nikhil.yt.models.MediaMetadata
import java.security.MessageDigest

internal const val SOUNDCLOUD_MEDIA_ID_PREFIX = "soundcloud:"

internal fun soundCloudMediaId(url: String): String =
    SOUNDCLOUD_MEDIA_ID_PREFIX + MessageDigest.getInstance("SHA-256")
        .digest(url.toByteArray(Charsets.UTF_8))
        .take(12)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

internal fun SoundCloudCatalog.Track.toSoundCloudMetadata() =
    MediaMetadata(
        id = soundCloudMediaId(permalink),
        title = title,
        artists = listOf(MediaMetadata.Artist(id = uploaderUrl, name = artist)),
        duration = durationSeconds.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
        thumbnailUrl = artworkUrl,
    )

internal fun SoundCloudCatalog.Track.toSoundCloudMediaItem(
    stream: SoundCloudNewPipe.Stream,
): MediaItem =
    buildSoundCloudMediaItem(
        uri = stream.url,
        isHls = stream.isHls,
        customCacheKey = if (stream.isHls) null else soundCloudMediaId(permalink),
    )

internal fun SoundCloudCatalog.Track.toDownloadedSoundCloudMediaItem(
    download: Download,
): MediaItem {
    require(download.request.id == soundCloudMediaId(permalink))
    val isHls = download.request.mimeType == MimeTypes.APPLICATION_M3U8
    return buildSoundCloudMediaItem(
        uri = download.request.uri.toString(),
        isHls = isHls,
        customCacheKey = download.request.customCacheKey,
    )
}

private fun SoundCloudCatalog.Track.buildSoundCloudMediaItem(
    uri: String,
    isHls: Boolean,
    customCacheKey: String?,
): MediaItem {
    val metadata = toSoundCloudMetadata()
    return MediaItem.Builder()
        .setMediaId(metadata.id)
        .setUri(uri)
        .apply {
            if (customCacheKey != null) {
                setCustomCacheKey(customCacheKey)
            }
            if (isHls) {
                setMimeType(MimeTypes.APPLICATION_M3U8)
            }
        }
        .setTag(metadata)
        .setMediaMetadata(
            androidx.media3.common.MediaMetadata.Builder()
                .setTitle(title)
                .setSubtitle(artist)
                .setArtist(artist)
                .setArtworkUri(artworkUrl?.toUri())
                .setMediaType(MEDIA_TYPE_MUSIC)
                .build(),
        )
        .build()
}
