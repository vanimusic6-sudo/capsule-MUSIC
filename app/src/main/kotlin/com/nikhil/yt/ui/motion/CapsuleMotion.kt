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
 * - the value is exactly identity at the surface's rest state, so nothing keeps a residual scale or
 *   offset once it has settled, and an idle screen costs nothing;
 * - the value never reverses, so nothing can read as a wobble on the way there.
 *
 * Deliberately absent: blur, and transparency on anything that holds. Full-screen blur forces an
 * offscreen buffer, and cross-fading large surfaces keeps two layers blending for the whole
 * handoff. The one place a caller now reads an opacity off one of these curves is the player's
 * close, which lasts one gesture and ends with the layer gone; nothing here fades while a surface
 * merely sits there.
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

    /**
     * A soft, sticky release of the ORIGINAL Mini from the ascending sheet.
     *
     * 12915.mp4: compressing almost 5% in the first quarter of a very stiff spring
     * read as a hard punch. Spread a gentler squeeze across the whole real Mini -> image
     * overlap, preserving zero velocity at the start, at its middle, and at the return.
     * Pure geometry of the same anchored progress: no per-frame allocations or second clock.
     */
    fun miniCompression(progress: Float): Float {
        val p = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f
        if (p <= 0f || p >= 0.47f) return 0f
        val t = p / 0.47f
        val arch = 4f * t * (1f - t)
        return (arch * arch).coerceIn(0f, 1f)
    }

    fun miniScaleX(progress: Float): Float = 1f - 0.035f * miniCompression(progress)
    fun miniScaleY(progress: Float): Float = 1f - 0.019f * miniCompression(progress)

    /** The app's navigation tucks early and only returns near the Mini's final dock. */
    fun navigationTucked(
        opening: Boolean,
        compactPressed: Boolean,
        progress: Float,
        dismissed: Boolean,
    ): Boolean {
        if (dismissed) return false
        val p = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f
        // Never move the nav during the stationary player preparation frame.
        val threshold = if (opening || compactPressed) 0.025f else 0.16f
        return p >= threshold
    }

    fun navTuckScaleX(progress: Float): Float = 1f - 0.07f * smooth(progress)
    fun navTuckScaleY(progress: Float): Float = 1f - 0.19f * smooth(progress)

    /**
     * Tiny return-only stretch around the moment navigation emerges above the screen edge.
     * One pulse, peaking at 2.8%, no extra spring, no timing loop; always exactly 1 at rest.
     */
    fun navReturnStretch(progress: Float): Float {
        val p = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f
        if (p <= 0f || p >= 0.46f) return 1f
        val t = p / 0.46f
        val arch = 4f * t * (1f - t)
        return 1f + 0.028f * arch * arch
    }

    /** Mini gains at most 1.25 physical dp of delayed lift (including scale stays within the measured dock envelope). */
    fun miniAdhesionDp(progress: Float): Float = 1.25f * miniCompression(progress)

    /**
     * Quintic smootherstep. Velocity and acceleration are both zero at the endpoints, so a
     * transition can be interrupted or handed to another surface without the tiny start/stop kick
     * that a cubic smoothstep still leaves in acceleration.
     */
    fun smooth(value: Float): Float {
        if (!value.isFinite()) return 1f
        val x = value.coerceIn(0f, 1f)
        // Float rounding can make the polynomial land microscopically above 1 at x == 1.
        // That is harmless for colour/alpha, but fatal when a caller turns (1 - smooth) into
        // geometry such as a Compose corner radius. Keep the primitive true to its 0..1 contract.
        return (x * x * x * (x * (x * 6f - 15f) + 10f)).coerceIn(0f, 1f)
    }
}
