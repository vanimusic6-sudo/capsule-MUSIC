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
    return ((lead - body) * envelope).coerceIn(-0.12f, 0.12f)
}

/**
 * The body spring is allowed just ~0.8% beyond the fully open anchor. This
 * gives the last few pixels the requested small, soft landing impulse rather
 * than a gigantic 25px rubber band at 60Hz. Closing uses a non-overshooting
 * spring and hence never travels past the hidden bottom edge.
 */
internal fun capsuleLyricsLandingTravel(body: Float): Float =
    body.coerceIn(-0.008f, 1f)

/** Sheet opening and page ink are one motion; reversible on every single frame. */
internal fun capsuleLyricsForegroundOpacity(body: Float): Float {
    val progress = body.coerceIn(0f, 1f)
    // Begin acquiring readable controls as soon as the full page enters;
    // finished before the sheet locks into place. Eliminates the visible
    // "empty panel rising first, lyrics appear 300ms later" defect.
    val reveal = ((0.78f - progress) / 0.70f).coerceIn(0f, 1f)
    return reveal * reveal * (3f - 2f * reveal)
}

/** Match layer travel and the inverse counter-travel exactly. */
internal fun capsuleLyricsTranslation(
    body: Float,
    recoil: Float,
    heightPx: Float,
    recoilBudgetPx: Float,
): Float = capsuleLyricsLandingTravel(body) * heightPx + recoil * recoilBudgetPx
