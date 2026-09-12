package com.nikhil.yt.utils

import kotlin.math.ln

/** Compare listening in equivalent plays, with diminishing returns and meaningful feedback. */
internal object RecommendationScore {
    fun calculate(
        totalPlayTimeMs: Long,
        durationSeconds: Int,
        skipCount: Int,
        liked: Boolean,
        recent: Boolean,
        timeOfDay: String,
    ): Float {
        val durationMs = (durationSeconds.takeIf { it > 0 } ?: 180).coerceAtLeast(30) * 1_000.0
        val equivalentPlays = totalPlayTimeMs.coerceAtLeast(0L) / durationMs
        val listening = (ln(1.0 + equivalentPlays) * 2.0).coerceAtMost(12.0)
        val feedback = (if (liked) 5.0 else 0.0) + (if (recent) 1.5 else 0.0)
        val skips = skipCount.coerceAtLeast(0).coerceAtMost(20) * 3.0
        val timeBonus = when {
            timeOfDay == "morning" && recent -> 1.3
            timeOfDay == "night" && liked -> 1.3
            else -> 1.0
        }
        return ((listening + feedback - skips).coerceAtLeast(0.0) * timeBonus).toFloat()
    }
}
