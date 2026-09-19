/**
 * Capsule MUSIC
 * How well a set of lyrics is synced, so the best one can win.
 * GPL-3.0
 */

package com.nikhil.yt.lyrics

/**
 * How closely lyrics can follow the music.
 *
 * The order is the whole point: a source that lands on the syllable is better than one that only
 * knows when a line starts, which is better than a wall of text with no timing at all. Ordinal
 * comparison is meaningful and relied upon.
 */
enum class LyricsSyncQuality {
    /** No timestamps. Readable, but it does not follow anything. */
    PLAIN,

    /** A timestamp per line. Follows the song, and lags or rushes by however wrong it is. */
    LINE,

    /** A timestamp per word or syllable. This is what "in time" actually means. */
    WORD,
}

private val LINE_STAMP = Regex("""\[\d{1,2}:\d{2}[.:]\d{2,3}]""")
private val WORD_STAMP = Regex("""<\d{1,2}:\d{2}[.:]\d{2,3}>""")

/**
 * Grades [lyrics] without parsing them fully.
 *
 * Deliberately shape-based rather than trusting whoever produced them: a provider that advertises
 * word sync and returns a plain block of text would otherwise outrank a source that quietly does
 * the job properly, which is the failure this whole ranking exists to prevent.
 */
fun lyricsSyncQuality(lyrics: String): LyricsSyncQuality {
    if (lyrics.isBlank()) return LyricsSyncQuality.PLAIN

    // TTML is always at least line-timed, and carries word timing when it has <span> begin times.
    if (LyricsUtils.isTtml(lyrics)) {
        val wordTimed = lyrics.contains("<span", ignoreCase = true) && lyrics.contains("begin=")
        return if (wordTimed) LyricsSyncQuality.WORD else LyricsSyncQuality.LINE
    }

    if (WORD_STAMP.containsMatchIn(lyrics)) return LyricsSyncQuality.WORD
    if (LINE_STAMP.containsMatchIn(lyrics)) return LyricsSyncQuality.LINE
    return LyricsSyncQuality.PLAIN
}
