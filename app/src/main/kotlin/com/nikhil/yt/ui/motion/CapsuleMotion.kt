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

    /**
     * Combines two independent reasons for the same surface to be hidden.
     *
     * The navigation bar has two: the player expanding over it, and the bar itself being taken away.
     * They overlap constantly — closing the player while the bar comes back is the single most
     * common thing that happens at the bottom of this app — and the obvious ways to combine them
     * are both wrong. Adding them lets the total exceed the travel, so the bar is shoved twice as
     * far as it can go and then has to come all the way back. Taking the larger of the two puts a
     * corner in the motion at the moment the other one takes over: the speed changes instantly,
     * which is exactly what reads as the movement tearing.
     *
     * This is the probabilistic OR, and it is what those two want to be. It equals either input
     * when the other is zero, reaches 1 only when one of them does, and its slope moves
     * continuously as the balance shifts between them — so two overlapping animations read as one
     * movement rather than as a handoff.
     */
    fun either(first: Float, second: Float): Float {
        val a = if (first.isFinite()) first.coerceIn(0f, 1f) else 0f
        val b = if (second.isFinite()) second.coerceIn(0f, 1f) else 0f
        return (a + b - a * b).coerceIn(0f, 1f)
    }

    /** Smoothstep. Zero slope at both ends, so motion neither starts nor stops abruptly. */
    fun smooth(value: Float): Float {
        if (!value.isFinite()) return 1f
        val clamped = value.coerceIn(0f, 1f)
        return clamped * clamped * (3f - 2f * clamped)
    }
}
