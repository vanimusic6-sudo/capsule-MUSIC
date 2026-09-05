package com.nikhil.yt.utils

internal object RecommendationFeedback {
    private const val WEEK_MS = 7 * 24 * 60 * 60 * 1_000L

    fun effectiveSkips(count: Int, lastSkippedAt: Long, likedAt: Long?, now: Long): Int {
        if (likedAt != null && likedAt >= lastSkippedAt) return 0
        val weeks = ((now - lastSkippedAt).coerceAtLeast(0L) / WEEK_MS).coerceAtMost(20).toInt()
        return (count.coerceIn(0, 20) - weeks).coerceAtLeast(0)
    }

    /** Apply candidate feedback before artist diversity, retaining upstream relevance order for ties. */
    fun <T> select(
        candidates: List<T>,
        limit: Int,
        id: (T) -> String,
        artistIds: (T) -> List<String>,
        rejected: (T) -> Boolean,
        score: (T) -> Float,
    ): List<T> {
        val artists = mutableMapOf<String, Int>()
        return candidates.distinctBy(id).filterNot(rejected).sortedByDescending(score).filter { candidate ->
            val keys = artistIds(candidate).filter { it.isNotBlank() }.distinct()
            if (keys.any { (artists[it] ?: 0) >= 3 }) false
            else {
                keys.forEach { artists[it] = (artists[it] ?: 0) + 1 }
                true
            }
        }.take(limit)
    }
}
