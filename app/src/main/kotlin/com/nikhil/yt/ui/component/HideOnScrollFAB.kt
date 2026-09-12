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
import androidx.compose.ui.Alignment
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
        enter = fadeIn(tween(200)) +
            slideInVertically(tween(280, easing = ScrollActionEasing)) { it / 6 } +
            scaleIn(tween(280, easing = ScrollActionEasing), initialScale = 0.94f, transformOrigin = TransformOrigin(0.5f, 1f)),
        // Fade away during a small downward drift, before reaching the navigation bar.
        exit = fadeOut(tween(180)) +
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
                contentDescription = if (icon == R.drawable.shuffle) stringResource(R.string.shuffle) else null,
            )
        }
    }
}
