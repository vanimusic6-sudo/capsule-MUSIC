package com.nikhil.yt.ui.screens

import android.provider.Settings
import androidx.compose.animation.core.Animatable
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
import com.nikhil.yt.ui.motion.CapsuleEnterEasing

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
     * Artwork-heavy detail pages: an artist, an album, a playlist.
     *
     * These are the screens where a transform cannot be used, and the reason is worth keeping. The
     * entrance layer sits inside the destination's own opaque canvas, so moving or shrinking the
     * layer uncovers a band of plain surface colour at its edge. Against full-bleed artwork that
     * band is plainly visible, which is what "the bottom edge of the artist card comes apart" was.
     * Scale has the same problem and resamples the artwork on top of it.
     *
     * What is left is a fade, and that is what these screens get. It moves nothing, so there is no
     * edge to come apart, and it blends against this destination's own canvas rather than against
     * the previous screen — no second destination is ever composed underneath.
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
    /** How much opacity the entrance starts short of solid. Geometry entrances leave this at 0. */
    val fade: Float = 0f,
)

/*
 * One curve for all of them, and it is the app's own arrival curve.
 *
 * Four hand-tuned beziers lived here, each close to the others and none of them measured. They all
 * started from rest and ended at rest, which is the easy half, and all of them paid for it with a
 * fast middle — the half that is actually felt. The shared curve is measured: it never exceeds 1.25
 * times its own average speed, so a screen arrives at a pace instead of lunging into place.
 *
 * The durations are longer than they were. A soft curve cannot rescue a movement that is over
 * before it has been watched, and these were between a third and a half of a second for travel the
 * eye is meant to follow.
 */
private val TabSpec =
    DestinationMotionSpec(
        durationMillis = 460,
        easing = CapsuleEnterEasing,
        lift = 16.dp,
    )

private val SettingsSpec =
    DestinationMotionSpec(
        durationMillis = 520,
        backwardDurationMillis = 450,
        easing = CapsuleEnterEasing,
        shift = 30.dp,
    )

/*
 * Shallow and brief. Starting from nothing would read as the screen flashing rather than arriving,
 * so a little over half of the opacity is already there on the first frame and the rest resolves
 * inside a fifth of a second.
 */
private val DetailSpec =
    DestinationMotionSpec(
        durationMillis = 280,
        easing = CapsuleEnterEasing,
        fade = 0.45f,
    )

private val SectionSpec =
    DestinationMotionSpec(
        durationMillis = 580,
        easing = CapsuleEnterEasing,
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

internal fun DestinationMotion.spec(): DestinationMotionSpec =
    when (this) {
        DestinationMotion.Tab -> TabSpec
        DestinationMotion.Detail -> DetailSpec
        DestinationMotion.Settings -> SettingsSpec
        DestinationMotion.Section -> SectionSpec
    }

/**
 * Plays one finite entrance for the destination that is arriving.
 *
 * No blur, and no second destination: the screen being left is never composed underneath. The layer
 * is dropped the moment it reaches identity, so an idle screen carries no animation cost and no
 * residual transform.
 */
@Composable
internal fun Modifier.destinationEntrance(
    motion: DestinationMotion,
    direction: RouteDirection = RouteDirection.Forward,
): Modifier {
    if (!systemAnimationsEnabled()) return this

    val spec = motion.spec()
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
        if (spec.fade != 0f) {
            alpha = (1f - spec.fade * remaining).coerceIn(0f, 1f)
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
