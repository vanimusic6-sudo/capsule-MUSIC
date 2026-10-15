/*
 * Capsule MUSIC — player / Lyrics physical page transform.
 * GPL-3.0
 */
package com.nikhil.yt.ui.player

import kotlin.math.PI
import kotlin.math.sin

/**
 * Sample the entire Lyrics transition from just two *spring positions*,
 * independent of display refresh rate. Avoid time-based foreground delays,
 * multiple page masks and animator updates in the Compose composition phase.
 */
internal fun capsuleLyricsEdgeRecoil(body: Float, lead: Float): Float {
    val progress = body.coerceIn(0f, 1f)
    val envelope = sin((PI * progress).toFloat()).coerceAtLeast(0f)
    return ((lead - body) * envelope).coerceIn(-0.18f, 0.18f)
}

/**
 * The aperture top edge may overrun the screen by at most 0.4% of its height
 * (approximately 3px for a 60Hz phone), then settle softly. Both foreground
 * and backdrop cancel this exact travel, so NO text or gradient travels.
 */
internal fun capsuleLyricsLandingTravel(body: Float): Float =
    body.coerceIn(-0.004f, 1f)

/** Sheet opening and page ink are one motion; reversible on every single frame. */
internal fun capsuleLyricsForegroundOpacity(body: Float): Float {
    val progress = body.coerceIn(0f, 1f)
    // The painterly scene (gradient) appears with the mask; text becomes
    // opaque WHILE the aperture is still moving, rather than popping in
    // after it reaches the top. The smoothstep has zero endpoint slope at
    // both the reveal and retract boundaries and is fully reversible.
    val reveal = ((0.92f - progress) / 0.42f).coerceIn(0f, 1f)
    return reveal * reveal * (3f - 2f * reveal)
}

/** Match layer travel and the inverse counter-travel exactly. */
internal fun capsuleLyricsTranslation(
    body: Float,
    recoil: Float,
    heightPx: Float,
    recoilBudgetPx: Float,
): Float = capsuleLyricsLandingTravel(body) * heightPx + recoil * recoilBudgetPx

/**
 * One low-cost optical "ink" handoff, with a natural small waterfall:
 * transport (first visible near the bottom), timeline, live lyric lines,
 * and the top bar (last visible once the aperture reaches the top).
 * Unlike four independent Animatables, the whole choreography samples
 * only the existing spring's position and can reverse in ONE frame.
 */
internal enum class LyricsInkZone { HEADER, LINES, TIMELINE, CONTROLS }

internal fun capsuleLyricsZoneOpacity(
    sheetPosition: Float,
    zone: LyricsInkZone,
): Float {
    val (start, finish) = when (zone) {
        LyricsInkZone.CONTROLS -> 0.90f to 0.47f
        LyricsInkZone.TIMELINE -> 0.78f to 0.38f
        LyricsInkZone.LINES -> 0.74f to 0.21f
        LyricsInkZone.HEADER -> 0.35f to 0.025f
    }
    val t = ((start - sheetPosition.coerceIn(0f, 1f)) / (start - finish))
        .coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/** Local 6dp float-in; never rescales glyphs or moves their layout bounds. */
internal fun capsuleLyricsZoneFloatDp(
    sheetPosition: Float,
    zone: LyricsInkZone,
): Float {
    val a = capsuleLyricsZoneOpacity(sheetPosition, zone)
    return 6f * (1f - a) * (1f - a)
}
