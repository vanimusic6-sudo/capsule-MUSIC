package com.nikhil.yt.ui.motion

import kotlin.math.sin

/**
 * The shared motion vocabulary for Capsule's large surfaces.
 *
 * Everything here is a pure function of a transition's *progress*. That is the whole point, and it
 * is a correction of how these surfaces used to move: they reacted to the animation's *velocity*,
 * adding an "impact" squash on top of an under-damped spring. Velocity is noisy, a spring overshoots
 * its target, and a reaction that trails the motion it reacts to reads exactly like the mini-player
 * shaking as the player leaves.
 *
 * A transform derived from progress alone cannot do that. It is the same clock the player's own
 * travel uses, so the two are coupled by construction — the mini-player is drawn along by the
 * player rather than jolted by it — and it follows a finger during a drag as faithfully as it
 * follows an animation.
 *
 * Two properties hold for every function here and are covered by tests:
 * - the value is exactly 0 at both rest states, so no surface keeps a residual scale, offset or
 *   blur once it has settled;
 * - the value rises and falls once, with no oscillation, so nothing can read as a wobble.
 */
object CapsuleMotion {
    /**
     * A single smooth pull, coupled to a transition approaching its *low* end.
     *
     * [progress] is the surface's own 0..1 travel. [window] is how much of that travel the pull
     * occupies, measured from 0. Outside the window the result is 0, so the effect exists only while
     * the two surfaces are actually near each other.
     *
     * The curve is a half sine: zero at the far edge of the window, zero again exactly at rest, one
     * peak in between. There is no second lobe, so there is nothing to read as a bounce.
     */
    fun pullToward(progress: Float, window: Float): Float {
        if (!progress.isFinite() || !window.isFinite() || window <= 0f) return 0f
        val travelled = progress.coerceIn(0f, 1f)
        if (travelled >= window) return 0f
        val insideWindow = (1f - travelled / window).coerceIn(0f, 1f)
        return sin(Math.PI.toFloat() * insideWindow).coerceIn(0f, 1f)
    }

    /** The same single pull, coupled to a transition approaching its *high* end. */
    fun pullAway(progress: Float, window: Float): Float {
        if (!progress.isFinite()) return 0f
        return pullToward(1f - progress.coerceIn(0f, 1f), window)
    }

    /**
     * Blur radius in pixels for a motion weight, used to suggest speed rather than to decorate.
     *
     * Zero weight returns exactly zero so that a settled surface carries no `RenderEffect` at all
     * and costs nothing; the caller is expected to skip the effect entirely on a zero radius.
     */
    fun speedBlurPx(weight: Float, maxRadiusPx: Float): Float {
        if (!weight.isFinite() || !maxRadiusPx.isFinite() || maxRadiusPx <= 0f) return 0f
        return (weight.coerceIn(0f, 1f) * maxRadiusPx).coerceIn(0f, maxRadiusPx)
    }

    /**
     * Per-item progress for a staggered entrance.
     *
     * Items start in order and each one still finishes at [progress] == 1, so the whole entrance has
     * a fixed end rather than growing longer with every extra row on screen. [stride] is the share
     * of the entrance an item waits before it begins.
     *
     * Callers apply the result through `graphicsLayer` only. A staggered entrance that changes
     * layout — `slideInVertically` inside a list, say — lets a card travel over a neighbour that is
     * already parked at its final position, which is what made the settings list look broken.
     */
    fun staggered(progress: Float, index: Int, stride: Float): Float {
        if (!progress.isFinite()) return 1f
        if (!stride.isFinite() || stride <= 0f || index <= 0) return smooth(progress.coerceIn(0f, 1f))
        val delay = (index * stride).coerceIn(0f, MaxStaggerDelay)
        val span = 1f - delay
        if (span <= 0f) return smooth(progress.coerceIn(0f, 1f))
        return smooth(((progress.coerceIn(0f, 1f) - delay) / span).coerceIn(0f, 1f))
    }

    /** Smoothstep. Zero slope at both ends, so an entrance neither starts nor stops abruptly. */
    fun smooth(value: Float): Float {
        if (!value.isFinite()) return 1f
        val clamped = value.coerceIn(0f, 1f)
        return clamped * clamped * (3f - 2f * clamped)
    }

    /** No item may wait out more than this share of the entrance, however long the list is. */
    private const val MaxStaggerDelay = 0.55f
}
