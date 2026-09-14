package com.nikhil.yt.ui.motion

/**
 * The shared motion vocabulary for Capsule's large surfaces.
 *
 * Everything here is a pure function of a transition's *progress*. That is the whole point, and it
 * is a correction of how these surfaces used to move: they reacted to the animation's *velocity*,
 * adding an "impact" squash on top of an under-damped spring. Velocity is noisy, a spring overshoots
 * its target, and a reaction that trails the motion it reacts to reads exactly like the mini-player
 * shimmering behind the player instead of being landed on.
 *
 * Two properties hold for every function here and are covered by tests:
 * - the value is exactly identity at the surface's rest state, so nothing keeps a residual scale,
 *   offset or opacity once it has settled, and an idle screen costs nothing;
 * - the value never reverses, so nothing can read as a wobble on the way there.
 *
 * Deliberately absent: blur. A full-screen `RenderEffect` forces an offscreen buffer for the whole
 * surface on every frame, and allocating it the first time is what made opening the player stall.
 * Scale and opacity ride the layer that already exists and cost effectively nothing.
 */
object CapsuleMotion {
    /**
     * How far a surface is from the low end of its travel, smoothed.
     *
     * 0 exactly at rest at the low end, 1 once past [window] and for the whole rest of the travel.
     * Use it for an effect that must be fully applied when docked and completely absent when open.
     */
    fun approach(progress: Float, window: Float): Float {
        if (!progress.isFinite() || !window.isFinite() || window <= 0f) return 1f
        return smooth((progress.coerceIn(0f, 1f) / window).coerceIn(0f, 1f))
    }

    /** Smoothstep. Zero slope at both ends, so motion neither starts nor stops abruptly. */
    fun smooth(value: Float): Float {
        if (!value.isFinite()) return 1f
        val clamped = value.coerceIn(0f, 1f)
        return clamped * clamped * (3f - 2f * clamped)
    }
}
