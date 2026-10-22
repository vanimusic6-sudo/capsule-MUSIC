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
    ): List<ExperimentalCatalog.Track> {
        if (maximum <= 0) return emptyList()
        val candidateGroups = ExperimentalCatalog.Source.entries.associateWith { source ->
            tracks.asSequence()
                .filter { it.source == source }
                .filter { it.title.isNotBlank() && it.artist.isNotBlank() }
                .filter { it.durationSeconds in 0..1200 }
                .distinctBy { it.mediaId }
                .toList()
                .shuffled(Random(dayKey.toInt() xor (source.ordinal * 97_531)))
        }
        val quotas = mapOf(
            ExperimentalCatalog.Source.AUDIUS to (maximum * 0.45).toInt().coerceAtLeast(1),
            ExperimentalCatalog.Source.ARCHIVE to (maximum * 0.40).toInt().coerceAtLeast(1),
            ExperimentalCatalog.Source.MIRLO to (maximum * 0.15).toInt().coerceAtLeast(1),
        )
        val counts = mutableMapOf<ExperimentalCatalog.Source, Int>()
        val artists = mutableMapOf<String, Int>()
        val titles = mutableSetOf<String>()
        val usedIds = mutableSetOf<String>()
        val result = mutableListOf<ExperimentalCatalog.Track>()

        fun append(track: ExperimentalCatalog.Track): Boolean {
            val artistKey = track.artist.trim().lowercase()
            val songKey = artistKey + ":" + track.title.trim().lowercase()
            if ((artists[artistKey] ?: 0) >= 2 ||
                songKey in titles || track.mediaId in usedIds) return false
            artists[artistKey] = (artists[artistKey] ?: 0) + 1
            counts[track.source] = (counts[track.source] ?: 0) + 1
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
                        if (append(track)) { changed = true; break }
                    }
                    index[source] = cursor
                }
            }
        }
        return result
    }
}
