package com.nikhil.yt.soundcloud

import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata.MEDIA_TYPE_MUSIC
import androidx.media3.common.MimeTypes
import com.nikhil.yt.innertube.soundcloud.SoundCloudNewPipe
import com.nikhil.yt.models.MediaMetadata
import java.security.MessageDigest

/** Never interpret a SoundCloud identity as an 11-character YouTube video id. */
internal const val SOUNDCLOUD_MEDIA_ID_PREFIX = "soundcloud:"

internal fun soundCloudMediaId(url: String): String =
    SOUNDCLOUD_MEDIA_ID_PREFIX + MessageDigest.getInstance("SHA-256")
        .digest(url.toByteArray(Charsets.UTF_8))
        .take(12)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

internal fun SoundCloudCatalog.Track.toSoundCloudMediaItem(
    stream: SoundCloudNewPipe.Stream,
): MediaItem {
    val id = soundCloudMediaId(permalink)
    val metadata = MediaMetadata(
        id = id,
        title = title,
        artists = listOf(MediaMetadata.Artist(id = uploaderUrl, name = artist)),
        duration = durationSeconds.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
        thumbnailUrl = artworkUrl,
    )
    return MediaItem.Builder()
        .setMediaId(id)
        .setUri(stream.url)
        // A signed SoundCloud URL is transient; do not write it into YouTube's cache.
        .apply { if (stream.isHls) setMimeType(MimeTypes.APPLICATION_M3U8) }
        .setTag(metadata)
        .setMediaMetadata(
            androidx.media3.common.MediaMetadata.Builder()
                .setTitle(title)
                .setSubtitle(artist)
                .setArtist(artist)
                .setArtworkUri(artworkUrl?.toUri())
                .setMediaType(MEDIA_TYPE_MUSIC)
                .build()
        )
        .build()
}
