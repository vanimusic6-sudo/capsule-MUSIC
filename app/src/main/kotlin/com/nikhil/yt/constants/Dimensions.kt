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
 * Capsule motion intentionally uses a slightly under-damped spring instead of a tween. A tween can
 * be smooth, but it still reads as a programmed interpolation. The spring carries momentum into
 * the target and then settles by a few pixels, which is the same tactile language used by the
 * favourite-heart press animation.
 */
val NavigationBarAnimationSpec = spring<Dp>(
    dampingRatio = 0.86f,
    stiffness = 380f,
)

/*
 * Releases stay denser and quicker than deliberate open/close actions. There is enough damping to
 * avoid a visible bounce, but not so much that the sheet loses its sense of mass when it docks.
 */
val BottomSheetAnimationSpec = spring<Dp>(
    dampingRatio = 0.86f,
    stiffness = 420f,
)

/*
 * Programmatic player/queue motion is softer. The tiny controlled overshoot is intentional: it is
 * perceived as magnetic settling rather than a bounce, especially together with the secondary
 * content-lag deformation in BottomSheet.
 */
val BottomSheetSoftAnimationSpec = spring<Dp>(
    dampingRatio = 0.82f,
    stiffness = 280f,
)
