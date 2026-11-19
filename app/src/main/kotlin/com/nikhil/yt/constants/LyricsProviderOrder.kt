/**
 * Capsule MUSIC — five stable, non-overlapping lyrics catalogs.
 * Licensed under GPL-3.0.
 */
package com.nikhil.yt.constants

object LyricsProviderOrder {
    const val BETTER_LYRICS_PORTATO = "BETTER_LYRICS_PORTATO"
    const val SIMPMUSIC = "SIMPMUSIC"
    const val LYRICS_PLUS = "LYRICS_PLUS"
    const val LRCLIB = "LRCLIB"
    const val YOUTUBE = "YOUTUBE"

    /** Official fallback is last, unless the user explicitly reorders it. */
    val supportedProviders: List<String> = listOf(
        BETTER_LYRICS_PORTATO,
        SIMPMUSIC,
        LYRICS_PLUS,
        LRCLIB,
        YOUTUBE,
    )
    private val supportedSet = supportedProviders.toSet()

    /** Previously shipped automatic defaults, including retired catalogs. */
    private val oldDefaults = listOf(
        listOf("PAXSENIX_APPLE_MUSIC", "BETTER_LYRICS", "NETEASE", "LYRICS_PLUS", "YOUTUBE"),
        listOf("SIMPMUSIC", "PAXSENIX_APPLE_MUSIC", "BETTER_LYRICS_PORTATO",
            "BETTER_LYRICS", "LYRICS_PLUS", "NETEASE", "YOUTUBE"),
        listOf("SIMPMUSIC", "PAXSENIX_APPLE_MUSIC", "BETTER_LYRICS_PORTATO",
            "LYRICS_PLUS", "LRCLIB", "KUGOU", "YOUTUBE"),
    )

    /** Retire removed IDs without reinstating their network clients.
     *  Preserve deliberate custom ordering for the remaining five providers.
     */
    fun resolve(raw: String?, legacyPreferred: String? = null): List<String> {
        val stored = raw.orEmpty().split(',')
            .map { it.trim().uppercase() }
            .filter(String::isNotBlank)
            .distinct()
        if (stored in oldDefaults) return supportedProviders
        val parsed = stored.filter { it in supportedSet }
        val seed = parsed.ifEmpty {
            // Velune used LRCLIB as the implicit legacy preference. An
            // untouched setting must not override the new stable ordering.
            listOfNotNull(legacyPreferred?.trim()?.uppercase()
                ?.takeIf { it in supportedSet && it != LRCLIB })
        }
        return (seed + supportedProviders).distinct()
    }

    fun encode(order: List<String>): String =
        resolve(order.joinToString(",")).joinToString(",")
}
