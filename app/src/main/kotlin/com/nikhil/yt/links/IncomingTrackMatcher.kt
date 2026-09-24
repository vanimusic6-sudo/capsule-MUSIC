package com.nikhil.yt.links

import com.nikhil.yt.innertube.models.SongItem
import java.util.Locale

/** Only auto-play a uniquely identifiable recording, not an arbitrary first search hit. */
internal object IncomingTrackMatcher {
    fun uniqueExactMatch(
        title: String,
        artist: String?,
        results: List<SongItem>,
        allowExplicit: Boolean = true,
    ): SongItem? {
        val normalizedTitle = normalize(title)
        val normalizedArtist = normalize(artist.orEmpty())
        if (normalizedTitle.isBlank() || normalizedArtist.isBlank()) return null
        return results.asSequence()
            .filter { allowExplicit || !it.explicit }
            .filter { normalize(it.title) == normalizedTitle }
            .filter { it.artists.any { artistItem -> normalize(artistItem.name) == normalizedArtist } }
            .distinctBy { it.id }
            .take(2)
            .toList()
            .singleOrNull()
    }

    private fun normalize(value: String): String =
        value.lowercase(Locale.ROOT)
            .replace(Regex("""[\p{P}\p{S}\s]+"""), " ")
            .trim()
}
