package com.nikhil.yt.ui.screens.artist

import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.nikhil.yt.R
import com.nikhil.yt.ui.component.StandardChrome
import com.nikhil.yt.ui.motion.CapsuleStandardEasing

/** One reversible clip boundary. Text keeps its full geometry until collapse has completed. */
@Composable
internal fun ArtistDescription(artistId: String, description: String, modifier: Modifier = Modifier,
    onGeometryAnimationChanged: (Boolean) -> Unit = {}) {
    val onGeometryChanged by rememberUpdatedState(onGeometryAnimationChanged)
    DisposableEffect(Unit) { onDispose { onGeometryChanged(false) } }
    var expanded by rememberSaveable(artistId) { mutableStateOf(false) }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = MaterialTheme.typography.bodyMedium
    Column(modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val width = constraints.maxWidth
            val text = remember(measurer, description, style, width) {
                measurer.measure(AnnotatedString(description), style = style, constraints = Constraints(maxWidth = width))
            }
            val fullHeight = with(density) { text.size.height.toDp() }
            val collapsedHeight = with(density) {
                text.getLineBottom((text.lineCount - 1).coerceIn(0, 1)).toDp()
            }
            val transition = updateTransition(expanded, label = "artist-description")
            val height by transition.animateDp(
                transitionSpec = { tween(320, easing = CapsuleStandardEasing) }, label = "clip-height",
            ) { if (it) fullHeight else collapsedHeight }
            val moving = transition.isRunning || transition.currentState != transition.targetState
            LaunchedEffect(moving) { onGeometryChanged(moving) }
            // Do not reflow to maxLines=2 while the full text is still being clipped away.
            val showFullText = transition.isRunning || transition.currentState || transition.targetState
            Box(Modifier.fillMaxWidth().height(height).clipToBounds()) {
                Text(description, modifier = Modifier.wrapContentHeight(Alignment.Top, unbounded = true),
                    style = style, color = StandardChrome.muted,
                    maxLines = if (showFullText) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
            }
        }
        TextButton(onClick = { expanded = !expanded }) {
            Text(stringResource(if (expanded) R.string.show_less else R.string.show_more), color = StandardChrome.text)
        }
    }
}

/** Text height owns this movement; placement animation still handles actual row insertions/reorders. */
internal fun androidx.compose.foundation.lazy.LazyItemScope.artistItemPlacement(geometryAnimating: Boolean): Modifier =
    Modifier.animateItem(placementSpec = if (geometryAnimating) null else androidx.compose.animation.core.spring(
        stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow,
        visibilityThreshold = androidx.compose.ui.unit.IntOffset(1, 1)))
