/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */

package com.nikhil.yt.constants

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.IntOffset
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

/* Navigation chrome should settle softly and never snap into the mini-player. */
val NavigationBarAnimationSpec = spring<Dp>(
    dampingRatio = 0.92f,
    stiffness = 125f,
)

/*
 * The outer anchors are hard Animatable bounds, so expansion/dismissal remain critically damped.
 * Large surfaces should read as mass, not as a translated card.
 */
val BottomSheetAnimationSpec = spring<Dp>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = 225f,
)

val BottomSheetSoftAnimationSpec = spring<Dp>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = 158f,
)

/*
 * Collapse still gets a little physical follow-through, but the old 0.78 damping was visibly rubbery
 * next to the dock. These values keep the impact while preventing the mini-player/nav seam from
 * overshooting and looking broken.
 */
val BottomSheetCollapseAnimationSpec = spring<Dp>(
    dampingRatio = 0.86f,
    stiffness = 188f,
)

val BottomSheetSoftCollapseAnimationSpec = spring<Dp>(
    dampingRatio = 0.89f,
    stiffness = 152f,
)

/*
 * Destinations settle into place instead of arriving from somewhere.
 *
 * Route motion stays None — no screen ever slides over another — so this is the only entrance
 * movement in the app. It is a decelerating tween rather than a spring on purpose: a spring leaves
 * the target at speed and then creeps into it, which reads as a snap followed by a tail. A curve
 * that only decelerates arrives soft, lands exactly on time, and cannot bounce.
 *
 * It runs once per destination entrance and then the animation coroutine completes; nothing keeps
 * ticking.
 */
const val DestinationEntranceDurationMillis = 300

/** Emphasized decelerate: quick to commit, long and soft to settle. Never overshoots. */
val DestinationEntranceEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

val DestinationEntranceSpec: FiniteAnimationSpec<Float> =
    tween(durationMillis = DestinationEntranceDurationMillis, easing = DestinationEntranceEasing)

/** Travel of the entrance lift. Small enough to read as settling, not as sliding. */
val DestinationEntranceOffset = 10.dp

/**
 * Entrance opacity floor. The destination owns an opaque canvas underneath this layer, so the fade
 * dissolves out of its own background rather than revealing the previous route. The floor keeps a
 * tab switch from starting on a bare flat colour.
 */
const val DestinationEntranceMinAlpha = 0.15f

/*
 * Item motion for library-style lists and grids.
 *
 * Entering a destination has to read as one canvas settling, not as a grid of tiles arriving
 * separately. Placement stays animated so a genuine reorder or removal still moves, but calmly, at
 * the same pace as the destination entrance.
 */
val CalmItemPlacementSpec: FiniteAnimationSpec<IntOffset> =
    tween(durationMillis = DestinationEntranceDurationMillis, easing = DestinationEntranceEasing)

val CalmItemFadeOutSpec: FiniteAnimationSpec<Float> =
    tween(durationMillis = 180, easing = DestinationEntranceEasing)
