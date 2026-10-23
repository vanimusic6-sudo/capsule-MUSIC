package com.nikhil.yt.discovery

import com.nikhil.yt.R

/** Sources that can actually provide a Capsule Home feed today.
 * Jamendo, Wikimedia and Freesound are indexed through the same Openverse
 * API, not separate clients with independent developer credentials.
 */
enum class HomeFeedSource(val id: String, val label: String, val icon: Int, val openverseId: String?) {
    YOUTUBE("youtube", "YouTube Music", R.drawable.ic_capsule_youtube_home, null),
    AUDIUS("audius", "Audius", R.drawable.ic_capsule_audius, null),
    JAMENDO("jamendo", "Jamendo · Openverse", R.drawable.ic_capsule_jamendo_home, "jamendo"),
    WIKIMEDIA("wikimedia_audio", "Wikimedia Commons · Openverse", R.drawable.ic_capsule_wikimedia_home, "wikimedia_audio"),
    FREESOUND("freesound", "Freesound · Openverse", R.drawable.ic_capsule_freesound_home, "freesound");

    companion object {
        const val DEFAULT = "youtube,audius,jamendo,wikimedia_audio,freesound"
        fun parse(raw: String): Set<HomeFeedSource> {
            val names = raw.split(',').toSet()
            return entries.filterTo(linkedSetOf()) { it.id in names }
                .ifEmpty { linkedSetOf(YOUTUBE) }
        }
        fun encode(sources: Set<HomeFeedSource>): String =
            entries.filter { it in sources }.joinToString(",") { it.id }
    }
}
