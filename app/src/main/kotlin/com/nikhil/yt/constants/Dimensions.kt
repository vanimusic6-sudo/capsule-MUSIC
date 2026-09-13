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

/*
 * Navigation is structural chrome, not a toy spring. Keep it slower and almost critically damped so
 * showing/hiding the dock feels like one soft piece of hardware rather than a bar snapping into place.
 */
val NavigationBarAnimationSpec = spring<Dp>(
    dampingRatio = 0.90f,
    stiffness = 130f,
)

/*
 * The outer anchors are hard Animatable bounds, so expansion/dismissal remain critically damped.
 * Lower stiffness makes the large surface read as mass instead of a fast translate. The interior
 * collapse anchor has room for a small safe overshoot and therefore keeps the sticky dock impact.
 */
val BottomSheetAnimationSpec = spring<Dp>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = 235f,
)

val BottomSheetSoftAnimationSpec = spring<Dp>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = 165f,
)

val BottomSheetCollapseAnimationSpec = spring<Dp>(
    dampingRatio = 0.78f,
    stiffness = 215f,
)

val BottomSheetSoftCollapseAnimationSpec = spring<Dp>(
    dampingRatio = 0.80f,
    stiffness = 175f,
)
