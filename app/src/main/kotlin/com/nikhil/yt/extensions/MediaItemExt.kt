/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */



package com.nikhil.yt.extensions

import android.os.Bundle
import android.os.Build
import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import com.nikhil.yt.discovery.OfflineAudioQueue
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata.MEDIA_TYPE_MUSIC
import com.nikhil.yt.innertube.models.SongItem
import com.nikhil.yt.db.entities.Song
import com.nikhil.yt.models.MediaMetadata
import com.nikhil.yt.models.artistCreditLine
import com.nikhil.yt.models.sanitizedArtistCredits
import com.nikhil.yt.models.toMediaMetadata

const val ExtraIsMusicVideo = "com.nikhil.yt.extra.IS_MUSIC_VIDEO"

val MediaItem.metadata: MediaMetadata?
    get() =
        (localConfiguration?.tag as? MediaMetadata)?.let { metadata ->
            metadata.copy(artists = metadata.artists.sanitizedArtistCredits())
        }

fun Song.toMediaItem(): MediaItem {
    val metadata = toMediaMetadata()
    val offlineId = song.id.takeIf { it.startsWith(OfflineAudioQueue.PREFIX) }
        ?.removePrefix(OfflineAudioQueue.PREFIX)?.toLongOrNull()
    val playbackUri = when {
        offlineId != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
            ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, offlineId).toString()
        song.id.startsWith("capsule-open:audius:") ->
            "https://api.audius.co/v1/tracks/" +
                Uri.encode(song.id.removePrefix("capsule-open:audius:")) + "/stream"
        else -> song.id
    }
    return MediaItem
        .Builder()
        .setMediaId(song.id)
        .setUri(playbackUri)
        .setCustomCacheKey(song.id)
        .setTag(metadata)
        .setMediaMetadata(
            androidx.media3.common.MediaMetadata
                .Builder()
                .setTitle(song.title)
                .setSubtitle(metadata.artists.artistCreditLine())
                .setArtist(metadata.artists.artistCreditLine())
                .setArtworkUri(song.thumbnailUrl?.toUri())
                .setAlbumTitle(song.albumName)
                .setMediaType(MEDIA_TYPE_MUSIC)
                .setExtras(Bundle().apply { putBoolean(ExtraIsMusicVideo, false) })
                .build(),
        ).build()
}

fun SongItem.toMediaItem(): MediaItem {
    val metadata = toMediaMetadata()
    return MediaItem
        .Builder()
        .setMediaId(id)
        .setUri(id)
        .setCustomCacheKey(id)
        .setTag(metadata)
        .setMediaMetadata(
            androidx.media3.common.MediaMetadata
                .Builder()
                .setTitle(title)
                .setSubtitle(metadata.artists.artistCreditLine())
                .setArtist(metadata.artists.artistCreditLine())
                .setArtworkUri(thumbnail.toUri())
                .setAlbumTitle(album?.name)
                .setMediaType(MEDIA_TYPE_MUSIC)
                .setExtras(Bundle().apply { putBoolean(ExtraIsMusicVideo, isOriginalVideo) })
                .build(),
        ).build()
}

fun MediaMetadata.toMediaItem(): MediaItem {
    val metadata = copy(artists = artists.sanitizedArtistCredits())
    val offlineId = id.takeIf { it.startsWith(OfflineAudioQueue.PREFIX) }
        ?.removePrefix(OfflineAudioQueue.PREFIX)?.toLongOrNull()
    val playbackUri = when {
        offlineId != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
            ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, offlineId).toString()
        id.startsWith("capsule-open:audius:") ->
            "https://api.audius.co/v1/tracks/" +
                Uri.encode(id.removePrefix("capsule-open:audius:")) + "/stream"
        else -> id
    }
    return MediaItem
        .Builder()
        .setMediaId(id)
        .setUri(playbackUri)
        .setCustomCacheKey(id)
        .setTag(metadata)
        .setMediaMetadata(
            androidx.media3.common.MediaMetadata
                .Builder()
                .setTitle(title)
                .setSubtitle(metadata.artists.artistCreditLine())
                .setArtist(metadata.artists.artistCreditLine())
                .setArtworkUri(thumbnailUrl?.toUri())
                .setAlbumTitle(album?.title)
                .setMediaType(MEDIA_TYPE_MUSIC)
                .setExtras(Bundle().apply { putBoolean(ExtraIsMusicVideo, metadata.isOriginalVideo) })
                .build(),
        ).build()
}

