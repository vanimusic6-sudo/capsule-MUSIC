package com.nikhil.yt.ui.screens

import android.provider.Settings
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDeepLink
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.nikhil.yt.constants.DestinationEntranceMinAlpha
import com.nikhil.yt.constants.DestinationEntranceOffset
import com.nikhil.yt.constants.DestinationEntranceSpec

/**
 * Registers a destination whose content settles in when the destination is entered.
 *
 * Every destination goes through here so the app has exactly one entrance behaviour instead of a
 * per-screen collection of them. The wrapper is layout-neutral: a single-child [Box] with
 * `propagateMinConstraints` measures the screen exactly as `NavHost` would have, so no screen's
 * sizing or alignment changes.
 */
internal fun NavGraphBuilder.destinationComposable(
    route: String,
    arguments: List<NamedNavArgument> = emptyList(),
    deepLinks: List<NavDeepLink> = emptyList(),
    content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit,
) = composable(route, arguments, deepLinks) { entry ->
    val contentScope = this
    Box(modifier = Modifier.destinationEntrance(), propagateMinConstraints = true) {
        with(contentScope) { content(entry) }
    }
}

/**
 * The entrance itself: a short critically damped lift and fade, played once.
 *
 * Deliberate properties:
 * - It is driven from a `graphicsLayer` lambda, so each frame only invalidates the draw phase. The
 *   destination is never recomposed or re-laid-out by the animation.
 * - The [LaunchedEffect] is finite. It animates once, completes, and leaves no timer, no loop and
 *   no clock running behind a screen the user has moved away from.
 * - It animates the destination container only, so nothing inside — a like or subscribe morph, the
 *   artist hero, artwork — is remounted or replayed by it.
 * - It respects the system "remove animations" setting, which Compose springs otherwise ignore.
 */
@Composable
internal fun Modifier.destinationEntrance(): Modifier {
    if (!systemAnimationsEnabled()) return this

    val liftPx = with(LocalDensity.current) { DestinationEntranceOffset.toPx() }
    val progress = rememberDestinationEntranceProgress()

    return graphicsLayer {
        alpha = destinationEntranceAlpha(progress.value)
        translationY = destinationEntranceLift(progress.value, liftPx)
    }
}

/** Drives one entrance from 0 to 1 and then completes. Split out so the curve is testable. */
@Composable
internal fun rememberDestinationEntranceProgress(): State<Float> {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, DestinationEntranceSpec) }
    return progress.asState()
}

/**
 * Opacity for a given entrance progress, clamped.
 *
 * The spec is critically damped so progress cannot overshoot, but every value that reaches a
 * `graphicsLayer` is clamped regardless: an out-of-range alpha, or a non-finite one produced by a
 * cancelled or restored animation, is exactly the sort of value newer Android builds reject.
 */
internal fun destinationEntranceAlpha(progress: Float): Float {
    val settled = settledFraction(progress)
    return DestinationEntranceMinAlpha + (1f - DestinationEntranceMinAlpha) * settled
}

/** Remaining lift for a given entrance progress. Never negative, so the content cannot overshoot. */
internal fun destinationEntranceLift(progress: Float, liftPx: Float): Float {
    if (!liftPx.isFinite() || liftPx <= 0f) return 0f
    return (1f - settledFraction(progress)) * liftPx
}

/** A non-finite progress means the animation is not running; treat the destination as settled. */
private fun settledFraction(progress: Float): Float =
    if (progress.isFinite()) progress.coerceIn(0f, 1f) else 1f

@Composable
private fun systemAnimationsEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            )
        }.getOrDefault(1f) > 0f
    }
}
