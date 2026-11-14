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
                            BackHandler { backgroundBacks++ }
                            BackHandler(enabled = modal) {
                                if (state.isExpandedOrExpanding) back.handle(state::collapseSoft)
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

    private fun preview(progress: Float) = compose.runOnIdle {
        state.setUserGestureInProgress(true)
        val desired = with(compose.density) { (110.dp + 590.dp * progress).toPx() }
        val current = with(compose.density) { state.value.toPx() }
        state.dispatchRawUserDelta(desired - current, allowDismiss = false)
    }

    @Test fun cornersKeepTheirRadiusUntilTheEdgesAreAttached() {
        var lastTravel = 0f
        for (step in 0..1000) {
            val p = step / 1000f
            val contour = playerFrameContour(p, 12f, 14f, 1f)
            assertTrue(contour.radius >= 14f)
            assertTrue(contour.inset >= 0f)
            assertTrue(contour.bend in 0f..3.501f)
            assertTrue(playerFrameTravel(p) >= lastTravel - 0.00001f)
            lastTravel = playerFrameTravel(p)
            if (p <= 0.97f) assertEquals(0f, contour.bleed, 0f)
        }
        assertEquals(26f, playerFrameContour(1f, 12f, 14f, 1f).radius, 0f)
        assertEquals(1f, playerFrameTravel(0.97f), 0f)
        assertEquals(1f, playerFrameTravel(1f), 0f)
    }

    @Test fun squareViewportCornersFillProgressivelyWithoutAZeroRadiusSnap() {
        val progress = mutableStateOf(0.97f)
        compose.setContent {
            Box(Modifier.size(260.dp, 240.dp).background(Color.Green).testTag("scene")) {
                Box(Modifier.fillMaxSize().graphicsLayer {
                    shape = PlayerMagneticFrameShape(playerFrameContour(progress.value, 12.dp.toPx(), 14.dp.toPx(), density))
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

    @Test fun contentRetainsItsDimensionsWhileOnlyTheContourBends() {
        mount()
        val widths = mutableListOf<Float>()
        val heights = mutableListOf<Float>()
        for (p in listOf(0.3f, 0.6f, 0.85f, 0.97f, 0.99f, 1f)) {
            preview(p)
            compose.waitForIdle()
            val poster = compose.onNodeWithTag("poster").fetchSemanticsNode().boundsInRoot
            widths += poster.width
            heights += poster.height
            capture("opening-${(p * 100).roundToInt()}").recycle()
        }
        assertTrue(measuredContent.isNotEmpty())
        assertEquals(1, measuredContent.distinct().size)
        widths.forEach { assertEquals(widths.first(), it, 1f) }
        heights.forEach { assertEquals(heights.first(), it, 1f) }
    }

    @Test fun repeatedOpenDoesNotRestartTheSixHundredMillisecondMotion() {
        mount()
        compose.runOnIdle { state.expandSoft() }
        advance(240)
        compose.runOnIdle { assertTrue(state.rawProgress in 0.1f..0.9f); state.expandSoft() }
        advance(160)
        compose.runOnIdle { state.expandSoft() }
        advance(300)
        compose.runOnIdle { assertTrue(state.isExpanded); assertFalse(state.isAnimationRunning) }
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
