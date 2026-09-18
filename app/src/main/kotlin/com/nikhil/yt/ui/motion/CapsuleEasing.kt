package com.nikhil.yt.ui.motion

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing

/**
 * The curves every timed animation in Capsule runs on.
 *
 * They exist because most animations in the app had no curve at all. `tween(200)` silently means
 * Compose's `FastOutSlowIn`, whose defining property is that it leaves at speed: its slope at the
 * first frame is steep, so the thing being animated is already a third of the way gone before the
 * eye has registered that it moved. On an arrival that reads as a jolt at the start, and on a
 * departure it reads as the element being snatched away rather than leaving.
 *
 * Every curve here is a cubic bezier whose first control point sits on y = 0 and whose second sits
 * on y = 1. That is not a style choice, it is what removes the jolt: it makes the slope exactly
 * zero at both ends, so an animation starts from rest and arrives at rest. Nothing about distance
 * or duration changes — the path and the timing of each animation are what they were, only the
 * distribution of the movement along that time is different.
 *
 * Three of them, because the three cases genuinely want different tails:
 * - something arriving should settle slowly, so the last few pixels are visible;
 * - something leaving should clear the screen without lingering, but must not bolt;
 * - a value merely changing — a colour, a size, a rotation — belongs between the two.
 */

/** Something appearing or expanding. The longest tail: an arrival is what the eye follows. */
val CapsuleEnterEasing: Easing = CubicBezierEasing(0.22f, 0f, 0.36f, 1f)

/**
 * Something disappearing or collapsing.
 *
 * Shorter tail than an arrival, because nobody wants to watch a dismissed thing drift, but the
 * start is still at rest — that first stationary instant is the whole difference between an element
 * leaving and an element being snatched.
 */
val CapsuleExitEasing: Easing = CubicBezierEasing(0.4f, 0f, 0.26f, 1f)

/** A value changing in place: a colour, a size, a corner, a rotation. */
val CapsuleStandardEasing: Easing = CubicBezierEasing(0.3f, 0f, 0.3f, 1f)

/**
 * The shortest a visible transition may be.
 *
 * Below roughly this, a soft curve stops helping: the whole movement lands inside two or three
 * frames, and what the eye gets is not a fast animation but a change it did not see happen. That is
 * the "too quick" complaint, and it is not fixed by easing — a curve can only distribute the time it
 * is given. Several exits ran at 150-180ms, which is where that starts.
 *
 * It is a floor, not a target. Anything with further to travel takes longer.
 */
const val CapsuleShortestVisible: Int = 200
