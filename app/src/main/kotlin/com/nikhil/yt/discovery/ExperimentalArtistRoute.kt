package com.nikhil.yt.discovery

import android.net.Uri
import com.nikhil.yt.models.MediaMetadata

/**
 * Internal artist pages for external sources, never passed to YouTube artist routes.
 * Query parameters are encoded because Archive creator names contain punctuation.
 */
object ExperimentalArtistRoute {
    fun forTrack(track: ExperimentalCatalog.Track): String = build(
        track.source, track.artist, track.artistId, track.artistPage,
    )

    fun forMetadata(metadata: MediaMetadata): String? {
        if (!metadata.id.startsWith(ExperimentalCatalog.EXPERIMENTAL_MEDIA_PREFIX)) return null
        val provider = metadata.id.removePrefix(ExperimentalCatalog.EXPERIMENTAL_MEDIA_PREFIX)
            .substringBefore(':').uppercase()
        val source = ExperimentalCatalog.Source.entries.firstOrNull { it.name == provider }
            ?: return null
        val artist = metadata.artists.firstOrNull() ?: return null
        return build(source, artist.name, artist.id, null)
    }

    private fun build(
        source: ExperimentalCatalog.Source,
        name: String,
        id: String?,
        url: String?,
    ): String = "discovery/artist?source=${source.name}" +
        "&name=${Uri.encode(name)}" +
        "&id=${Uri.encode(id.orEmpty())}" +
        "&url=${Uri.encode(url.orEmpty())}"
}
