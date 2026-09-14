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
