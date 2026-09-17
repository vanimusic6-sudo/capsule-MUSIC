package com.nikhil.yt.ui.screens

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Character of a destination entrance.
 *
 * Route transitions remain disabled: only the incoming destination may animate. This avoids keeping
 * two complete screens alive at once and removes the destroyed-NavBackStackEntry race that timed
 * route transitions caused during rapid navigation.
 */
internal enum class DestinationMotion {
    /** Frequent lateral navigation. */
    Tab,

    /**
     * Artwork-heavy detail pages.
     *
     * These intentionally have no entrance transform. Scale and translation were both tested here
     * and made full-bleed artwork seams crawl; alpha/fade fixed that visually but required a
     * full-screen blend and violates Capsule's opaque-motion rule. A clean one-frame arrival is less
     * distracting and cheaper than either compromise.
     */
    Detail,

    /** Movement inside the settings tree. */
    Settings,

    /** Crossing into or out of the settings area. */
    Section,
}

internal data class DestinationMotionSpec(
    val durationMillis: Int,
    val backwardDurationMillis: Int = durationMillis,
    val easing: Easing,
    val lift: Dp = 0.dp,
    val shift: Dp = 0.dp,
    val overscale: Float = 0f,
)

/*
 * All curves accelerate gently from rest and keep a visible tail near the end. A steep pure
 * decelerate feels responsive at first but spends nearly all of its travel immediately and then
 * appears to stop dead. These curves distribute the same short movement over the whole duration.
 */
private val TabSpec =
    DestinationMotionSpec(
        durationMillis = 380,
        easing = CubicBezierEasing(0.2f, 0.05f, 0.35f, 1f),
        lift = 16.dp,
    )

private val SettingsSpec =
    DestinationMotionSpec(
        durationMillis = 440,
        backwardDurationMillis = 370,
        easing = CubicBezierEasing(0.38f, 0.02f, 0.3f, 1f),
        shift = 30.dp,
    )

private val SectionSpec =
    DestinationMotionSpec(
        durationMillis = 500,
        easing = CubicBezierEasing(0.42f, 0f, 0.28f, 1f),
        overscale = 0.045f,
    )

internal fun destinationMotionFor(
    route: String?,
    from: String? = null,
): DestinationMotion {
    if (route == null) return DestinationMotion.Detail

    val arriving = route.isInSettings()
    val leaving = from?.isInSettings() == true

    return when {
        arriving != leaving -> DestinationMotion.Section
        arriving -> DestinationMotion.Settings
        Screens.MainScreens.any { it.route == route } -> DestinationMotion.Tab
        else -> DestinationMotion.Detail
    }
}

private fun String.isInSettings(): Boolean =
    this == "settings" || startsWith("settings/")

/**
 * Null is deliberate for artwork-heavy detail screens: their previous fade was the only animation
 * in this file that needed an offscreen blend. Returning null also means no temporary RenderNode is
 * created for a screen whose safest motion is no motion.
 */
internal fun DestinationMotion.spec(): DestinationMotionSpec? =
    when (this) {
        DestinationMotion.Tab -> TabSpec
        DestinationMotion.Detail -> null
        DestinationMotion.Settings -> SettingsSpec
        DestinationMotion.Section -> SectionSpec
    }

/**
 * Plays one finite geometry-only entrance. No alpha, blur or second destination is involved, and
 * the layer is removed as soon as it reaches identity so an idle screen carries no animation cost.
 */
@Composable
internal fun Modifier.destinationEntrance(
    motion: DestinationMotion,
    direction: RouteDirection = RouteDirection.Forward,
): Modifier {
    if (!systemAnimationsEnabled()) return this

    val spec = motion.spec() ?: return this
    val durationMillis =
        when (direction) {
            RouteDirection.Forward -> spec.durationMillis
            RouteDirection.Backward -> spec.backwardDurationMillis
        }

    val progress = remember(spec, direction) { Animatable(0f) }
    var running by remember(spec, direction) { mutableStateOf(true) }

    LaunchedEffect(spec, direction) {
        progress.snapTo(0f)
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = durationMillis, easing = spec.easing),
        )
        running = false
    }

    if (!running) return this

    val density = LocalDensity.current
    val liftPx = with(density) { spec.lift.toPx() }
    val shiftPx = with(density) { spec.shift.toPx() * direction.sign }

    return this.graphicsLayer {
        val settled =
            progress.value.let {
                if (it.isFinite()) it.coerceIn(0f, 1f) else 1f
            }
        val remaining = 1f - settled

        translationX = remaining * shiftPx
        translationY = remaining * liftPx
        if (spec.overscale != 0f) {
            scaleX = 1f + spec.overscale * remaining
            scaleY = 1f + spec.overscale * remaining
        }
    }
}

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

internal enum class RouteDirection(
    val sign: Float,
) {
    Forward(1f),
    Backward(-1f),
}

internal fun routeDirection(
    from: String?,
    to: String,
): RouteDirection =
    if (
        from != null &&
            from.length > to.length &&
            from.startsWith(to) &&
            from[to.length] == '/'
    ) {
        RouteDirection.Backward
    } else {
        RouteDirection.Forward
    }

internal data class RouteArrival(
    val route: String,
    val from: String?,
    val direction: RouteDirection,
)

internal class RouteHistory {
    private var previousRoute: String? = null

    fun enter(route: String): RouteArrival {
        val arrival =
            RouteArrival(
                route = route,
                from = previousRoute,
                direction = routeDirection(previousRoute, route),
            )
        previousRoute = route
        return arrival
    }

    fun clear() {
        previousRoute = null
    }
}
