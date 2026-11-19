/* Capsule MUSIC — QQ QRC (Portato) -> enhanced LRC with original word boundaries. GPL-3.0. */
package com.nikhil.yt.lyrics

import java.util.Locale

/**
 * QRC attaches each word's (startMs,durationMs) AFTER its text, while enhanced LRC puts a
 * timestamp BEFORE the word. Translate without losing the last word's explicit end time.
 */
internal object QrcToEnhancedLrc {
    private val envelope = Regex("LyricContent=\"([\\s\\S]*?)\"")
    private val lineHeader = Regex("""^\[(\d+),(\d+)]""")
    private val wordToken = Regex("""\((\d+),(\d+)\)""")
    private val creditLine = Regex(
        """^(lyrics\s*by|composed\s*by|arranged\s*by|producer|作词|作曲|编曲|制作人)\s*[:：]""",
        RegexOption.IGNORE_CASE,
    )

    fun convert(xml: String, title: String, artist: String): String? {
        val raw = (envelope.find(xml)?.groupValues?.getOrNull(1) ?: xml)
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
        val result = ArrayList<String>()
        raw.lineSequence().forEach { source ->
            val line = source.trim()
            val header = lineHeader.find(line) ?: return@forEach
            val startMs = header.groupValues[1].toLongOrNull() ?: return@forEach
            val durationMs = header.groupValues[2].toLongOrNull() ?: return@forEach
            val words = line.substring(header.value.length)
            val tokens = wordToken.findAll(words).toList()
            val displayText = wordToken.replace(words, "").trim()
            if (displayText.isBlank() || creditLine.containsMatchIn(displayText)) return@forEach
            if (startMs < 12_000L && title.isNotBlank() && artist.isNotBlank()) {
                val folded = displayText.lowercase()
                if (folded.contains(title.lowercase()) && folded.contains(artist.lowercase()))
                    return@forEach
            }
            if (tokens.isEmpty()) {
                result.add("[${clock(startMs)}]$displayText")
                return@forEach
            }
            val out = StringBuilder().append("[").append(clock(startMs)).append("]")
            var afterToken = 0
            var lastEnd = startMs + durationMs
            for (token in tokens) {
                val text = words.substring(afterToken, token.range.first)
                val wordStart = token.groupValues[1].toLongOrNull() ?: continue
                val wordDuration = token.groupValues[2].toLongOrNull() ?: 0L
                if (text.isNotEmpty()) out.append("<").append(clock(wordStart)).append(">").append(text)
                afterToken = token.range.last + 1
                lastEnd = (wordStart + wordDuration).coerceAtLeast(wordStart)
            }
            val trailing = words.substring(afterToken)
            if (trailing.isNotEmpty()) out.append(trailing)
            out.append("<").append(clock(lastEnd)).append(">")
            result.add(out.toString())
        }
        return result.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    private fun clock(ms: Long): String {
        val safe = ms.coerceAtLeast(0)
        return String.format(
            Locale.US, "%02d:%02d.%03d",
            safe / 60_000, (safe / 1_000) % 60, safe % 1_000,
        )
    }
}
