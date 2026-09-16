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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nikhil.yt.ui.motion.CapsuleMotion

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
     * Moving between pages *inside* one structure.
     *
     * Settings is a tree, and stepping between its pages is following a path, so it moves sideways:
     * the page comes in from the edge you are heading towards. Scaling suited this badly — it made
     * each page look like something being presented rather than the next step along the way.
     *
     * It is the one motion that is directional. Going deeper, the page arrives from the trailing
     * edge; stepping back out, the page you return to arrives from the leading edge instead, and a
     * little quicker, because retracing a step should not take as long as taking it. That is what
     * carries the sense of leaving a settings page, and it carries it without an exit transition —
     * which would mean two screens on screen at once, one showing through the other.
     */
    Settings,

    /**
     * Crossing the boundary of a structure, in either direction.
     *
     * Opening settings is not a step along the settings path — it is arriving at a different part of
     * the app — and leaving settings is not a tab switch, even though it lands on a tab. Both were
     * taking the motion of where they ended up, which is why opening settings slid sideways as if it
     * were already one of its own pages, and why closing settings made the screen behind rise like a
     * library tab.
     *
     * So the boundary gets its own motion, and it is the one used for opening something: a scale.
     * A little larger and a little longer than [Detail], because a whole area of the app is a bigger
     * thing to arrive at than one artist.
     */
    Section,
}

internal data class DestinationMotionSpec(
    val durationMillis: Int,
    /**
     * Duration when the destination is reached by stepping back out of something.
     *
     * Retracing a step should not take as long as taking it: going back is a move you have already
     * seen, and making it wait the full duration reads as the screen being slow to let you leave.
     * Defaults to the forward duration for motions where there is no "back".
     */
    val backwardDurationMillis: Int = durationMillis,
    val easing: Easing,
    /** Distance the content rises through. Zero for motion that resolves by scale instead. */
    val lift: Dp,
    /** Distance the content travels in from the trailing edge. Zero for motion that does not. */
    val shift: Dp = 0.dp,
    /** Size the content resolves down from. 0f keeps it at its true size throughout. */
    val overscale: Float,
    /**
     * How dark the veil over the arriving screen starts, and how much of the duration it takes to
     * lift.
     *
     * This replaces starting the *content* at a reduced opacity, and the difference matters twice
     * over.
     *
     * Visually: route transitions are None, so the screen being left is gone in one frame. A screen
     * that arrives semi-transparent shows the bare canvas through itself for those first frames,
     * which reads as a washed-out flash — the eye catches a change in brightness before it catches
     * anything else. A brief veil that lifts reads instead as deliberate, and it hides the one thing
     * that is genuinely ugly about the first frames: content that is still settling. A keyed grid
     * whose data lands a frame later plays its own item placement animations, and in a grid those
     * move diagonally — which is where the small sideways twitch when opening a library tab comes
     * from. It is not the entrance; it is the grid, and the veil covers it.
     *
     * Structurally: an alpha on the layer multiplies into every draw inside it, so a
     * half-transparent child's colour travels a different curve from its opaque neighbours — the bug
     * that made the favourites cards flash. A veil drawn on top has no such interaction. Content
     * stays at full opacity throughout, and a stalled animation leaves a readable screen rather than
     * a washed one.
     */
    val scrim: Float,
    /** Fraction of the duration the veil takes to lift. Short: it masks a moment, not the motion. */
    val scrimWindow: Float,
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
        scrim = 0.20f,
        scrimWindow = 0.35f,
    )

/*
 * The scale characters share the gentlest lead-in of anything here, and they had the harshest.
 *
 * This one kept the original steep decelerate long after the tabs and settings were moved off it:
 * a twentieth of the duration in and a fifth of the scale was already spent, so opening an artist
 * began with a lurch and then coasted. A scale is far less forgiving of that than a translation —
 * the whole frame changes size at once, so the opening rate is read directly as force.
 *
 * (0.42, 0) spends almost nothing in the first frames. The screen leans into the movement instead
 * of being thrown into it, which is the "lead-in" that was missing.
 */
private val DetailSpec =
    DestinationMotionSpec(
        durationMillis = 460,
        easing = CubicBezierEasing(0.42f, 0f, 0.28f, 1f),
        lift = 0.dp,
        overscale = 0.028f,
        scrim = 0.22f,
        scrimWindow = 0.40f,
    )

/*
 * Slower than the others, and softer off the mark than anything else here.
 *
 * Two separate corrections live in this curve. It was first on a steep decelerate, which put nearly
 * all of the sideways travel into the opening moments and made stepping into a settings page read as
 * a flick. Moving it to the long-tailed curve fixed the end but not the beginning: at a tenth of the
 * way through the duration the page had already covered a sixteenth of its travel, which is enough
 * to feel like being thrown into the animation from the page you were on rather than leaving it.
 *
 * Pulling the first control point out to 0.38 and its height down to 0.02 spends barely two percent
 * of the distance in the first tenth of the time. The page leans away before it goes, so one page
 * becomes the next without either of them appearing to be flung.
 */
private val SettingsSpec =
    DestinationMotionSpec(
        durationMillis = 440,
        backwardDurationMillis = 370,
        easing = CubicBezierEasing(0.38f, 0.02f, 0.3f, 1f),
        lift = 0.dp,
        shift = 30.dp,
        overscale = 0f,
        // Lighter than the others: a lateral step has no first-render settle to hide, it only needs
        // the seam between the instant swap and the movement softened.
        scrim = 0.14f,
        scrimWindow = 0.30f,
    )

/*
 * The boundary of a section: opening settings, and coming back out of it.
 *
 * Deliberately the [DetailSpec] gesture — a scale settling down to true size — because arriving at a
 * whole area of the app is the same *kind* of event as opening an artist, not a step along a path
 * and not a tab switch. Bigger and longer than Detail by a little, since what is being arrived at is
 * bigger.
 */
private val SectionSpec =
    DestinationMotionSpec(
        durationMillis = 500,
        easing = CubicBezierEasing(0.42f, 0f, 0.28f, 1f),
        lift = 0.dp,
        overscale = 0.045f,
        scrim = 0.22f,
        scrimWindow = 0.40f,
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
/**
 * Which character a destination arrives with, given where it was reached from.
 *
 * The route alone is not enough, and that is the correction here. "settings" reached from the app is
 * an arrival at a new area; reached from one of its own sub-pages it is a step back along a path.
 * "home" reached from another tab is a tab switch; reached from settings it is an area closing. The
 * same destination, two meanings, and taking the motion from the destination alone got both of them
 * wrong in the same way.
 */
internal fun destinationMotionFor(route: String?, from: String? = null): DestinationMotion {
    if (route == null) return DestinationMotion.Detail

    val arriving = route.isInSettings()
    val leaving = from?.isInSettings() == true

    return when {
        // Crossing the boundary either way: into settings, or back out to the app.
        arriving != leaving -> DestinationMotion.Section
        // Moving between pages within settings.
        arriving -> DestinationMotion.Settings
        Screens.MainScreens.any { it.route == route } -> DestinationMotion.Tab
        else -> DestinationMotion.Detail
    }
}

/**
 * The settings tree: its root and everything under it.
 *
 * The separator check keeps a route that merely begins with the same letters — "settings_backup" —
 * from counting as part of the tree.
 */
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
    val durationMillis =
        when (direction) {
            RouteDirection.Forward -> spec.durationMillis
            RouteDirection.Backward -> spec.backwardDurationMillis
        }
    val progress = remember(spec, direction) { Animatable(0f) }
    LaunchedEffect(spec, direction) {
        progress.animateTo(1f, tween(durationMillis, easing = spec.easing))
    }

    val density = LocalDensity.current
    val liftPx = with(density) { spec.lift.toPx() }
    val shiftPx = with(density) { spec.shift.toPx() * direction.sign }

    return this
        .graphicsLayer {
            // The tween has already applied the easing; clamping here guards only against a value
            // arriving out of range, which a cancelled or restored animation can produce.
            val settled = progress.value.let { if (it.isFinite()) it.coerceIn(0f, 1f) else 1f }
            val remaining = 1f - settled

            translationX = remaining * shiftPx
            translationY = remaining * liftPx
            if (spec.overscale != 0f) {
                scaleX = 1f + spec.overscale * remaining
                scaleY = 1f + spec.overscale * remaining
            }
        }
        .drawWithContent {
            /*
             * The veil is drawn *over* the content, never applied as an alpha to it.
             *
             * An alpha on the layer multiplies into every draw inside, which is why a
             * half-transparent child ended up travelling a different colour curve from its opaque
             * neighbours. Drawing a rectangle on top has no such interaction: the content underneath
             * is at full opacity the entire time and only looks darker.
             *
             * It also cannot intercept touches, because it is a draw instruction and not a layout
             * node — the screen is interactive from the first frame even while it is still dark.
             */
            drawContent()

            val settled = progress.value.let { if (it.isFinite()) it.coerceIn(0f, 1f) else 1f }
            val lifted = CapsuleMotion.smooth((settled / spec.scrimWindow).coerceIn(0f, 1f))
            val veil = (spec.scrim * (1f - lifted)).coerceIn(0f, 1f)
            if (veil > 0.002f) {
                drawRect(color = Color.Black, alpha = veil)
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
/** A destination being entered, and what it was entered from. */
internal data class RouteArrival(
    val route: String,
    val from: String?,
    val direction: RouteDirection,
)

internal class RouteHistory {
    private var previousRoute: String? = null

    fun enter(route: String): RouteArrival {
        val arrival = RouteArrival(route, previousRoute, routeDirection(previousRoute, route))
        previousRoute = route
        return arrival
    }

    /** Forgets the last route, so the next arrival is treated as a beginning. */
    fun clear() {
        previousRoute = null
    }
}
