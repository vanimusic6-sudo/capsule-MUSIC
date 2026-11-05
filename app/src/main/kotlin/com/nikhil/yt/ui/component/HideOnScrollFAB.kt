/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */



package com.nikhil.yt.ui.component

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import com.nikhil.yt.R
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.nikhil.yt.LocalPlayerAwareWindowInsets
import com.nikhil.yt.ui.utils.isScrollingUp
import com.nikhil.yt.ui.motion.CapsuleExitEasing
import com.nikhil.yt.ui.motion.CapsuleShortestVisible
import com.nikhil.yt.ui.motion.CapsuleEnterEasing
import kotlinx.coroutines.flow.collect
import kotlin.math.abs

private val ScrollActionEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

@Composable
fun BoxScope.HideOnScrollFAB(
    visible: Boolean = true,
    lazyListState: LazyListState,
    @DrawableRes icon: Int,
    onClick: () -> Unit,
) {
    ScrollActionButton(visible && lazyListState.isScrollingUp(), icon, onClick)
}

@Composable
fun BoxScope.HideOnScrollFAB(
    visible: Boolean = true,
    lazyListState: LazyGridState,
    @DrawableRes icon: Int,
    onClick: () -> Unit,
) {
    ScrollActionButton(visible && lazyListState.isScrollingUp(), icon, onClick)
}

@Composable
fun BoxScope.HideOnScrollFAB(
    visible: Boolean = true,
    scrollState: ScrollState,
    @DrawableRes icon: Int,
    onClick: () -> Unit,
) {
    ScrollActionButton(visible && scrollState.isScrollingUp(), icon, onClick)
}

@Composable
internal fun BoxScope.ScrollActionButton(
    visible: Boolean,
    @DrawableRes icon: Int,
    onClick: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(200, easing = CapsuleEnterEasing)) +
            slideInVertically(tween(280, easing = ScrollActionEasing)) { it / 6 } +
            scaleIn(tween(280, easing = ScrollActionEasing), initialScale = 0.94f, transformOrigin = TransformOrigin(0.5f, 1f)),
        // Fade away during a small downward drift, before reaching the navigation bar.
        exit = fadeOut(tween(CapsuleShortestVisible, easing = CapsuleExitEasing)) +
            slideOutVertically(tween(220, easing = ScrollActionEasing)) { it / 6 } +
            scaleOut(tween(220, easing = ScrollActionEasing), targetScale = 0.94f, transformOrigin = TransformOrigin(0.5f, 1f)),
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
            ),
    ) {
        FloatingActionButton(
            modifier = Modifier.padding(16.dp).semantics { if (!visible) disabled() },
            onClick = { if (visible) onClick() },
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = when (icon) {
                    R.drawable.shuffle -> stringResource(R.string.shuffle)
                    R.drawable.library_add -> stringResource(R.string.action_like)
                    R.drawable.library_add_check -> stringResource(R.string.action_remove_like)
                    R.drawable.add -> stringResource(R.string.create_playlist)
                    else -> null
                },
            )
        }
    }
}


/**
 * Accumulate small scroll moves into one direction decision. Reversing
 * by a pixel must not bounce the floating button on/off.
 */
internal class ScrollFabDirectionTracker(private val thresholdPx: Float) {
    private var lastIndex: Int? = null
    private var lastOffset = 0
    private var cumulativeDelta = 0f
    var isVisible: Boolean = true
        private set

    fun sample(scrolling: Boolean, index: Int, offset: Int): Boolean {
        val previous = lastIndex
        val delta = when {
            previous == null -> 0f
            index > previous -> thresholdPx + 1f
            index < previous -> -thresholdPx - 1f
            else -> (offset - lastOffset).toFloat()
        }
        lastIndex = index
        lastOffset = offset
        if (!scrolling) {
            cumulativeDelta = 0f
            isVisible = true
        } else if (delta != 0f) {
            if (delta * cumulativeDelta < 0f) cumulativeDelta = 0f
            cumulativeDelta += delta
            if (abs(cumulativeDelta) >= thresholdPx.coerceAtLeast(1f)) {
                isVisible = cumulativeDelta < 0f
                cumulativeDelta = 0f
            }
        }
        return isVisible
    }
}

@Composable
private fun rememberScrollFabVisible(
    list: LazyListState?,
    grid: LazyGridState?,
): Boolean {
    if (list == null && grid == null) return true
    val threshold = with(LocalDensity.current) { 14.dp.toPx() }
    var visible by remember(list, grid) { mutableStateOf(true) }
    LaunchedEffect(list, grid, threshold) {
        val tracker = ScrollFabDirectionTracker(threshold)
        snapshotFlow {
            if (list != null) {
                Triple(list.isScrollInProgress, list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset)
            } else {
                Triple(grid!!.isScrollInProgress, grid.firstVisibleItemIndex, grid.firstVisibleItemScrollOffset)
            }
        }.collect { (scrolling, index, offset) ->
            val next = tracker.sample(scrolling, index, offset)
            if (next != visible) visible = next
        }
    }
    return visible
}

/**
 * One playlist shortcut per screen, including the default mixed Library.
 * Scroll down: descend, shrink, and fade; scroll up / stop: gently return.
 * The button stays above the mini-player through player-aware insets.
 */
@Composable
internal fun BoxScope.PersistentPlaylistAction(
    visible: Boolean,
    @DrawableRes icon: Int,
    onClick: () -> Unit,
    lazyListState: LazyListState? = null,
    lazyGridState: LazyGridState? = null,
) {
    val scrollVisible = rememberScrollFabVisible(lazyListState, lazyGridState)
    // A modal/selection/hard hide removes the click target immediately.
    if (!visible) return
    AnimatedVisibility(
        visible = scrollVisible,
        enter = fadeIn(tween(230, easing = CapsuleEnterEasing)) +
            slideInVertically(tween(290, easing = ScrollActionEasing)) { it / 3 } +
            scaleIn(tween(290, easing = ScrollActionEasing),
                initialScale = 0.92f, transformOrigin = TransformOrigin(0.5f, 1f)),
        exit = fadeOut(tween(180, easing = CapsuleExitEasing)) +
            slideOutVertically(tween(240, easing = ScrollActionEasing)) { it / 3 } +
            scaleOut(tween(240, easing = ScrollActionEasing),
                targetScale = 0.9f, transformOrigin = TransformOrigin(0.5f, 1f)),
        modifier = Modifier.align(Alignment.BottomEnd)
            .windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(
                    WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal,
                ),
            ),
    ) {
        FloatingActionButton(
            onClick = { if (scrollVisible) onClick() },
            modifier = Modifier
                .padding(end = 18.dp, bottom = 18.dp)
                .semantics { if (!scrollVisible) disabled() }
                .testTag("capsule:playlist-floating-action"),
            shape = RoundedCornerShape(18.dp),
            containerColor = StandardChrome.selected,
            contentColor = StandardChrome.text,
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = when (icon) {
                    R.drawable.library_add -> stringResource(R.string.action_like)
                    R.drawable.library_add_check -> stringResource(R.string.action_remove_like)
                    else -> stringResource(R.string.create_playlist)
                },
            )
        }
    }
}
