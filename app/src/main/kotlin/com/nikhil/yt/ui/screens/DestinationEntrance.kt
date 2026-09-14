package com.nikhil.yt.ui.screens

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How a destination arrives.
 *
 * Route transitions themselves stay at None, so two destinations are never composed together and
 * navigation keeps costing one screen. The character lives inside the destination instead: the
 * incoming screen plays a short entrance on its own layer, which is draw-only work on a layer that
 * already exists.
 *
 * The two characters exist because the two kinds of navigation mean different things. Moving
 * between tabs is lateral and habitual — it should feel quick and slightly kinetic, not ceremonial.
 * Opening an artist, an album or a settings page is going *into* something, and wants to resolve
 * softly so the swap does not land as a hard cut.
 */
internal enum class DestinationMotion {
    /** Lateral, habitual, frequent. Brisk, with a little travel so it reads as momentum. */
    Tab,

    /** Going into something. Settles out of a slightly oversized state rather than snapping in. */
    Detail,

    /**
     * Moving between pages of one structure.
     *
     * Settings is a tree, not a series of places you open, so it moves sideways: the new page comes
     * in from the edge you are heading towards. Scaling suited it badly — it made each page look
     * like something being presented rather than the next step along a path.
     *
     * It is the one motion that is directional. Going deeper, the page arrives from the trailing
     * edge; stepping back out, the page you return to arrives from the leading edge instead. That
     * is what carries the sense of leaving a settings page, and it carries it without an exit
     * transition — which would mean two screens on screen at once, one showing through the other.
     */
    Settings,
}

internal data class DestinationMotionSpec(
    val durationMillis: Int,
    val easing: Easing,
    /** Distance the content rises through. Zero for motion that resolves by scale instead. */
    val lift: Dp,
    /** Distance the content travels in from the trailing edge. Zero for motion that does not. */
    val shift: Dp = 0.dp,
    /** Size the content resolves down from. 0f keeps it at its true size throughout. */
    val overscale: Float,
    /**
     * Opacity the content starts at.
     *
     * Kept high, and for a reason that is easy to get wrong. Route transitions are None, so the
     * screen being left disappears at once; if the arriving screen started faint, those first
     * frames would show neither screen properly and the change would land as a flash of bare
     * canvas. That flash is what reads as a flicker, and as harshness. The character has to come
     * from the movement, not from fading up out of nothing.
     *
     * It also means a stalled animation would leave a readable screen rather than a blank one.
     */
    val fromAlpha: Float,
)

/*
 * Every curve eases in a little before it decelerates.
 *
 * A pure decelerate leaves at full speed from a standing start, and that instant is what reads as
 * hard however short the animation is. Giving the first few percent somewhere to accelerate from
 * removes the edge without making anything feel slower to respond — the screen still commits
 * immediately, it just stops snapping.
 *
 * The tab curve also gets a long tail on purpose. A steep decelerate puts almost all of the travel
 * into the first quarter of the duration, and what is left is too small and too brief to be seen
 * finishing: the screen appears to jump into place and then stop, which reads as a flick at the end
 * rather than an arrival. Pulling the second control point back out (0.35 instead of 0.08) spreads
 * the travel far more evenly — a quarter of the distance is still to come at the halfway point, and
 * a tenth of it at two thirds — so the screen is visibly still settling when it stops. That, plus
 * the longer duration, is the difference between coming to rest and being cut off.
 */
private val TabSpec =
    DestinationMotionSpec(
        durationMillis = 380,
        easing = CubicBezierEasing(0.2f, 0.05f, 0.35f, 1f),
        lift = 16.dp,
        overscale = 0f,
        fromAlpha = 0.82f,
    )

private val DetailSpec =
    DestinationMotionSpec(
        durationMillis = 400,
        easing = CubicBezierEasing(0.3f, 0.06f, 0.05f, 1f),
        lift = 0.dp,
        overscale = 0.028f,
        fromAlpha = 0.86f,
    )

private val SettingsSpec =
    DestinationMotionSpec(
        durationMillis = 340,
        easing = CubicBezierEasing(0.3f, 0.06f, 0.05f, 1f),
        lift = 0.dp,
        shift = 30.dp,
        overscale = 0f,
        fromAlpha = 0.86f,
    )

/**
 * Tabs are the four bottom-bar destinations. Everything else is something the user opened.
 *
 * Note what is deliberately absent: a real blur. It would suit the detail entrance, but a
 * `RenderEffect` forces an offscreen buffer for the whole screen and allocating it the first time
 * is what made opening the player stall on device. Resolving down from a slightly oversized state
 * reads soft for the same reason a blur does — the edges are not where they will end up — and costs
 * nothing beyond the layer that already exists.
 */
internal fun destinationMotionFor(route: String?): DestinationMotion =
    when {
        route == null -> DestinationMotion.Detail
        Screens.MainScreens.any { it.route == route } -> DestinationMotion.Tab
        route.startsWith("settings") -> DestinationMotion.Settings
        else -> DestinationMotion.Detail
    }

internal fun DestinationMotion.spec(): DestinationMotionSpec =
    when (this) {
        DestinationMotion.Tab -> TabSpec
        DestinationMotion.Detail -> DetailSpec
        DestinationMotion.Settings -> SettingsSpec
    }

/**
 * Plays a destination's entrance once.
 *
 * Draw-phase only: no measurement changes, so this cannot disturb a screen's layout, its scrolling
 * or its neighbours. The animation is a finite tween that completes and leaves nothing running.
 */
@Composable
internal fun Modifier.destinationEntrance(
    motion: DestinationMotion,
    direction: RouteDirection = RouteDirection.Forward,
): Modifier {
    if (!systemAnimationsEnabled()) return this

    val spec = motion.spec()
    val progress = remember(spec) { Animatable(0f) }
    LaunchedEffect(spec) {
        progress.animateTo(1f, tween(spec.durationMillis, easing = spec.easing))
    }

    val density = LocalDensity.current
    val liftPx = with(density) { spec.lift.toPx() }
    val shiftPx = with(density) { spec.shift.toPx() * direction.sign }

    return graphicsLayer {
        // The tween has already applied the easing; clamping here guards only against a value
        // arriving out of range, which a cancelled or restored animation can produce.
        val settled = progress.value.let { if (it.isFinite()) it.coerceIn(0f, 1f) else 1f }
        val remaining = 1f - settled

        alpha = spec.fromAlpha + (1f - spec.fromAlpha) * settled
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

/**
 * Which way along a route tree a destination was reached.
 *
 * Only lateral motion uses it: the sign of the edge a page travels in from.
 */
internal enum class RouteDirection(val sign: Float) {
    /** Deeper in, or somewhere new. The page arrives from the trailing edge. */
    Forward(1f),

    /** Back out towards the root. The page arrives from the leading edge it left by. */
    Backward(-1f),
}

/**
 * Backward when [to] is an ancestor of [from] in a slash-separated route tree.
 *
 * Deliberately narrow. It answers "did we step back out of a page that lives under this one", which
 * is the only case with a direction to it; a sideways move between two pages at the same depth, or
 * anything outside the tree, is an arrival and reads as Forward. The boundary check matters: without
 * it "settings_backup" would count as being inside "settings".
 */
internal fun routeDirection(from: String?, to: String): RouteDirection =
    if (from != null &&
        from.length > to.length &&
        from.startsWith(to) &&
        from[to.length] == '/'
    ) {
        RouteDirection.Backward
    } else {
        RouteDirection.Forward
    }

/**
 * Remembers which route was composed last, so the next one knows which way it was reached.
 *
 * NavHost composes one destination at a time here — route transitions are None, so the outgoing
 * screen is gone within a frame — which makes "the route before this one" a well defined thing to
 * record. Composition runs on the main thread, so no synchronisation is involved.
 *
 * A stale value cannot do damage: the worst outcome is a single settings page travelling in from
 * the wrong edge once, after which the history is correct again.
 */
internal class RouteHistory {
    private var previousRoute: String? = null

    fun enter(route: String): RouteDirection {
        val direction = routeDirection(previousRoute, route)
        previousRoute = route
        return direction
    }
}
