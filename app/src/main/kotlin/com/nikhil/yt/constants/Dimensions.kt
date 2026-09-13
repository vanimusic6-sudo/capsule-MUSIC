/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */

package com.nikhil.yt.constants

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

val NavigationBarAnimationSpec = spring<Dp>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessLow,
)

/*
 * Drag release should feel weighty, but must still follow the user's velocity. A tiny amount of
 * under-damping gives the sheet a soft magnetic dock instead of an abrupt stop.
 */
val BottomSheetAnimationSpec = spring<Dp>(
    dampingRatio = 0.90f,
    stiffness = 360f,
)

/*
 * Programmatic open/close uses a zero-velocity start and a long, dense settle. The curve begins
 * gently, gains speed in the middle, then spends the final part of the travel docking into place.
 * There is no alpha animation: all softness comes from real movement.
 */
private val CapsuleSheetEasing = CubicBezierEasing(0.24f, 0f, 0.05f, 1f)

val BottomSheetSoftAnimationSpec = tween<Dp>(
    durationMillis = 560,
    easing = CapsuleSheetEasing,
)
