package com.nikhil.yt.soundcloud

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata.MEDIA_TYPE_MUSIC
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.offline.Download
import com.nikhil.yt.innertube.soundcloud.SoundCloudNewPipe
import com.nikhil.yt.models.MediaMetadata
import java.io.File
import java.security.MessageDigest

internal const val SOUNDCLOUD_MEDIA_ID_PREFIX = "soundcloud:"
private const val SOUNDCLOUD_STREAM_CACHE_KEY_PREFIX = "soundcloud-stream:"

internal fun soundCloudMediaId(url: String): String =
    SOUNDCLOUD_MEDIA_ID_PREFIX + MessageDigest.getInstance("SHA-256")
        .digest(url.toByteArray(Charsets.UTF_8))
        .take(12)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

internal fun soundCloudStreamCacheKey(url: String): String =
    SOUNDCLOUD_STREAM_CACHE_KEY_PREFIX +
        soundCloudMediaId(url).removePrefix(SOUNDCLOUD_MEDIA_ID_PREFIX)


private const val SOUNDCLOUD_DOWNLOAD_DIRECTORY = "soundcloud_downloads"

internal fun soundCloudDownloadFile(
    context: Context,
    mediaId: String,
): File {
    val stableName =
        mediaId.removePrefix(SOUNDCLOUD_MEDIA_ID_PREFIX)
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
    return context.filesDir
        .resolve(SOUNDCLOUD_DOWNLOAD_DIRECTORY)
        .resolve("$stableName.audio")
}

internal fun soundCloudDownloadPartFile(
    context: Context,
    mediaId: String,
): File = File(soundCloudDownloadFile(context, mediaId).absolutePath + ".part")

internal fun hasSoundCloudDownload(
    context: Context,
    mediaId: String,
): Boolean = soundCloudDownloadFile(context, mediaId).let { it.isFile && it.length() > 0L }

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
        // Streaming and offline download bytes must never share a cache key.
        // A currently playing SoundCloud track used to lock the exact cache
        // entry the downloader was trying to fill.
        customCacheKey = if (stream.isHls) null else soundCloudStreamCacheKey(permalink),
    )

internal fun SoundCloudCatalog.Track.toDownloadedSoundCloudMediaItem(
    download: Download,
): MediaItem {
    val mediaId = soundCloudMediaId(permalink)
    require(download.request.id == mediaId)
    val file = soundCloudDownloadFile(com.nikhil.yt.App.instance, mediaId)
    require(file.isFile && file.length() > 0L) {
        "Completed SoundCloud download file is missing"
    }
    return buildSoundCloudMediaItem(
        uri = Uri.fromFile(file).toString(),
        isHls = false,
        customCacheKey = null,
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
