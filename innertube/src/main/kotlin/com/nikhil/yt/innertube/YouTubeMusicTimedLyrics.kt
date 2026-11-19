/*
 * Capsule MUSIC — line-synchronized YouTube Music lyrics.
 *
 * Response contract adapted from Metrolist 14's YouTubeLyricsProvider,
 * licensed GPL-3.0. Parse only real timedLyricsData; never fabricate timestamps
 * for unsynced text or a generic video transcript.
 */
package com.nikhil.yt.innertube

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal object YouTubeMusicTimedLyrics {
    private const val MAX_DEPTH = 24
    private const val MAX_LINES = 2_000
    private const val MAX_LINE_CHARS = 1_024
    private const val MAX_LYRICS_CHARS = 150_000
    private const val MAX_DURATION_MS = 24 * 60 * 60 * 1_000L

    fun findLyricsBrowseId(response: JsonElement): String? {
        fun lookup(node: JsonElement, depth: Int): String? {
            if (depth > MAX_DEPTH) return null
            return when (node) {
                is JsonObject -> {
                    val id = (node["browseId"] as? JsonPrimitive)?.contentOrNull
                    if (id != null && id.startsWith("MPLYt") && id.length <= 256) id
                    else node.values.firstNotNullOfOrNull { lookup(it, depth + 1) }
                }
                is JsonArray -> node.firstNotNullOfOrNull { lookup(it, depth + 1) }
                else -> null
            }
        }
        return lookup(response, 0)
    }

    fun toSynchronizedLrc(response: JsonElement): String? {
        fun lookup(node: JsonElement, depth: Int): JsonArray? {
            if (depth > MAX_DEPTH) return null
            return when (node) {
                is JsonObject -> (node["timedLyricsData"] as? JsonArray)
                    ?: node.values.firstNotNullOfOrNull { lookup(it, depth + 1) }
                is JsonArray -> node.firstNotNullOfOrNull { lookup(it, depth + 1) }
                else -> null
            }
        }

        val timed = lookup(response, 0) ?: return null
        if (timed.isEmpty()) return null
        val result = StringBuilder()
        val seen = HashSet<Pair<Long, String>>()
        var count = 0
        for (entry in timed) {
            if (count >= MAX_LINES || result.length >= MAX_LYRICS_CHARS) break
            val line = entry as? JsonObject ?: continue
            val text = (line["lyricLine"] as? JsonPrimitive)?.contentOrNull
                ?.replace('\r', ' ')?.trim()?.takeIf { it.isNotBlank() && it.length <= MAX_LINE_CHARS }
                ?: continue
            val range = line["cueRange"] as? JsonObject ?: continue
            val start = (range["startTimeMilliseconds"] as? JsonPrimitive)
                ?.contentOrNull?.toLongOrNull() ?: continue
            val end = (range["endTimeMilliseconds"] as? JsonPrimitive)
                ?.contentOrNull?.toLongOrNull() ?: continue
            if (start !in 0..MAX_DURATION_MS || end <= start || end > MAX_DURATION_MS) continue
            if (!seen.add(start to text)) continue
            val millis = start % 1_000L
            val sec = (start / 1_000L) % 60L
            val min = start / 60_000L
            val stamp = "[%02d:%02d.%03d]".format(java.util.Locale.US, min, sec, millis)
            val safe = text.replace('\n', ' ')
            if (result.length + stamp.length + safe.length > MAX_LYRICS_CHARS) break
            result.append(stamp).append(safe).append('\n')
            count++
        }
        return result.toString().trimEnd().takeIf { it.isNotBlank() }
    }
}
