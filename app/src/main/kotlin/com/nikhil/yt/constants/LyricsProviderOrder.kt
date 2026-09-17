/**
 * Capsule MUSIC
 * The order lyrics providers are tried in.
 * GPL-3.0
 */

package com.nikhil.yt.constants

/**
 * A ranking, not a favourite.
 *
 * It used to be one "preferred" provider moved to the front of a fixed list, which answers only the
 * first question anyone has. Which source is tried second matters just as much: they disagree about
 * timing, about which songs they have at all, and about whether what they return is synced. This is
 * the same shape as the audio client order, and for the same reason.
 *
 * Stored as a comma-separated list of ids. Unknown ids are dropped and missing ones are appended in
 * their default order, so a provider added or removed in a later version cannot leave somebody with
 * a broken or truncated list.
 */
object LyricsProviderOrder {
    const val LRCLIB = "LRCLIB"
    const val BETTER_LYRICS = "BETTER_LYRICS"
    const val YOUTUBE_SUBTITLE = "YOUTUBE_SUBTITLE"
    const val YOUTUBE = "YOUTUBE"

    /** Default order, best first. LrcLib leads because its lyrics are synced most of the time. */
    val supportedProviders: List<String> =
        listOf(
            LRCLIB,
            BETTER_LYRICS,
            YOUTUBE_SUBTITLE,
            YOUTUBE,
        )

    private val supportedSet = supportedProviders.toSet()

    /**
     * The stored order, repaired.
     *
     * [legacyPreferred] is the old single-choice setting: when nothing has been ordered yet it
     * decides which provider leads, so upgrading keeps the choice somebody already made instead of
     * silently resetting it.
     */
    fun resolve(
        raw: String?,
        legacyPreferred: String? = null,
    ): List<String> {
        val parsed =
            raw
                .orEmpty()
                .split(',')
                .map(::normalize)
                .filter { it in supportedSet }
                .distinct()

        val seed =
            parsed.ifEmpty {
                val preferred = normalize(legacyPreferred.orEmpty()).takeIf { it in supportedSet }
                listOfNotNull(preferred)
            }

        return (seed + supportedProviders).distinct()
    }

    fun encode(order: List<String>): String = resolve(order.joinToString(",")).joinToString(",")

    private fun normalize(value: String): String = value.trim().uppercase()
}
