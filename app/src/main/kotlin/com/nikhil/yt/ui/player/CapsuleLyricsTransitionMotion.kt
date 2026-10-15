/*
 * Capsule MUSIC — player / Lyrics physical page transform.
 * GPL-3.0
 */
package com.nikhil.yt.ui.player

import kotlin.math.PI
import kotlin.math.abs
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
 * Bounded depth cue: the page gets further away during travel (but never
 * while resting). The raised-cosine has zero first derivative at both ends;
 * no abrupt scale velocity spike in the first/last rendered 60 Hz frame.
 */
internal fun capsuleLyricsRetreat(body: Float, lead: Float): Float {
    val p = body.coerceIn(0f, 1f)
    val retreat = 0.034f * sin((PI * p).toFloat()).let { it * it }
    val elasticity = 0.009f * abs(capsuleLyricsEdgeRecoil(body, lead))
    return (retreat + elasticity).coerceIn(0f, 0.037f)
}

/** Sheet opening and page ink are one motion; reversible on every single frame. */
internal fun capsuleLyricsForegroundOpacity(body: Float): Float {
    val progress = body.coerceIn(0f, 1f)
    // Begin acquiring readable controls as soon as the full page enters;
    // finished before the sheet locks into place. Eliminates the visible
    // "empty panel rising first, lyrics appear 300ms later" defect.
    val reveal = ((0.88f - progress) / 0.58f).coerceIn(0f, 1f)
    return reveal * reveal * (3f - 2f * reveal)
}

/** Match layer travel and the inverse counter-travel exactly. */
internal fun capsuleLyricsTranslation(
    body: Float,
    recoil: Float,
    heightPx: Float,
    recoilBudgetPx: Float,
): Float = body * heightPx + recoil * recoilBudgetPx
