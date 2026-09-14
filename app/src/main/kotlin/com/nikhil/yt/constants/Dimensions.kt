/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */

package com.nikhil.yt.constants

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
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
 * Route motion stays None on purpose — no screen ever slides over another — so this is the only
 * entrance movement in the app: content lifts the last few dp and fades in the last of its opacity.
 * Critically damped, so it never overshoots and never reads as a page transition. It runs once per
 * destination entrance and then the animation coroutine completes; nothing keeps ticking.
 */
val DestinationEntranceSpec = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = 800f,
)

/** Travel of the entrance lift. Small enough to read as settling, not as sliding. */
val DestinationEntranceOffset = 6.dp

/**
 * Entrance opacity floor. Starting from a high alpha rather than from zero keeps a tab switch from
 * flashing an empty canvas, which matters because the outgoing destination disappears instantly.
 */
const val DestinationEntranceMinAlpha = 0.7f
