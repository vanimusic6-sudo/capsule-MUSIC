/*
 * Capsule MUSIC — instrumental intervals for the lyrics display.
 * GPL-3.0
 * Intro/outro timing follows ArchiveTune's 5s threshold, 1s intro and 2.5s vocal tail.
 */
package com.nikhil.yt.lyrics

private const val MIN_INSTRUMENTAL_MS = 5_000L
private const val INTRO_START_MS = 1_000L
private const val UNTYPED_VOCAL_TAIL_MS = 2_500L

/**
 * Display-only rows; the stored lyrics and provider results remain untouched.
 * A long line-start interval alone is not proof that the singer has stopped. Middle breaks
 * require an actual word/TTML end or a timestamped empty LRC line, so sustained vocals do
 * not acquire a false instrumental indicator. Overlapping/background vocals count too.
 */
internal fun withInstrumentalBreaks(
    entries: List<LyricsEntry>,
    songDurationMs: Long = 0L,
): List<LyricsEntry> {
    val vocals = entries.filter { it.time >= 0L && it.text.isNotBlank() && !it.isInstrumental }
        .sortedBy { it.time }
    if (vocals.isEmpty()) return entries
    val emptyMarkers = entries.filter { it.time >= 0L && it.text.isBlank() && !it.isInstrumental }
        .sortedBy { it.time }
    val breaks = mutableListOf<LyricsEntry>()

    fun addBreak(start: Long, end: Long) {
        if (start < 0L || end - start < MIN_INSTRUMENTAL_MS) return
        breaks += LyricsEntry(time = start, text = "", durationMs = end - start, isInstrumental = true)
    }

    addBreak(INTRO_START_MS, vocals.first().time)
    // Retain the latest known vocal end across overlapping TTML agents/duets.
    var occupiedUntil = 0L
    vocals.forEachIndexed { index, vocal ->
        val knownEnd = vocal.knownVocalEndMs()
        occupiedUntil = maxOf(occupiedUntil, vocal.time, knownEnd ?: vocal.time)
        val nextStart = vocals.getOrNull(index + 1)?.time
        val explicitEnd = emptyMarkers.firstOrNull {
            it.time > vocal.time && (nextStart == null || it.time < nextStart)
        }?.time
        val breakStart = when {
            knownEnd != null -> occupiedUntil
            explicitEnd != null -> maxOf(occupiedUntil, explicitEnd)
            // Only the outro has ArchiveTune's short line-only fallback.
            nextStart == null -> maxOf(occupiedUntil, vocal.time + UNTYPED_VOCAL_TAIL_MS)
            else -> null
        }
        if (breakStart != null) {
            addBreak(breakStart, nextStart ?: songDurationMs)
        }
    }
    // Empty timestamps are boundaries, not visible blank lyric rows.
    return (vocals + breaks).sortedBy { it.time }
}

private fun LyricsEntry.knownVocalEndMs(): Long? {
    val wordEnd = words?.asSequence()
        ?.filter { it.text.isNotBlank() && it.endTime.isFinite() && it.endTime > it.startTime }
        ?.map { (it.endTime * 1000.0).toLong() }
        ?.maxOrNull()
    val lineEnd = (time + durationMs).takeIf { durationMs > 0L }
    return listOfNotNull(wordEnd, lineEnd).maxOrNull()?.takeIf { it > time }
}

internal fun instrumentalFillFraction(positionMs: Long, startMs: Long, durationMs: Long): Float =
    when {
        durationMs <= 0L || positionMs <= startMs -> 0f
        positionMs - startMs >= durationMs -> 1f
        else -> ((positionMs - startMs).toDouble() / durationMs).toFloat()
    }

/** Half-open interval: no preview before the pause and no stale note after the next vocal. */
internal fun isInstrumentalInterval(positionMs: Long, startMs: Long, durationMs: Long): Boolean =
    durationMs > 0L && positionMs >= startMs && positionMs - startMs < durationMs
