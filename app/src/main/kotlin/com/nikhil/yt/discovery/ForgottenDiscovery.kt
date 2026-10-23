package com.nikhil.yt.discovery

import kotlin.random.Random

/**
 * Heuristic curation of scarcely surfaced music from Openverse's upstream
 * catalogs. Does not assert verified exclusivity, age, rarity or popularity:
 * none of those can be established reliably from Openverse metadata.
 */
internal object ForgottenDiscovery {
    private val excludedPhrases = listOf(
        "pronunciation", "speaking", "text-to-speech", "language lesson",
        "field noise", "ringtone", "sound effect", "test tone",
        "alarm clock", "telephone", "engine noise", "applause",
    )
    private val musicHints = listOf(
        "music", "song", "folk", "traditional", "instrumental", "jazz",
        "blues", "piano", "violin", "guitar", "orchestra", "symphony",
        "choir", "melody", "recording", "performance", "banjo", "flute",
        "phonograph", "vintage", "ethnomusic", "archive", "concert",
    )

    fun select(
        candidates: List<ExperimentalCatalog.Track>,
        dayKey: Long,
        maximum: Int = 12,
    ): List<ExperimentalCatalog.Track> {
        if (maximum <= 0) return emptyList()
        val rng = Random(dayKey.toInt() xor 0x27c7_3f91)
        val ranked = candidates.asSequence()
            .filter { it.source == ExperimentalCatalog.Source.OPENVERSE }
            .filter { it.catalogProvider in setOf("wikimedia_audio", "freesound") }
            .filter { it.playable && it.license != null }
            .filter { it.durationSeconds in 45..1200 }
            .filter { it.title.isNotBlank() && it.artist.isNotBlank() }
            .filter { track ->
                val category = track.audioCategory.orEmpty()
                category.isBlank() || category.equals("music", ignoreCase = true)
            }
            .filter { track ->
                val text = (track.title + " " + track.genre.orEmpty()).lowercase()
                excludedPhrases.none { it in text } &&
                    !track.title.startsWith("LL-", ignoreCase = true)
            }
            .distinctBy { it.mediaId }
            .map { track ->
                val context = (track.title + " " + track.genre.orEmpty()).lowercase()
                val score = musicHints.count { it in context } +
                    (if (track.audioCategory == "music") 3 else 0) +
                    (if (track.catalogProvider == "wikimedia_audio") 1 else 0)
                score to track
            }
            // Avoid a shelf full of ambiguous unclassified sound clips.
            .filter { (score, track) -> score > 0 || track.audioCategory == "music" }
            .groupBy { it.first }
            .toSortedMap(compareByDescending { it })
            .values.flatMap { group -> group.shuffled(rng).map { it.second } }

        val seen = mutableSetOf<String>()
        val artistCount = mutableMapOf<String, Int>()
        return ranked.filter { track ->
            val artist = track.artist.trim().lowercase()
            val key = artist + ":" + track.title.trim().lowercase()
            if (key in seen || (artistCount[artist] ?: 0) >= 2) return@filter false
            seen.add(key)
            artistCount[artist] = (artistCount[artist] ?: 0) + 1
            true
        }.take(maximum)
    }
}
