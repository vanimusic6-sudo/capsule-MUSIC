/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */



package com.nikhil.yt.models

import androidx.compose.runtime.Immutable
import com.nikhil.yt.innertube.models.SongItem
import com.nikhil.yt.db.entities.Song
import com.nikhil.yt.db.entities.SongEntity
import com.nikhil.yt.ui.utils.resize
import java.io.Serializable
import java.time.LocalDateTime

@Immutable
data class MediaMetadata(
    val id: String,
    val title: String,
    val artists: List<Artist>,
    val duration: Int,
    val thumbnailUrl: String? = null,
    val album: Album? = null,
    val setVideoId: String? = null,
    val explicit: Boolean = false,
    val liked: Boolean = false,
    val likedDate: LocalDateTime? = null,
    val inLibrary: LocalDateTime? = null,
    /**
     * Canonical source page for non-YouTube providers.
     *
     * SoundCloud needs this because mediaId is an internal stable hash, not a
     * playable or resolvable URL. Keeping the permalink in persisted metadata
     * lets the player menu download the real track and lets persistent queues
     * re-resolve fresh signed CDN URLs after process death.
     */
    val sourceUrl: String? = null,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }

    data class Artist(
        val id: String?,
        val name: String,
        val thumbnailUrl: String? = null,
    ) : Serializable {
        companion object {
            private const val serialVersionUID = 1L
        }
    }

    data class Album(
        val id: String,
        val title: String,
    ) : Serializable {
        companion object {
            private const val serialVersionUID = 1L
        }
    }

    fun toSongEntity() =
        SongEntity(
            id = id,
            title = title,
            duration = duration,
            thumbnailUrl = thumbnailUrl,
            albumId = album?.id,
            albumName = album?.title,
            explicit = explicit,
            liked = liked,
            likedDate = likedDate,
            inLibrary = inLibrary,
        )
}


fun mergeArtistCredits(
    primary: List<MediaMetadata.Artist>,
    secondary: List<MediaMetadata.Artist>,
): List<MediaMetadata.Artist> {
    val merged = mutableListOf<MediaMetadata.Artist>()

    fun addOrEnrich(candidate: MediaMetadata.Artist) {
        val name = candidate.name.trim()
        if (name.isEmpty()) return

        val index =
            merged.indexOfFirst { current ->
                (candidate.id != null && current.id == candidate.id) ||
                    current.name.equals(name, ignoreCase = true)
            }

        if (index < 0) {
            merged += candidate.copy(name = name)
        } else {
            val current = merged[index]
            merged[index] =
                current.copy(
                    id = current.id ?: candidate.id,
                    thumbnailUrl = current.thumbnailUrl ?: candidate.thumbnailUrl,
                )
        }
    }

    primary.forEach(::addOrEnrich)
    secondary.forEach(::addOrEnrich)
    return merged
}

fun Song.toMediaMetadata() =
    MediaMetadata(
        id = song.id,
        title = song.title,
        artists =
        artists.map {
            MediaMetadata.Artist(
                // Generated LA ids are persistence keys for unresolved remote credits, not
                // navigable YouTube artist pages. Real local artists remain navigable locally.
                id = it.id.takeUnless { id -> id.startsWith("LA") && !it.isLocal },
                name = it.name,
                thumbnailUrl = it.thumbnailUrl,
            )
        },
        duration = song.duration,
        thumbnailUrl = song.thumbnailUrl,
        album =
        album?.let {
            MediaMetadata.Album(
                id = it.id,
                title = it.title,
            )
        } ?: song.albumId?.let { albumId ->
            MediaMetadata.Album(
                id = albumId,
                title = song.albumName.orEmpty(),
            )
        },
    )

fun SongItem.toMediaMetadata() =
    MediaMetadata(
        id = id,
        title = title,
        artists =
        artists.map {
            MediaMetadata.Artist(
                id = it.id,
                name = it.name,
                thumbnailUrl = null,
            )
        },
        duration = duration ?: -1,
        thumbnailUrl = thumbnail.resize(544, 544),
        album =
        album?.let {
            MediaMetadata.Album(
                id = it.id,
                title = it.name,
            )
        },
        explicit = explicit,
        setVideoId = setVideoId
    )
