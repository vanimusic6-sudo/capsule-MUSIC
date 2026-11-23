/**
 * Capsule MUSIC — six independent, user-ordered lyrics sources.
 * Licensed under GPL-3.0.
 */
package com.nikhil.yt.constants

object LyricsProviderOrder {
    const val BETTER_LYRICS_PORTATO = "BETTER_LYRICS_PORTATO"
    const val BETTER_LYRICS = "BETTER_LYRICS"
    const val LRCLIB = "LRCLIB"
    const val YOUTUBE_SYNCED = "YOUTUBE_SYNCED"
    const val LYPRIO = "LYPRIO"
    const val YOUTUBE = "YOUTUBE"

    val supportedProviders = listOf(
        BETTER_LYRICS_PORTATO, BETTER_LYRICS, LRCLIB,
        YOUTUBE_SYNCED, LYPRIO, YOUTUBE,
    )
    private val supportedSet = supportedProviders.toSet()
    private val retired = setOf(
        "PAXSENIX_APPLE_MUSIC", "SIMPMUSIC", "LYRICS_PLUS", "NETEASE", "KUGOU",
    )

    /** Retired saved lists migrate to the requested six-provider default. */
    fun resolve(raw: String?, legacyPreferred: String? = null): List<String> {
        val stored = raw.orEmpty().split(',')
            .map { it.trim().uppercase(java.util.Locale.US) }
            .filter(String::isNotBlank)
            .distinct()
        if (stored.isEmpty() || stored.any { it in retired }) return supportedProviders
        val parsed = stored.filter { it in supportedSet }
        val seed = parsed.ifEmpty {
            listOfNotNull(legacyPreferred?.trim()?.uppercase(java.util.Locale.US)
                ?.takeIf { it in supportedSet && it != LRCLIB })
        }
        return (seed + supportedProviders).distinct()
    }

    fun encode(order: List<String>): String =
        resolve(order.joinToString(",")).joinToString(",")
}
