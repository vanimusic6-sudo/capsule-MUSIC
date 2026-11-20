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
    // An artist-verified clip from the natural Videos query should not
    // launch another metadata search solely to chase a higher score.
    const val FIRST_QUERY_ACCEPT_SCORE = 170

    // Every Videos result previously rebuilt a dozen regular expressions and
    // variant lists. Cache the immutable matchers once: this path runs on
    // every candidate, including on small/thermally constrained phones.
    private val featuredCredit = Regex("""(?i)\s*[\[(]\s*(?:feat\.?|ft\.?|featuring)\s+[^\])]*[\])]""")
    private val unsupportedCharacters = Regex("""[^\p{L}\p{N}]+""")
    private val whitespace = Regex("""\s+""")
    private val durationPattern = Regex("""(?<!\d)(\d{1,2}):(\d{2})(?::(\d{2}))?(?!\d)""")
    private val titleCleanups = listOf(
        Regex("""\bofficial\s+music\s+video\b"""),
        Regex("""\bofficial\s+video\b"""),
        Regex("""\bmusic\s+video\b"""),
        Regex("""\bofficial\b"""),
        Regex("""\bremaster(?:ed)?(?:\s+\d{4})?\b"""),
        Regex("""\balbum\s+version\b"""),
        Regex("""\bsingle\s+version\b"""),
    )
    private val contextualRejects = listOf(
        " live ", " concert ", " performance ", " session ",
        " acoustic ", " cover ", " karaoke ",
        " animated video ", " dance video ",
        " dance practice ", " slowed ", " reverb ", " sped up ",
        " nightcore ", " remix ", " edit ", " fanmade ", " fan made ",
        " amv ", " reaction ", " interview ", " behind the scenes ",
        " making of ", " teaser ", " trailer ", " vertical video ",
        " shorts ", " challenge ",
    )
    private val alwaysReject = listOf(
        " lyric ", " lyrics ", " lyricvideo ", " lyricsvideo ",
        " lyric video ", " lyrics video ",
        " sing along ", " on screen lyrics ", " text only ",
        " text video ", " words on screen ", " subtitles ", " subtitled ",
        " текст песни ", " слова песни ", " видео с текстом ",
        " субтитры ", " караоке ", " visualizer ",
        " official audio ", " audio only ", " topic audio ",
    )

    fun needsSecondQuery(bestScore: Int?): Boolean =
        bestScore == null || bestScore < FIRST_QUERY_ACCEPT_SCORE

    fun scoreCandidate(
        sourceTitle: String,
        sourceTitleNorm: String,
        sourceArtistNorms: List<String>,
        candidateTitle: String,
        secondaryText: String,
        sourceDurationSeconds: Int?,
        sameOfficialVideo: Boolean = false,
        omvVerified: Boolean = true,
    ): Int? {
        // Playlist rows may omit the video type or carry incomplete artist/title
        // metadata. A search result explicitly marked OMV for the exact source ID
        // is already the canonical clip, not a competing title/artist match.
        if (sameOfficialVideo) return STRONG_MATCH_SCORE + 1
        if (isRejectedVariant(sourceTitle, candidateTitle)) return null
        // A generic title plus OMV classification still does not establish
        // song identity without any artist. The only safe missing-artist case
        // is an explicitly verified same-ID OMV, handled above.
        if (sourceArtistNorms.none { it.isNotBlank() }) return null

        val candidateTitleNorm = normalizeTitle(candidateTitle)
        if (candidateTitleNorm.isBlank() || sourceTitleNorm.isBlank()) return null

        val secondaryNorm = normalizeText(secondaryText)
        // Same words in a longer title are not the same recording. E.g.
        // "Another Artist - Different Song / Home" previously won for
        // "Home" because it ended with the requested word and mentioned an
        // artist elsewhere. Match the *whole* normalized recording title.
        val sameRecordingTitle = candidateTitleNorm == sourceTitleNorm ||
            sourceArtistNorms.any { artist ->
                candidateTitleNorm == "$artist $sourceTitleNorm"
            }
        if (!sameRecordingTitle) return null
        var score = if (candidateTitleNorm == sourceTitleNorm) 150 else 105

        if (sourceArtistNorms.isNotEmpty()) {
            val artistMatched =
                sourceArtistNorms.any { artist ->
                    artist.length >= 2 &&
                        (
                            containsWholePhrase(secondaryNorm, artist) ||
                                containsWholePhrase(candidateTitleNorm, artist)
                        )
                }

            // An OMV category label does not prove this is the correct artist.
            // Previously unrelated same-title OMVs could still exceed the
            // acceptance threshold, causing a wrong-channel "official" clip.
            if (!artistMatched) return null
            score += 65
        }

        // MUSIC_VIDEO_TYPE_OMV is checked by the resolver before this helper.
        if (omvVerified) score += 55

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

    // Title-side featured-artist credits belong to the metadata, not to the
    // recording name. A Music row such as "Song (feat. Guest)" must still match
    // the official clip titled "Artist - Song". Only remove explicitly
    // bracketed credits: never delete arbitrary title words or a remix suffix.
    fun normalizeTitle(value: String): String =
        whitespace.replace(
            titleCleanups.fold(normalizeText(featuredCredit.replace(value, " "))) { title, pattern ->
                pattern.replace(title, " ")
            },
            " ",
        ).trim()

    fun normalizeText(value: String): String =
        whitespace.replace(
            unsupportedCharacters.replace(value.lowercase().replace('&', ' '), " "),
            " ",
        ).trim()

    fun extractDurationSeconds(text: String): Int? {
        val match =
            durationPattern.find(text)
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

        if (
            contextualRejects.any { token ->
                candidate.contains(token) && !source.contains(token)
            }
        ) {
            return true
        }

        // A lyrics/text upload is never an acceptable replacement for a clip,
        // even if the original AUDIO title itself contains "Lyrics".
        return alwaysReject.any { token -> candidate.contains(token) }
    }

    private fun containsWholePhrase(haystack: String, needle: String): Boolean =
        " $haystack ".contains(" $needle ") || haystack == needle
}
