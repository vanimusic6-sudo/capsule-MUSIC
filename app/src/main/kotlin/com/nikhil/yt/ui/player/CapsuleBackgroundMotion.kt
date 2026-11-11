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
    // Every fifth change, a handful of the stars form a familiar asterism.
    // The remaining stars still follow the original seeded random sky.
    val shape = capsuleKnownConstellation(generation)
    return List(count) { index ->
        val mark = shape?.getOrNull(index)
        if (mark == null) CapsuleStar(random.nextFloat(), random.nextFloat(),
            0.36f + random.nextFloat() * 0.64f, random.nextFloat() * 6.28f)
        else CapsuleStar(mark.first, mark.second, 0.94f, random.nextFloat() * 6.28f)
    }
}

/** Each axis can be sampled from the shared frame angle without allocating a Pair per star. */
internal fun capsuleStarDriftX(star: CapsuleStar, angle: Double): Float =
    sin(angle * (0.18 + star.depth * 0.14) + star.phase).toFloat()

internal fun capsuleStarDriftY(star: CapsuleStar, angle: Double): Float =
    cos(angle * (0.15 + star.depth * 0.11) + star.phase).toFloat()

/** Pure, continuous coordinates; kept as a convenient API for boundary regression tests. */
internal fun capsuleStarDrift(star: CapsuleStar, elapsedMs: Long): Pair<Float, Float> {
    val angle = capsuleBackgroundAngle(elapsedMs)
    return capsuleStarDriftX(star, angle) to capsuleStarDriftY(star, angle)
}

/** Familiar real-world constellations, drawn as stars only (no symbols or lines). */
internal fun capsuleKnownConstellation(generation: Long): List<Pair<Float, Float>>? {
    if (generation % 5L != 2L) return null
    val atlas = listOf(
        // Orion: shoulders, three-star belt, legs.
        listOf(0.40f to 0.30f, 0.59f to 0.31f, 0.46f to 0.48f,
            0.50f to 0.49f, 0.54f to 0.50f, 0.42f to 0.70f, 0.60f to 0.72f),
        // Cassiopeia's W.
        listOf(0.24f to 0.42f, 0.37f to 0.59f, 0.49f to 0.38f,
            0.61f to 0.57f, 0.76f to 0.39f),
        // Ursa Major's Big Dipper.
        listOf(0.34f to 0.39f, 0.53f to 0.42f, 0.55f to 0.60f,
            0.36f to 0.57f, 0.27f to 0.44f, 0.18f to 0.40f, 0.11f to 0.33f),
        // Cygnus (Northern Cross).
        listOf(0.51f to 0.21f, 0.51f to 0.40f, 0.51f to 0.59f,
            0.51f to 0.78f, 0.28f to 0.46f, 0.74f to 0.45f),
    )
    return atlas[((generation / 5L) % atlas.size).toInt()]
}
