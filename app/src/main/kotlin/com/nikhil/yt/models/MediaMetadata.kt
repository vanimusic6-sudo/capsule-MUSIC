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
     * External catalog entries may use internal hashes rather than a
     * playable URL. Keeping the source page in metadata
     * lets the player expose source attribution without guessing IDs, while the queue
     * can safely avoid reusing short-lived third-party URLs.
     */
    val sourceUrl: String? = null,
    /** Explicit playback origin; normal library songs remain AUDIO. */
    val isOriginalVideo: Boolean = false,
    val unavailable: Boolean = false,
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


private val artistCreditJoiners =
    setOf(
        "•", "·", ",", "&", "/", ";", "|",
        "feat.", "ft.", "featuring",
        "и", "and",
    )

fun String.isArtistCreditJoiner(): Boolean {
    val normalized = trim().lowercase()
    if (normalized in artistCreditJoiners) return true
    return normalized.isNotEmpty() &&
        normalized.all {
            it == '•' || it == '·' || it == ',' || it == '&' ||
                it == '/' || it == ';' || it == '|'
        }
}

fun mergeArtistCredits(
    primary: List<MediaMetadata.Artist>,
    secondary: List<MediaMetadata.Artist>,
): List<MediaMetadata.Artist> {
    val merged = mutableListOf<MediaMetadata.Artist>()

    fun addOrEnrich(candidate: MediaMetadata.Artist) {
        val name = candidate.name.trim()
        if (name.isEmpty() || name.isArtistCreditJoiner()) return

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

fun List<MediaMetadata.Artist>.sanitizedArtistCredits(): List<MediaMetadata.Artist> =
    mergeArtistCredits(this, emptyList())

fun List<MediaMetadata.Artist>.artistCreditLine(): String =
    sanitizedArtistCredits().joinToString(separator = ", ") { it.name }

fun Song.toMediaMetadata() =
    MediaMetadata(
        id = song.id,
        title = song.title,
        artists =
        artists
            .filterNot { it.name.isArtistCreditJoiner() }
            .map {
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
        artists
            .map {
                MediaMetadata.Artist(
                    id = it.id,
                    name = it.name,
                    thumbnailUrl = null,
                )
            }
            .sanitizedArtistCredits(),
        duration = duration ?: -1,
        // Video posters should keep their source's native crop/aspect.
        // Resizing a 16:9 thumbnail to 544x544 silently swaps it to a
        // different square-cropped YouTube image after the initial frame.
        thumbnailUrl = if (isOriginalVideo) thumbnail else thumbnail.resize(544, 544),
        album =
        album?.let {
            MediaMetadata.Album(
                id = it.id,
                title = it.name,
            )
        },
        explicit = explicit,
        setVideoId = setVideoId,
        isOriginalVideo = isOriginalVideo,
        unavailable = unavailable,
    )
