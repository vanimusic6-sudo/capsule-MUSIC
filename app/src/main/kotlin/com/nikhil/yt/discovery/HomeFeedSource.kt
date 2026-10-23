package com.nikhil.yt.discovery

import com.nikhil.yt.R
import androidx.compose.ui.graphics.Color

/** Sources that can actually provide a Capsule Home feed today.
 * Jamendo, Wikimedia and Freesound are indexed through the same Openverse
 * API, not separate clients with independent developer credentials.
 */
enum class HomeFeedSource(val id: String, val label: String, val icon: Int, val accent: Color, val openverseId: String?) {
    YOUTUBE("youtube", "YouTube Music", R.drawable.ic_capsule_youtube_home, Color(0xFFE62117), null),
    AUDIUS("audius", "Audius", R.drawable.ic_capsule_audius, Color(0xFF7B39E8), null),
    JAMENDO("jamendo", "Jamendo · Openverse", R.drawable.ic_capsule_jamendo_home, Color(0xFFE88823), "jamendo"),
    WIKIMEDIA("wikimedia_audio", "Wikimedia Commons", R.drawable.ic_capsule_wikimedia_home, Color(0xFF367CC3), "wikimedia_audio"),
    FREESOUND("freesound", "Freesound · Openverse", R.drawable.ic_capsule_freesound_home, Color(0xFF54A445), "freesound"),
    ARCHIVE("archive", "Internet Archive · Netlabels", R.drawable.ic_capsule_archive_home, Color(0xFFB87346), null),
    CCMIXTER("ccmixter", "ccMixter · Creative Commons", R.drawable.ic_capsule_ccmixter_home, Color(0xFF18A995), null);

    companion object {
        const val DEFAULT = "youtube"
        fun parse(raw: String): Set<HomeFeedSource> {
            val names = raw.split(',').toSet()
            return entries.filterTo(linkedSetOf()) { it.id in names }
                .ifEmpty { linkedSetOf(YOUTUBE) }
        }
        fun encode(sources: Set<HomeFeedSource>): String =
            entries.filter { it in sources }.joinToString(",") { it.id }
    }
}
