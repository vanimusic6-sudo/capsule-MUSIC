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
import androidx.compose.runtime.setValue
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

    /**
     * Going into something: an artist, an album, a playlist.
     *
     * No entrance at all, and that is the whole of it. This one had a scale, which resampled every
     * edge in the frame and left the artist header's bottom edge crawling; it was moved to a
     * translation, and the edge still came apart. Two different transforms producing the same
     * artefact on the same screen says the fault is not in the choice of transform — it is that
     * these screens are being drawn into a layer they do not otherwise have. So they are not.
     *
     * The screens under this character are the heavy ones: full-bleed artwork, gradients, fades
     * that are sized to the frame. They arrive as a cut, which costs nothing and cannot glitch.
     */
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
     * So the boundary gets its own motion: a rise, carried further and held longer than a tab's,
     * because a whole area of the app is a bigger thing to arrive at than the tab next door.
     * Settings pages are plain, so unlike the detail screens they take a layer without artefacts.
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
    /** Distance the content rises through. Zero for motion that travels sideways instead. */
    val lift: Dp = 0.dp,
    /** Distance the content travels in from the trailing edge. Zero for motion that does not. */
    val shift: Dp = 0.dp,
    /**
     * Size the content resolves down from. 0f keeps it at its true size throughout.
     *
     * Only the settings boundary uses it, and only because those pages are plain — see below.
     */
    val overscale: Float = 0f,
)

/*
 * Where a scale is allowed, and where it is not.
 *
 * The detail and section entrances both used to resolve down from a slightly oversized state, and
 * on the detail screens that was visibly broken.
 *
 * A scale resamples the whole frame: for the length of the animation every edge inside the screen
 * sits on a fractional pixel and is redrawn from a different set of source pixels each frame. Where
 * two opaque fills meet — the bottom edge of the artist header against the page under it — that
 * shows up as a seam that crawls and flickers until the screen lands. It also draws outside its own
 * bounds, because `graphicsLayer` does not clip, so an oversized screen overhangs its neighbours
 * and then retracts.
 *
 * The detail screens were first moved to a rise, and their bottom edge came apart under that too. A
 * defect that survives the transform being swapped is not a defect of the transform: what both
 * versions shared was the layer, and those are the screens that fill it with full-bleed artwork,
 * gradients and frame-sized fades. So they get no layer at all now, and arrive as a cut.
 *
 * The settings boundary is the opposite case and keeps its scale. Those pages are flat lists on a
 * flat background — no artwork, no gradients, nothing with a high-contrast internal edge for the
 * resampling to show up on — and the scale is what makes opening settings read as arriving at an
 * area of the app rather than as another page sliding in. Replacing it with a rise broke that, so
 * it is back, unchanged.
 */

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
        shift = 30.dp,
    )

/*
 * The boundary of a section: opening settings, and coming back out of it.
 *
 * A scale, because arriving at a whole area of the app is neither a step along a path nor a tab
 * switch, and neither travel direction says that — a rise here read as one more page arriving.
 * Slower and gentler off the mark than the rest, since what is being arrived at is bigger.
 */
private val SectionSpec =
    DestinationMotionSpec(
        durationMillis = 500,
        easing = CubicBezierEasing(0.42f, 0f, 0.28f, 1f),
        overscale = 0.045f,
    )

/**
 * Tabs are the four bottom-bar destinations. Everything else is something the user opened.
 *
 * Note what is deliberately absent: a real blur. A `RenderEffect` forces an offscreen buffer for
 * the whole screen and allocating it the first time is what made opening the player stall on
 * device. The scale that survives costs no buffer, and is confined to the one place whose content
 * does not show the resampling.
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

/** Null means the destination simply appears: no layer, no animation, nothing to go wrong. */
internal fun DestinationMotion.spec(): DestinationMotionSpec? =
    when (this) {
        DestinationMotion.Tab -> TabSpec
        DestinationMotion.Detail -> null
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

    // No spec, no layer. The destination is composed exactly as it would be with none of this here.
    val spec = motion.spec() ?: return this
    val durationMillis =
        when (direction) {
            RouteDirection.Forward -> spec.durationMillis
            RouteDirection.Backward -> spec.backwardDurationMillis
        }

    /*
     * Once the entrance is over the modifier is removed entirely, and that is the whole energy
     * story of this file.
     *
     * A `graphicsLayer` left in place is a RenderNode the screen keeps for as long as it exists, and
     * every one of them is another node the compositor walks on every frame — for a transform that
     * has been the identity matrix since half a second after the screen opened. Dropping it costs
     * exactly one recomposition, at the moment the animation ends, and buys an idle screen that is
     * byte for byte what it would be if this file did not exist.
     *
     * The transform is already identity and the content already fully opaque when this flips, so
     * there is nothing to see: the frame before and the frame after are the same pixels.
     */
    val progress = remember(spec, direction) { Animatable(0f) }
    var running by remember(spec, direction) { mutableStateOf(true) }

    LaunchedEffect(spec, direction) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(durationMillis, easing = spec.easing))
        running = false
    }

    if (!running) return this

    val density = LocalDensity.current
    val liftPx = with(density) { spec.lift.toPx() }
    val shiftPx = with(density) { spec.shift.toPx() * direction.sign }

    /*
     * Transforms only — no alpha, and nothing drawn on top.
     *
     * That distinction is what makes this cheap rather than merely small. A layer carrying an alpha
     * below 1, or a RenderEffect, has to be composited through an offscreen buffer: the content is
     * rendered into a texture the size of the screen and then blended. A layer carrying only a
     * translation and a scale is a display list with a matrix attached, which the GPU applies for
     * free while drawing it. Same code path as scrolling.
     */
    return this.graphicsLayer {
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
