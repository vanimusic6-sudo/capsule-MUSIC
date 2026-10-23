package com.nikhil.yt.discovery

import kotlin.random.Random

/**
 * Pure recommendation ranking. Rotates daily, limits artists and prevents a single
 * provider from flooding an experimental shelf. No ML, tracking IDs or heavy scoring.
 */
internal object ExperimentalRanking {
    fun select(
        tracks: List<ExperimentalCatalog.Track>,
        dayKey: Long,
        maximum: Int = 18,
        tasteArtists: List<String> = emptyList(),
    ): List<ExperimentalCatalog.Track> {
        if (maximum <= 0) return emptyList()
        val taste = tasteArtists.asSequence().map { it.trim().lowercase() }
            .filter { it.isNotBlank() }.toSet()
        val candidateGroups = ExperimentalCatalog.Source.entries.associateWith { source ->
            tracks.asSequence()
                .filter { it.source == source }
                .filter { it.title.isNotBlank() && it.artist.isNotBlank() }
                .filter { it.durationSeconds in 0..1200 }
                .filter { it.playable }
                .filter {
                    it.audioCategory.isNullOrBlank() ||
                        it.audioCategory.equals("music", true)
                }
                .filter {
                    val title = it.title.lowercase()
                    listOf("pronunciation", "sound effect", "test tone", "telephone",
                        "alarm clock", "ringtone", "language lesson").none(title::contains) &&
                        !title.startsWith("ll-")
                }
                .distinctBy { it.mediaId }
                .toList()
                // Shuffle ties per UTC day, then rank by actual metadata signals.
                // Openverse has no global listener-rating metric.
                .shuffled(Random(dayKey.toInt() xor (source.ordinal * 97_531)))
                .sortedByDescending { track ->
                    var score = 0
                    if (track.durationSeconds in 110..420) score += 3
                    if (!track.artwork.isNullOrBlank()) score += 1
                    if (!track.genre.isNullOrBlank()) score += 2
                    if (track.audioCategory.equals("music", true)) score += 3
                    if (track.license != null) score += 1
                    if (track.artist.trim().lowercase() in taste) score += 8
                    // Only Audius provides actual engagement; Openverse does not.
                    // Capped tiers avoid reducing discovery to a popularity chart.
                    if (track.source == ExperimentalCatalog.Source.AUDIUS) {
                        score += when {
                            track.favoriteCount >= 1_000 || track.repostCount >= 500 -> 7
                            track.favoriteCount >= 100 || track.repostCount >= 50 -> 5
                            track.favoriteCount >= 15 || track.repostCount >= 8 -> 3
                            else -> 0
                        }
                        score += when {
                            track.playCount >= 100_000 -> 3
                            track.playCount >= 10_000 -> 2
                            track.playCount >= 1_000 -> 1
                            else -> 0
                        }
                    }
                    if (track.catalogProvider == "freesound" &&
                        track.durationSeconds < 75) score -= 3
                    score
                }
        }
        val quotas = mapOf(
            ExperimentalCatalog.Source.AUDIUS to (maximum * 0.40).toInt().coerceAtLeast(1),
            ExperimentalCatalog.Source.OPENVERSE to (maximum * 0.30).toInt().coerceAtLeast(1),
            ExperimentalCatalog.Source.ARCHIVE to (maximum * 0.30).toInt().coerceAtLeast(1),
        )
        val counts = mutableMapOf<ExperimentalCatalog.Source, Int>()
        val artists = mutableMapOf<String, Int>()
        val providerCounts = mutableMapOf<String, Int>()
        val upstreamCount = candidateGroups[ExperimentalCatalog.Source.OPENVERSE]
            .orEmpty().mapNotNull { it.catalogProvider }.distinct().size
        val sourceProviderCap = if (upstreamCount > 1) (maximum / (upstreamCount + 1) + 2)
            .coerceAtLeast(3) else maximum
        val titles = mutableSetOf<String>()
        val usedIds = mutableSetOf<String>()
        val result = mutableListOf<ExperimentalCatalog.Track>()

        fun append(track: ExperimentalCatalog.Track, respectProviderCap: Boolean): Boolean {
            val artistKey = track.artist.trim().lowercase()
            val songKey = artistKey + ":" + track.title.trim().lowercase()
            val provider = track.catalogProvider ?: track.source.name
            if ((artists[artistKey] ?: 0) >= 2 ||
                songKey in titles || track.mediaId in usedIds ||
                (respectProviderCap && track.source == ExperimentalCatalog.Source.OPENVERSE &&
                    (providerCounts[provider] ?: 0) >= sourceProviderCap)) return false
            artists[artistKey] = (artists[artistKey] ?: 0) + 1
            counts[track.source] = (counts[track.source] ?: 0) + 1
            providerCounts[provider] = (providerCounts[provider] ?: 0) + 1
            titles.add(songKey)
            usedIds.add(track.mediaId)
            result.add(track)
            return true
        }

        // First pass respects source quotas. When a source has no available tracks,
        // the second pass fills gaps with other providers rather than an empty shelf.
        for (pass in 0..1) {
            val index = mutableMapOf<ExperimentalCatalog.Source, Int>()
            var changed = true
            while (result.size < maximum && changed) {
                changed = false
                for (source in ExperimentalCatalog.Source.entries) {
                    if (result.size >= maximum) break
                    if (pass == 0 && (counts[source] ?: 0) >= (quotas[source] ?: 0)) continue
                    val pool = candidateGroups[source].orEmpty()
                    var cursor = index[source] ?: 0
                    while (cursor < pool.size) {
                        val track = pool[cursor++]
                        if (append(track, respectProviderCap = pass == 0)) { changed = true; break }
                    }
                    index[source] = cursor
                }
            }
        }
        return result
    }
}
