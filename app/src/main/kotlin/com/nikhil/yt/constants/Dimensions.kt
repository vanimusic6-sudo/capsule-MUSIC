/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */

package com.nikhil.yt.constants

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

const val CONTENT_TYPE_HEADER = 0
const val CONTENT_TYPE_LIST = 1
const val CONTENT_TYPE_SONG = 2
const val CONTENT_TYPE_ARTIST = 3
const val CONTENT_TYPE_ALBUM = 4
const val CONTENT_TYPE_PLAYLIST = 5

val NavigationBarHeight = 80.dp
val SlimNavBarHeight = 64.dp
val MiniPlayerHeight = 64.dp
val MiniPlayerBottomSpacing = 8.dp // Space between MiniPlayer and NavigationBar
val QueuePeekHeight = 64.dp
val AppBarHeight = 64.dp

val ListItemHeight = 64.dp
val SuggestionItemHeight = 56.dp
val SearchFilterHeight = 48.dp
val ListThumbnailSize = 48.dp
val SmallGridThumbnailHeight = 104.dp
val GridThumbnailHeight = 128.dp
val AlbumThumbnailSize = 144.dp

val ThumbnailCornerRadius = 6.dp
val GridThumbnailCornerRadius = 8.dp

val PlayerHorizontalPadding = 32.dp

/*
 * How the navigation bar leaves and returns.
 *
 * A tween, not a spring, and that is the point. A spring approaches its target asymptotically: the
 * last fraction of the travel is spent creeping, which is both invisible and never quite finished,
 * and code that waits for the value to reach its resting point exactly is waiting on a limit. A
 * finite curve arrives, and arrives when it says it will.
 *
 * The curve is the one the destination entrances use — eased in slightly so it does not leave from a
 * standing start at full speed, with a long tail so the end of the movement can be seen happening
 * rather than simply stopping. The duration is chosen to match what the old spring took to become
 * visually settled, so nothing about the bar feels slower than it did.
 */
val NavigationBarAnimationMillis = 400
val NavigationBarAnimationSpec: AnimationSpec<Float> =
    tween(
        durationMillis = NavigationBarAnimationMillis,
        easing = CubicBezierEasing(0.2f, 0.05f, 0.35f, 1f),
    )

/*
 * The outer anchors are hard Animatable bounds, so expansion/dismissal remain critically damped.
 * Large surfaces should read as mass, not as a translated card.
 */
val BottomSheetAnimationSpec = spring<Dp>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = 225f,
)

/*
 * How the player opens and closes when it is tapped rather than dragged.
 *
 * These were springs, and a spring is what made the movement start with a jolt. A critically damped
 * spring leaves rest at zero speed but at *maximum acceleration* — the force is largest at the very
 * first frame and decays from there. The eye does not read acceleration, it reads the change in it,
 * and that change is instantaneous at t=0. However smooth the rest of the travel is, the departure
 * snaps.
 *
 * A tween on a curve that eases in has no such instant: the first frames spend almost no distance,
 * so the player leaves the dock softly, commits through the middle, and settles at the end. It also
 * arrives, where a spring only approaches.
 *
 * Dragging is untouched, and deliberately. A fling already carries the finger's velocity into the
 * animation, and a spring continuing that velocity is exactly right there — there is no standing
 * start to soften.
 */
val PlayerTapTravelMillis = 460
/*
 * Softer off the mark than anything else in the app, because this surface is the largest.
 *
 * A jolt at the start scales with how much is moving, and the player moves the whole screen. Where a
 * destination entrance can afford to commit quickly, the sheet has to lean into the movement first —
 * (0.44, 0) spends almost nothing in the opening frames, which is what removes the shove.
 */
private val PlayerTapEasing = CubicBezierEasing(0.44f, 0f, 0.26f, 1f)

val BottomSheetSoftAnimationSpec: AnimationSpec<Dp> =
    tween(durationMillis = PlayerTapTravelMillis, easing = PlayerTapEasing)

/*
 * Collapse after a drag still gets a little physical follow-through, but the old 0.78 damping was
 * visibly rubbery next to the dock. These values keep the impact while preventing the
 * mini-player/nav seam from overshooting and looking broken.
 */
val BottomSheetCollapseAnimationSpec = spring<Dp>(
    dampingRatio = 0.86f,
    stiffness = 188f,
)

/*
 * A tapped close, like a tapped open: soft departure, and no dip past the dock.
 *
 * This one was under-damped, so the sheet went slightly below the dock and came back. That reads as
 * weight when it is driven by a finger, and as a wobble when it is driven by a tap out of nowhere.
 */
val BottomSheetSoftCollapseAnimationSpec: AnimationSpec<Dp> =
    tween(durationMillis = PlayerTapTravelMillis, easing = PlayerTapEasing)
