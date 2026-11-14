package com.nikhil.yt.ui.component

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w400dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlayerOpeningMotionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var state: BottomSheetState
    private val foreground = mutableStateOf(true)
    private var sceneDraws = 0
    private var sceneCompositions = 0
    private var navigationClicks = 0
    private var backgroundBacks = 0
    private var controlClicks = 0
    private var nestedSurface = false
    private val routeEpoch = mutableStateOf(0)
    private var contentMounts = 0
    private var contentDisposals = 0
    private val measuredContent = mutableListOf<IntSize>()

    private fun mount() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                state = rememberBottomSheetState(0.dp, 700.dp, 110.dp,
                    initialAnchor = COLLAPSED_ANCHOR, playerMotion = true)
                PlayerTransitionLifetime(state, foreground.value)
                val modal by remember { derivedStateOf {
                    state.isExpandedOrExpanding || shouldRenderExpandedSurface(state.visualProgress, state.isDismissed)
                } }
                val back = remember { PlayerBackAction() }
                DisposableEffect(back) {
                    back.callback = { if (nestedSurface) nestedSurface = false else state.collapseSoft() }
                    onDispose { back.callback = null }
                }
                Box(Modifier.size(360.dp, 700.dp).clipToBounds().background(Color.Black).testTag("scene")) {
                    PlayerSceneBackdrop(progress = { state.visualProgress }, blocked = modal) {
                        sceneCompositions++
                        Scaffold(
                            containerColor = Color(0xFF317B50),
                            contentWindowInsets = WindowInsets(0, 0, 0, 0),
                            bottomBar = {
                                Box(Modifier.fillMaxWidth().height(60.dp).background(Color.Blue)
                                    .clickable { navigationClicks++ }.testTag("navigation"))
                            },
                        ) { padding ->
                            Box(Modifier.fillMaxSize().padding(padding).drawWithContent {
                                sceneDraws++
                                drawContent()
                            }) { Text("APP SCENE", Modifier.padding(24.dp), color = Color.White) }
                            // Same registration order as the real Scaffold: NavHost first, overlay last.
                            key(routeEpoch.value) {
                                BackHandler { backgroundBacks++ }
                            }
                            key(routeEpoch.value) {
                                if (modal) BackHandler {
                                    if (state.isExpandedOrExpanding) back.handle(state::collapseSoft)
                                }
                            }
                        }
                    }
                    BottomSheet(
                        state = state, backgroundColor = Color(0xFF9B2823), gesturesEnabled = false,
                        backHandlerEnabled = false, collapsedContentHeight = 48.dp,
                        collapsedHorizontalInset = 12.dp, collapsedTopCornerRadius = 14.dp,
                        surfaceAlpha = ::playerHandoffSurfaceAlpha,
                        collapsedContent = {
                            Box(Modifier.fillMaxSize().background(Color(0xFF7C3331)).testTag("mini"))
                        },
                    ) {
                        DisposableEffect(Unit) {
                            contentMounts++
                            onDispose { contentDisposals++ }
                        }
                        Box(Modifier.fillMaxSize().onSizeChanged { measuredContent += it }) {
                            Box(Modifier.align(Alignment.TopCenter).padding(top = 56.dp)
                                .size(180.dp).background(Color(0xFFFFBF4C)).testTag("poster"))
                            Text("PLAYER", Modifier.align(Alignment.Center), color = Color.White)
                            Box(Modifier.align(Alignment.CenterEnd).size(48.dp)
                                .clickable { controlClicks++ }.testTag("control"))
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun advance(ms: Long) {
        repeat(((ms + 15) / 16).toInt()) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
    }

    @Test fun cornersKeepTheirRadiusUntilTheEdgesAreAttached() {
        var lastTravel = 0f
        for (step in 0..1000) {
            val p = step / 1000f
            val contour = playerFrameContour(p, 12f, 14f, 1f)
            assertTrue(contour.radius >= 14f)
            assertTrue(contour.inset >= 0f)
            assertTrue(playerFrameTravel(p) >= lastTravel - 0.00001f)
            lastTravel = playerFrameTravel(p)
            if (p <= 0.97f) assertEquals(0f, contour.bleed, 0f)
        }
        assertEquals(26f, playerFrameContour(1f, 12f, 14f, 1f).radius, 0f)
        assertEquals(1f, playerFrameTravel(0.97f), 0f)
        assertEquals(1f, playerFrameTravel(1f), 0f)
        val outline = PlayerCardShape(playerFrameContour(0.5f, 12f, 14f, 1f))
            .createOutline(Size(360f, 700f), LayoutDirection.Ltr, Density(1f))
        assertTrue("the card should use a native round-rectangle clip", outline is Outline.Rounded)
    }

    @Test fun cardBoundsGrowFromTheMiniInsteadOfStartingBelowTheScreen() {
        val height = 700f
        val dock = 110f
        val mini = 48f
        assertEquals(mini, playerFrameHeight(0f, height, mini), 0f)
        assertEquals(height, playerFrameHeight(1f, height, mini), 0f)
        var lastTop = height - dock
        var lastBottom = height - dock + mini
        for (step in 0..1000) {
            val p = step / 1000f
            val top = (height - dock) * (1f - playerFrameTravel(p))
            val bottom = top + playerFrameHeight(p, height, mini)
            assertTrue(top <= lastTop + 0.001f)
            assertTrue(bottom >= lastBottom - 0.001f)
            assertTrue(bottom <= height + 0.001f)
            lastTop = top
            lastBottom = bottom
        }
    }

    @Test fun firstFramesKeepMiniVisibleWithoutAnIsolatedBrightArtworkFragment() {
        mount()
        compose.runOnIdle { state.expandSoft() }
        advance(48)
        val p = compose.runOnIdle { state.visualProgress }
        assertTrue("sample must show the early physical reveal: $p", p > state.contentHandoffPoint && p < PlayerContentHandoffPoint)
        val bitmap = capture("opening-dock-origin")
        val x = bitmap.width / 2
        val navigationY = with(compose.density) { 670.dp.toPx() }.roundToInt()
        val navigation = bitmap.getPixel(x, navigationY)
        assertTrue("the card must not already extend through navigation", AndroidColor.blue(navigation) > 180)
        val top = 590f * (1f - playerFrameTravel(p))
        val plateY = with(compose.density) { (top + 58f).dp.toPx() }.roundToInt()
        assertEquals(0f, playerCardOpacity(p), 0f)
        assertEquals(1f, playerMiniDrawAlpha(p), 0f)
        val outsideMini = bitmap.getPixel(x, plateY)
        assertTrue("the bright photo fragment must be absent before the handoff",
            AndroidColor.red(outsideMini) < 180 && AndroidColor.green(outsideMini) < 160)
        bitmap.recycle()
    }

    @Test fun squareViewportCornersFillProgressivelyWithoutAZeroRadiusSnap() {
        val progress = mutableStateOf(0.97f)
        compose.setContent {
            Box(Modifier.size(260.dp, 240.dp).background(Color.Green).testTag("scene")) {
                Box(Modifier.fillMaxSize().graphicsLayer {
                    shape = PlayerCardShape(playerFrameContour(progress.value, 12.dp.toPx(), 14.dp.toPx(), density))
                    clip = true
                }.background(Color.Red))
            }
        }
        fun cornerInk(name: String): Int {
            val b = capture(name)
            val edge = with(compose.density) { 26.dp.toPx() }.roundToInt()
            return (0 until edge).sumOf { y -> (0 until edge).count { x -> AndroidColor.red(b.getPixel(x, y)) > 200 } }
        }
        val attached = cornerInk("square-attached-rounded")
        compose.runOnIdle { progress.value = 0.99f }
        val leaving = cornerInk("square-corners-leaving")
        compose.runOnIdle { progress.value = 1f }
        val settled = cornerInk("square-corners-outside")
        assertTrue(leaving > attached)
        assertTrue(settled > leaving)
        val pixel = capture("square-final").getPixel(1, 1)
        assertEquals(AndroidColor.RED, pixel)
    }

    @Test fun navigationIsCoveredAndCannotReceiveOpeningTaps() {
        mount()
        val collapsed = capture("collapsed")
        val x = collapsed.width / 2
        val y = collapsed.height - with(compose.density) { 24.dp.toPx() }.roundToInt()
        assertEquals(AndroidColor.BLUE, collapsed.getPixel(x, y))
        compose.runOnIdle { state.expandSoft() }
        advance(16)
        // Before the rising sheet has claimed its hit region, the app scene already yields input.
        compose.onNodeWithTag("scene").performTouchInput { click(Offset(center.x, height - 20f)) }
        compose.runOnIdle { assertEquals(0, navigationClicks) }
        advance(700)
        val expanded = capture("expanded")
        assertEquals(AndroidColor.rgb(155, 40, 35), expanded.getPixel(x, y))
        compose.onNodeWithTag("control").performClick()
        compose.runOnIdle { assertEquals(1, controlClicks) }
    }

    @Test fun layoutStaysStableWhileVisualElasticityRemainsSubtle() {
        mount()
        val widths = mutableListOf<Float>()
        val heights = mutableListOf<Float>()
        compose.runOnIdle { state.expandSoft() }
        for (p in listOf(0.3f, 0.6f, 0.85f, 0.97f, 0.99f, 1f)) {
            // Exercise the real animation/mutation owner instead of the raw gesture escape hatch.
            var frames = 0
            while (compose.runOnIdle { state.visualProgress } < p && frames++ < 50) advance(16)
            compose.runOnIdle { assertTrue("opening did not reach $p", state.visualProgress >= p) }
            val poster = compose.onNodeWithTag("poster", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            widths += poster.width
            heights += poster.height
            capture("opening-${(p * 100).roundToInt()}").recycle()
        }
        assertTrue(measuredContent.isNotEmpty())
        assertEquals(1, measuredContent.distinct().size)
        val settledWidth = widths.last()
        val settledHeight = heights.last()
        widths.forEach { assertTrue(abs(it - settledWidth) <= settledWidth * 0.012f + 1f) }
        heights.forEach { assertTrue(abs(it - settledHeight) <= settledHeight * 0.012f + 1f) }
    }

    @Test fun repeatedOpenKeepsTheCalibratedDurationInsteadOfRestarting() {
        mount()
        compose.runOnIdle { state.expandSoft() }
        advance(96)
        compose.runOnIdle { assertTrue(state.rawProgress in 0.1f..0.9f); state.expandSoft() }
        advance(96)
        compose.runOnIdle { state.expandSoft() }
        advance(208)
        compose.runOnIdle { assertFalse("opening should not rush to its end before 460ms", state.isExpanded) }
        advance(112)
        compose.runOnIdle { assertTrue(state.isExpanded); assertFalse(state.isAnimationRunning) }
    }

    @Test fun tapStartsMovingWithinTheFirstTwoFrames() {
        mount()
        compose.onNodeWithTag("mini", useUnmergedTree = true).performTouchInput {
            down(center)
            up()
        }
        compose.runOnIdle { assertTrue(state.isExpandedOrExpanding) }
        advance(32)
        compose.runOnIdle { assertTrue("tap left a motionless lead-in", state.rawProgress > 0.005f) }
    }

    @Test fun oneCardDissolveBlendsOverAnOpaqueMiniWithoutADeadMiddleFrame() {
        mount()
        compose.runOnIdle { state.expandSoft() }
        var frames = 0
        while (compose.runOnIdle { state.visualProgress } <= 0.14f && frames++ < 20) advance(16)
        val p = compose.runOnIdle { state.visualProgress }
        assertTrue(p < PlayerContentHandoffPoint)
        val alpha = playerCardOpacity(p)
        assertTrue(alpha > 0f && alpha < 1f)
        assertEquals(1f, playerMiniDrawAlpha(p), 0f)
        assertEquals(1f, fullPlayerContentAlpha(p, magnetic = true), 0f)
        val bitmap = capture("mini-card-blend")
        // Mini stays at its dock. Sample the overlapping backgrounds outside the poster.
        val x = with(compose.density) { 40.dp.toPx() }.roundToInt()
        val y = with(compose.density) { 614.dp.toPx() }.roundToInt()
        val actual = bitmap.getPixel(x, y)
        fun blend(source: Int, target: Int) = (source * (1f - alpha) + target * alpha).roundToInt()
        assertEquals("Mini must remain opaque UNDER the single card fade",
            blend(124, 155).toFloat(), AndroidColor.red(actual).toFloat(), 2f)
        assertEquals(blend(51, 40).toFloat(), AndroidColor.green(actual).toFloat(), 2f)
        assertEquals(blend(49, 35).toFloat(), AndroidColor.blue(actual).toFloat(), 2f)
        bitmap.recycle()
    }

    @Test fun closingPhotoFadesBeforeMiniSettlesAndBothEndpointsAreClean() {
        assertEquals(0f, playerCardOpacity(0f), 0f)
        assertEquals(0f, playerCardOpacity(0.06f), 0f)
        assertTrue(playerCardOpacity(0.09f) < 0.1f)
        assertTrue("background blending should already have begun on closing", playerCardOpacity(0.35f) < 0.95f)
        assertEquals(1f, playerCardOpacity(0.42f), 0f)
        assertEquals(1f, playerMiniDrawAlpha(0f), 0f)
        assertEquals(0f, playerMiniDrawAlpha(0.42f), 0f)
        var previous = 0f
        for (step in 0..1000) {
            val p = step / 1000f
            val alpha = playerCardOpacity(p)
            assertTrue(alpha >= previous)
            if (alpha < 1f) assertEquals(1f, playerMiniDrawAlpha(p), 0f)
            previous = alpha
        }
    }

    @Test fun elasticDeformationUsesVelocityAndVanishesAtRestAndNearMini() {
        assertTrue(playerCardElasticity(0.5f, 3f) > 0f)
        assertTrue(playerCardElasticity(0.5f, -3f) < 0f)
        assertEquals(0f, playerCardElasticity(0f, 3f), 0f)
        assertEquals(0f, playerCardElasticity(1f, -3f), 0f)
        assertEquals(0f, playerCardElasticity(0.5f, 0f), 0f)
        assertEquals(0f, playerCardElasticity(0.04f, 3f), 0f)
        for (p in listOf(-1f, 0f, 0.1f, 0.5f, 1f, 2f, Float.NaN)) {
            for (v in listOf(-100f, -3f, 0f, 3f, 100f, Float.NaN)) {
                assertTrue(playerCardElasticity(p, v) in -1f..1f)
            }
        }
    }

    @Test fun miniReachesUpOnlyOnClosingAndRelaxesWithoutAnotherClock() {
        assertTrue(playerMiniStretch(0.12f, -3f) > 0f)
        assertEquals(0f, playerMiniStretch(0.12f, 3f), 0f)
        assertEquals(0f, playerMiniStretch(0.12f, 0f), 0f)
        assertEquals(0f, playerMiniStretch(0f, -3f), 0f)
        assertEquals(0f, playerMiniStretch(1f, -3f), 0f)
        for (step in 0..1000) {
            for (velocity in listOf(-100f, -3f, 0f, 3f, Float.NaN)) {
                assertTrue(playerMiniStretch(step / 1000f, velocity) in 0f..0.06f)
            }
        }
    }

    @Test fun miniStaysDockedAndOnlyItsTopStretchesTowardsTheClosingPlayer() {
        mount()
        fun miniBounds() = compose.onNodeWithTag("mini", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val dock = miniBounds()
        compose.runOnIdle { state.expandSoft() }
        repeat(32) {
            advance(16)
            val opening = miniBounds()
            assertEquals("Mini must not ride the opening card", dock.top, opening.top, 1f)
            assertEquals(dock.bottom, opening.bottom, 1f)
        }
        compose.runOnIdle { state.collapseSoft() }
        var highestTop = dock.top
        var capturedStretch = false
        repeat(26) {
            advance(16)
            val closing = miniBounds()
            highestTop = minOf(highestTop, closing.top)
            if (!capturedStretch && closing.top < dock.top - 1f) {
                capture("mini-docked-stretch").recycle()
                capturedStretch = true
            }
            assertEquals("the lower Mini edge must stay anchored", dock.bottom, closing.bottom, 1f)
            assertEquals(dock.left, closing.left, 1f)
            assertEquals(dock.right, closing.right, 1f)
            assertTrue("only a small upward stretch is allowed", closing.top >= dock.top - dock.height * 0.061f - 1f)
        }
        assertTrue("closing must visibly reach towards the falling card", highestTop < dock.top - 1f)
        assertEquals(dock.top, miniBounds().top, 1f)
        capture("mini-fixed-after-close").recycle()
    }

    @Test fun dockedMiniRetainsItsIndependentDownwardDismissOffset() {
        mount()
        fun miniTop() = compose.onNodeWithTag("mini", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top
        val dock = miniTop()
        compose.runOnIdle { state.snapTo(90.dp) }
        val moved = miniTop()
        assertEquals(with(compose.density) { 20.dp.toPx() }, moved - dock, 1f)
        compose.runOnIdle { state.snapTo(state.collapsedBound) }
        assertEquals(dock, miniTop(), 1f)
    }

    @Test fun actualOpeningStretchesAndClosingCompressesTheSameMeasuredPage() {
        mount()
        compose.runOnIdle { state.expandSoft() }
        var frames = 0
        while (compose.runOnIdle { state.visualProgress } < 0.5f && frames++ < 30) advance(16)
        val openingHeight = compose.onNodeWithTag("poster", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.height
        capture("elastic-opening").recycle()
        advance(512)
        val settledHeight = compose.onNodeWithTag("poster", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.height
        compose.runOnIdle { state.collapseSoft() }
        frames = 0
        while (compose.runOnIdle { state.visualProgress } > 0.55f && frames++ < 30) advance(16)
        val closingHeight = compose.onNodeWithTag("poster", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.height
        capture("elastic-closing").recycle()
        assertTrue("opening must stretch subtly", openingHeight > settledHeight + 0.5f)
        assertTrue("closing must compress subtly", closingHeight < settledHeight - 0.5f)
        assertEquals(1, measuredContent.distinct().size)
    }

    @Test fun miniCanReopenAfterTheUpperCardHasDissolvedBeforeFinalSettlement() {
        mount()
        compose.runOnIdle { state.expandSoft() }
        advance(512)
        compose.runOnIdle { state.collapseSoft() }
        var frames = 0
        while (compose.runOnIdle { state.visualProgress } > 0.04f && frames++ < 40) advance(16)
        compose.runOnIdle {
            assertEquals(0f, playerCardOpacity(state.visualProgress), 0f)
            assertTrue(state.compactForegroundAcceptsInput)
        }
        compose.onNodeWithTag("mini", useUnmergedTree = true).performTouchInput { click(center) }
        compose.runOnIdle { assertTrue(state.isExpandedOrExpanding); assertEquals(0, navigationClicks) }
        advance(512)
        compose.runOnIdle { assertTrue(state.isExpanded); assertEquals(1, contentMounts) }
    }

    @Test fun appSceneStaysStationaryWhileTheCardCoversIt() {
        mount()
        compose.runOnIdle { state.expandSoft() }
        var frames = 0
        while (compose.runOnIdle { state.visualProgress } < 0.55f && frames++ < 30) advance(16)
        val bitmap = capture("stationary-app-scene")
        val x = with(compose.density) { 1.dp.toPx() }.roundToInt()
        val y = with(compose.density) { 40.dp.toPx() }.roundToInt()
        assertTrue("a receding app would expose a black strip at its left edge",
            AndroidColor.green(bitmap.getPixel(x, y)) > 60)
        bitmap.recycle()
    }

    @Test fun closeReopenNearMiniDoesNotDisposeAndRebuildTheVisiblePage() {
        mount()
        compose.runOnIdle { state.expandSoft() }
        advance(512)
        compose.runOnIdle { state.collapseSoft() }
        var frames = 0
        while (compose.runOnIdle { state.visualProgress } > 0.09f && frames++ < 40) advance(16)
        compose.runOnIdle {
            assertTrue(state.visualProgress > state.contentHandoffPoint)
            assertEquals(1, contentMounts)
            assertEquals("the still-visible page must survive the final closing segment", 0, contentDisposals)
        }
        capture("closing-near-mini").recycle()
        compose.runOnIdle { state.expandSoft() }
        advance(512)
        compose.runOnIdle { assertTrue(state.isExpanded); assertEquals(1, contentMounts) }
    }

    @Test fun reversalContinuesFromTheCurrentPositionAndLatestIntentWins() {
        mount()
        compose.runOnIdle { state.expandSoft() }
        advance(240)
        val before = compose.runOnIdle { state.value.value }
        compose.runOnIdle { state.collapseSoft(); assertEquals(before, state.value.value, 0.001f) }
        advance(128)
        val closing = compose.runOnIdle { state.value.value }
        assertTrue(closing < before)
        compose.runOnIdle { state.expandSoft(); assertEquals(closing, state.value.value, 0.001f) }
        advance(700)
        compose.runOnIdle { assertTrue(state.isExpanded) }
    }

    @Test fun stoppedActivityFinishesItsRequestedAnchorWithoutHiddenFrames() {
        mount()
        compose.runOnIdle { state.expandSoft() }
        advance(200)
        compose.runOnIdle { assertFalse(state.isExpanded); foreground.value = false }
        advance(32)
        compose.runOnIdle { assertTrue(state.isExpanded); assertFalse(state.isAnimationRunning) }
        val drawn = sceneDraws
        val composed = sceneCompositions
        advance(1500)
        compose.runOnIdle { assertEquals(drawn, sceneDraws); assertEquals(composed, sceneCompositions) }
    }

    @Test fun scaffoldBackClosesNestedSurfaceThenPlayerAndShieldsClosing() {
        mount()
        compose.runOnIdle { state.expandSoft() }
        advance(700)
        compose.runOnIdle { nestedSurface = true; compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.runOnIdle { assertFalse(nestedSurface); assertTrue(state.isExpanded); assertEquals(0, backgroundBacks) }
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        advance(96)
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed(); assertEquals(0, backgroundBacks) }
        advance(500)
        compose.runOnIdle { assertTrue(state.isCollapsed) }
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed(); assertEquals(1, backgroundBacks) }
    }

    @Test fun aLaterRouteDoesNotStealTheNextPlayerBack() {
        mount()
        compose.runOnIdle { routeEpoch.value++ }
        advance(32)
        compose.runOnIdle { state.expandSoft() }
        advance(700)
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        advance(500)
        compose.runOnIdle { assertTrue(state.isCollapsed); assertEquals(0, backgroundBacks) }
    }

    private fun capture(name: String): Bitmap {
        compose.waitForIdle()
        val bounds = compose.onNodeWithTag("scene").fetchSemanticsNode().boundsInRoot
        lateinit var bitmap: Bitmap
        compose.runOnIdle {
            bitmap = Bitmap.createBitmap(bounds.width.roundToInt(), bounds.height.roundToInt(), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.translate(-bounds.left, -bounds.top)
            compose.activity.findViewById<View>(android.R.id.content).draw(canvas)
        }
        val dir = File("build/player-opening-preview").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bitmap
    }
}
