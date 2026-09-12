package com.nikhil.yt.ui.player

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

internal const val CONSTELLATION_HOLD_MS = 72_000L
internal const val CONSTELLATION_FADE_MS = 12_000L
internal const val CONSTELLATION_PERIOD_MS = CONSTELLATION_HOLD_MS + CONSTELLATION_FADE_MS

/** Keep time continuous: fractional-speed waves must not reset every 36 seconds. */
internal fun capsuleBackgroundAngle(elapsedMs: Long): Double =
    elapsedMs.coerceAtLeast(0L).toDouble() / 36_000.0 * 2.0 * PI

internal data class ConstellationBlend(val generation: Long, val nextAlpha: Float)

internal fun constellationBlend(elapsedMs: Long): ConstellationBlend {
    val time = elapsedMs.coerceAtLeast(0L)
    val progress = ((time % CONSTELLATION_PERIOD_MS - CONSTELLATION_HOLD_MS).toFloat() /
        CONSTELLATION_FADE_MS).coerceIn(0f, 1f)
    // Zero velocity at both ends; the new field survives the period boundary.
    val eased = progress * progress * (3f - 2f * progress)
    return ConstellationBlend(time / CONSTELLATION_PERIOD_MS, eased)
}

internal data class CapsuleStar(val x: Float, val y: Float, val depth: Float, val phase: Float)

internal fun capsuleConstellation(generation: Long, count: Int): List<CapsuleStar> {
    val random = Random((generation xor (generation ushr 32)).toInt() xor 0xCA57)
    return List(count) {
        CapsuleStar(random.nextFloat(), random.nextFloat(), 0.36f + random.nextFloat() * 0.64f, random.nextFloat() * 6.28f)
    }
}

/** Pure, continuous coordinates; shared by rendering and boundary regression tests. */
internal fun capsuleStarDrift(star: CapsuleStar, elapsedMs: Long): Pair<Float, Float> {
    val angle = capsuleBackgroundAngle(elapsedMs)
    return sin(angle * (0.18 + star.depth * 0.14) + star.phase).toFloat() to
        cos(angle * (0.15 + star.depth * 0.11) + star.phase).toFloat()
}
