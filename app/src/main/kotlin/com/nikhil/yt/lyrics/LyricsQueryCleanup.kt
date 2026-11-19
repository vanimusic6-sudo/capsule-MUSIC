/* Capsule MUSIC — shared lookup normalization, independent of retired lyric services.
 * GPL-3.0; adapted from the existing Capsule Paxsenix implementation.
 */
package com.nikhil.yt.lyrics

/** Common metadata cleanup for SimpMusic and LyricsPlus. */
internal object LyricsQueryCleanup {
    private val noise =
        listOf(
            Regex(
                """\s*\(.*?(official|video|audio|lyrics?|visualizer|hd|hq|4k|remaster|live|acoustic|version|edit|extended|radio|clean|explicit).*?\)""",
                RegexOption.IGNORE_CASE,
            ),
            Regex(
                """\s*\[.*?(official|video|audio|lyrics?|visualizer|hd|hq|4k|remaster|live|acoustic|version|edit|extended|radio|clean|explicit).*?]""",
                RegexOption.IGNORE_CASE,
            ),
            Regex("""\s*【.*?】"""),
            Regex("""\s*\|.*$"""),
            Regex(
                """\s*-\s*(official|video|audio|lyrics?|visualizer).*$""",
                RegexOption.IGNORE_CASE,
            ),
            Regex("""\s*\((feat\.|ft\.).*?\)""", RegexOption.IGNORE_CASE),
            Regex("""\s*(feat\.|ft\.).*$""", RegexOption.IGNORE_CASE),
        )

    private val artistSeparators =
        listOf(
            " & ",
            " and ",
            ", ",
            " x ",
            " feat. ",
            " feat ",
            " ft. ",
            " ft ",
            " featuring ",
            " with ",
        )

    fun title(raw: String): String {
        var cleaned = raw.trim()
        noise.forEach { cleaned = cleaned.replace(it, "") }
        return cleaned.trim()
    }

    fun artist(raw: String): String {
        var cleaned = raw.trim()
        for (separator in artistSeparators) {
            if (cleaned.contains(separator, ignoreCase = true)) {
                cleaned =
                    cleaned
                        .split(separator, ignoreCase = true, limit = 2)
                        .first()
                break
            }
        }
        return cleaned.trim()
    }
}
