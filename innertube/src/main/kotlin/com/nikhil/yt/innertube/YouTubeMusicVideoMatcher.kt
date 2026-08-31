/*
 * Capsule MUSIC
 * Pure official-video scoring logic extracted so it can be regression tested
 * without a network call or Android runtime.
 * GPL-3.0
 */
package com.nikhil.yt.innertube

import kotlin.math.abs

internal object YouTubeMusicVideoMatcher {
    const val MIN_MATCH_SCORE = 115
    const val STRONG_MATCH_SCORE = 225

    fun scoreCandidate(
        sourceTitle: String,
        sourceTitleNorm: String,
        sourceArtistNorms: List<String>,
        candidateTitle: String,
        secondaryText: String,
        sourceDurationSeconds: Int?,
    ): Int? {
        if (isRejectedVariant(sourceTitle, candidateTitle)) return null

        val candidateTitleNorm = normalizeTitle(candidateTitle)
        if (candidateTitleNorm.isBlank() || sourceTitleNorm.isBlank()) return null

        val secondaryNorm = normalizeText(secondaryText)
        val overlap = titleTokenOverlap(sourceTitleNorm, candidateTitleNorm)

        var score =
            when {
                candidateTitleNorm == sourceTitleNorm -> 150
                candidateTitleNorm.contains(sourceTitleNorm) ||
                    sourceTitleNorm.contains(candidateTitleNorm) -> 105
                overlap >= 0.78 -> 88
                overlap >= 0.62 -> 67
                overlap >= 0.50 -> 48
                else -> return null
            }

        if (sourceArtistNorms.isNotEmpty()) {
            val artistMatched =
                sourceArtistNorms.any { artist ->
                    artist.length >= 2 &&
                        (
                            containsWholePhrase(secondaryNorm, artist) ||
                                containsWholePhrase(candidateTitleNorm, artist) ||
                                secondaryNorm.contains(artist)
                        )
                }

            if (artistMatched) score += 65 else score -= 55
        }

        // MUSIC_VIDEO_TYPE_OMV is checked by the resolver before this helper.
        score += 55

        val rawTitleNorm = normalizeText(candidateTitle)
        if (
            rawTitleNorm.contains("official music video") ||
            rawTitleNorm.contains("official video")
        ) {
            score += 28
        }

        val candidateDuration = extractDurationSeconds(secondaryText)
        val sourceDuration = sourceDurationSeconds?.takeIf { it > 0 }

        if (sourceDuration != null && candidateDuration != null) {
            val delta = abs(sourceDuration - candidateDuration)
            score +=
                when {
                    delta <= 5 -> 42
                    delta <= 12 -> 34
                    delta <= 25 -> 22
                    delta <= 45 -> 10
                    delta <= 75 -> -8
                    else -> return null
                }
        }

        return score.takeIf { it >= MIN_MATCH_SCORE }
    }

    fun normalizeTitle(value: String): String =
        normalizeText(value)
            .replace(Regex("\\bofficial\\s+music\\s+video\\b"), " ")
            .replace(Regex("\\bofficial\\s+video\\b"), " ")
            .replace(Regex("\\bmusic\\s+video\\b"), " ")
            .replace(Regex("\\bofficial\\b"), " ")
            .replace(Regex("\\bremaster(?:ed)?(?:\\s+\\d{4})?\\b"), " ")
            .replace(Regex("\\balbum\\s+version\\b"), " ")
            .replace(Regex("\\bsingle\\s+version\\b"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    fun normalizeText(value: String): String =
        value
            .lowercase()
            .replace('&', ' ')
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    fun extractDurationSeconds(text: String): Int? {
        val match =
            Regex("""(?<!\d)(\d{1,2}):(\d{2})(?::(\d{2}))?(?!\d)""")
                .find(text)
                ?: return null

        val first = match.groupValues[1].toIntOrNull() ?: return null
        val second = match.groupValues[2].toIntOrNull() ?: return null
        val third =
            match.groupValues
                .getOrNull(3)
                ?.takeIf { it.isNotBlank() }
                ?.toIntOrNull()

        return if (third == null) {
            first * 60 + second
        } else {
            first * 3600 + second * 60 + third
        }
    }

    private fun isRejectedVariant(
        sourceTitle: String,
        candidateTitle: String,
    ): Boolean {
        val source = " ${normalizeText(sourceTitle)} "
        val candidate = " ${normalizeText(candidateTitle)} "

        val contextualRejects =
            listOf(
                " live ", " concert ", " performance ", " session ",
                " acoustic ", " cover ", " karaoke ", " lyric ", " lyrics ",
                " visualizer ", " animated video ", " dance video ",
                " dance practice ", " slowed ", " reverb ", " sped up ",
                " nightcore ", " remix ", " edit ", " fanmade ", " fan made ",
                " amv ", " reaction ", " interview ", " behind the scenes ",
                " making of ", " teaser ", " trailer ", " vertical video ",
                " shorts ", " challenge ",
            )

        if (
            contextualRejects.any { token ->
                candidate.contains(token) && !source.contains(token)
            }
        ) {
            return true
        }

        val alwaysReject =
            listOf(
                " official audio ",
                " audio only ",
                " topic audio ",
            )

        return alwaysReject.any { token -> candidate.contains(token) }
    }

    private fun titleTokenOverlap(left: String, right: String): Double {
        val a = left.split(' ').filter { it.length > 1 }.toSet()
        val b = right.split(' ').filter { it.length > 1 }.toSet()
        if (a.isEmpty() || b.isEmpty()) return 0.0
        return a.intersect(b).size.toDouble() / a.union(b).size.toDouble()
    }

    private fun containsWholePhrase(haystack: String, needle: String): Boolean =
        " $haystack ".contains(" $needle ") || haystack == needle
}
