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
 * Zero-velocity ends are necessary and are not sufficient, which took a second pass to see. A curve
 * has to cover the whole distance in the time it is given, so if it leaves at rest and arrives at
 * rest, that speed has to come back somewhere — and it comes back in the middle. The first version
 * of these curves peaked at 1.75 to 2.33 times their own average speed; the departure was the worst
 * of them, and Compose's default is 2.5. Soft at the ends and twice as fast in the middle is
 * precisely what reads as aggressive: the movement does not start with a jolt any more, it whips
 * past instead.
 *
 * So the control points are pulled apart rather than sat on top of each other, which is what widens
 * the middle. Peak speed is now 1.25 to 1.41 times average, which is a movement with a pace rather
 * than a lunge, and a test holds it there.
 *
 * Three of them, because the three cases genuinely want different tails:
 * - something arriving should settle slowly, so the last few pixels are visible;
 * - something leaving should clear the screen without lingering, but must not bolt;
 * - a value merely changing — a colour, a size, a rotation — belongs between the two.
 */

/** Something appearing or expanding. The longest tail: an arrival is what the eye follows. */
val CapsuleEnterEasing: Easing = CubicBezierEasing(0.2f, 0f, 0.8f, 1f)

/**
 * Something disappearing or collapsing.
 *
 * Shorter tail than an arrival, because nobody wants to watch a dismissed thing drift, but the
 * start is still at rest — that first stationary instant is the whole difference between an element
 * leaving and an element being snatched.
 */
val CapsuleExitEasing: Easing = CubicBezierEasing(0.28f, 0f, 0.7f, 1f)

/** A value changing in place: a colour, a size, a corner, a rotation. */
val CapsuleStandardEasing: Easing = CubicBezierEasing(0.22f, 0f, 0.76f, 1f)

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
const val CapsuleShortestVisible: Int = 260
