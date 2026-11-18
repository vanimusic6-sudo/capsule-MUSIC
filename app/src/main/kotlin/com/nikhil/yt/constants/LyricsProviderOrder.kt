/**
 * Capsule MUSIC — ordered, distinct lyrics sources.
 * Licensed under GPL-3.0.
 */
package com.nikhil.yt.constants

/** One source per independent catalog; legacy overlapping sources are dropped on upgrade. */
object LyricsProviderOrder {
    const val SIMPMUSIC = "SIMPMUSIC"              // YouTube ID / community
    const val PAXSENIX_APPLE_MUSIC = "PAXSENIX_APPLE_MUSIC" // Apple
    const val BETTER_LYRICS_PORTATO = "BETTER_LYRICS_PORTATO" // QQ / Portato
    const val LYRICS_PLUS = "LYRICS_PLUS"          // YouLy+ community
    const val LRCLIB = "LRCLIB"                    // Community LRC
    const val KUGOU = "KUGOU"                     // KuGou line-synced catalog
    const val YOUTUBE = "YOUTUBE"                  // Description fallback

    val supportedProviders = listOf(
        SIMPMUSIC,
        PAXSENIX_APPLE_MUSIC,
        BETTER_LYRICS_PORTATO,
        LYRICS_PLUS,
        LRCLIB,
        KUGOU,
        YOUTUBE,
    )

    private val supportedSet = supportedProviders.toSet()
    private val oldDefaults = listOf(
        listOf("PAXSENIX_APPLE_MUSIC", "BETTER_LYRICS", "NETEASE", "LYRICS_PLUS", "YOUTUBE"),
        listOf("SIMPMUSIC", "PAXSENIX_APPLE_MUSIC", "BETTER_LYRICS_PORTATO",
            "BETTER_LYRICS", "LYRICS_PLUS", "NETEASE", "YOUTUBE"),
    )

    /**
     * Never silently reorder a custom order. Migrating an untouched older default is safe.
     * Removed provider IDs (including NetEase and overlapping Better TTML) never reappear.
     */
    fun resolve(raw: String?, legacyPreferred: String? = null): List<String> {
        val stored = raw.orEmpty().split(',')
            .flatMap { expandLegacy(it.trim().uppercase()) }
            .filter { it.isNotEmpty() }
            .distinct()
        if (stored in oldDefaults) return supportedProviders
        val parsed = stored.filter { it in supportedSet }
        val seed = parsed.ifEmpty {
            val legacy = expandLegacy(legacyPreferred.orEmpty().trim().uppercase()).firstOrNull()
            // Velune's historical default was LRCLIB. The old single-choice
            // preference is often present even when the user never chose it.
            // Do not silently promote that stale default above Capsule's new
            // providers; only an explicit reordered list may do that.
            listOfNotNull(legacy?.takeIf { it in supportedSet && it != LRCLIB })
        }
        return (seed + supportedProviders).distinct()
    }

    fun encode(order: List<String>): String = resolve(order.joinToString(",")).joinToString(",")

    private fun expandLegacy(value: String): List<String> =
        when (value) {
            "PAXSENIX" -> listOf(PAXSENIX_APPLE_MUSIC)
            else -> listOf(value)
        }
}
