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
    private var materialDraws = 0
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
                        compactBackground = {
                            Box(Modifier.fillMaxSize().drawWithContent { materialDraws++; drawContent() }
                                .background(Color(0xFF7C3331)))
                        },
                        collapsedContent = {
                            Box(Modifier.fillMaxSize().background(Color(0xFF7C3331)).testTag("mini"))
                        },
                    ) {
                        DisposableEffect(Unit) {
                            contentMounts++
                            onDispose { contentDisposals++ }
                        }
                        Box(Modifier.fillMaxSize().testTag("page").onSizeChanged { measuredContent += it }) {
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

    private fun sampleAt(progress: Float) {
        compose.runOnIdle {
            state.setUserGestureInProgress(true)
            val desired = state.collapsedBound + (state.expandedBound - state.collapsedBound) * progress
            state.dispatchRawUserDelta(with(compose.density) { (desired - state.value).toPx() }, allowDismiss = false)
        }
        compose.waitForIdle()
    }

    private fun pagePoint(x: Float, y: Float): Offset {
        val node = compose.onNodeWithTag("page", useUnmergedTree = true).fetchSemanticsNode()
        return compose.runOnIdle {
            node.layoutInfo.coordinates.localToRoot(Offset(node.layoutInfo.coordinates.size.width * x,
                node.layoutInfo.coordinates.size.height * y))
        }
    }

    @Test fun windowGrowsFromTheDockWithMonotonicEdgesAndPersistentRounding() {
        val size = Size(360f, 700f)
        val source = playerWindowBounds(0f, size, 48f, 110f, 12f)
        assertEquals(12f, source.left, 0f); assertEquals(590f, source.top, 0f)
        assertEquals(348f, source.right, 0f); assertEquals(638f, source.bottom, 0f)
        var previous = source
        for (step in 0..1000) {
            val p = step / 1000f
            val frame = playerWindowBounds(p, size, 48f, 110f, 12f)
            assertTrue(frame.top <= previous.top + 0.001f)
            assertTrue(frame.bottom >= previous.bottom - 0.001f)
            assertTrue(frame.left <= previous.left + 0.001f)
            assertTrue(playerFrameContour(p, 12f, 14f, 1f).radius >= 14f)
            previous = frame
        }
        assertEquals(androidx.compose.ui.geometry.Rect(0f, 0f, 360f, 700f), previous)
        val outline = PlayerWindowShape(source, 14f, 0f).createOutline(size, LayoutDirection.Ltr, Density(1f))
        assertTrue(outline is Outline.Rounded)
    }

    @Test fun sourceMaterialFillsTheWindowBeforeAnyBrightPageCanAppear() {
        mount(); sampleAt(0.12f)
        assertEquals(0f, playerPageOpacity(state.visualProgress), 0f)
        val bitmap = capture("source-material-no-photo")
        // Above Mini, INSIDE the expanded shell. This used to show the app through the photo.
        val x = with(compose.density) { 180.dp.toPx() }.roundToInt()
        val y = with(compose.density) { 550.dp.toPx() }.roundToInt()
        assertEquals(AndroidColor.rgb(124, 51, 49), bitmap.getPixel(x, y))
        bitmap.recycle()
    }

    @Test fun pageDissolvesIntoSourceMaterialInsteadOfShowingTheAppBehindIt() {
        mount(); sampleAt(0.43f)
        val p = compose.runOnIdle { state.visualProgress }
        val alpha = playerPageOpacity(p)
        val frame = playerWindowBounds(p, Size(360f, 700f), 48f, 110f, 12f)
        val bitmap = capture("material-page-blend")
        val x = with(compose.density) { 40.dp.toPx() }.roundToInt()
        val y = with(compose.density) { (frame.center.y + 20f).dp.toPx() }.roundToInt()
        val pixel = bitmap.getPixel(x, y)
        fun mix(source: Int, target: Int) = source * (1f - alpha) + target * alpha
        assertEquals(mix(124,155), AndroidColor.red(pixel).toFloat(), 2f)
        assertEquals(mix(51,40), AndroidColor.green(pixel).toFloat(), 2f)
        assertEquals(mix(49,35), AndroidColor.blue(pixel).toFloat(), 2f)
        bitmap.recycle()
    }

    @Test fun closingContentIsGoneBeforeTheWindowReachesMini() {
        assertEquals(0f, playerPageOpacity(0f), 0f)
        assertEquals(0f, playerPageOpacity(0.18f), 0f)
        assertEquals(1f, playerPageOpacity(0.66f), 0f)
        assertTrue(playerMiniDrawAlpha(0.08f) > 0f)
        assertEquals(0f, playerMiniDrawAlpha(0.20f), 0f)
        var previous = 0f
        for (step in 0..1000) {
            val alpha = playerPageOpacity(step / 1000f)
            assertTrue(alpha >= previous - 0.000001f); previous = alpha
        }
    }

    @Test fun pageKeepsOneMeasurementAndUniformAspectThroughoutTheTrip() {
        mount()
        for (p in listOf(0.25f, 0.43f, 0.66f, 0.85f, 0.98f)) {
            sampleAt(p)
            val a = pagePoint(0f, 0f); val b = pagePoint(1f, 1f)
            assertEquals(360f / 700f, (b.x-a.x)/(b.y-a.y), 0.0001f)
            val centre = pagePoint(0.5f,0.5f)
            val frame = playerWindowBounds(p, Size(360f,700f),48f,110f,12f)
            assertEquals(with(compose.density) { frame.center.y.dp.toPx() }, centre.y, 1f)
            capture("uniform-page-${(p*100).roundToInt()}").recycle()
        }
        assertEquals(1, measuredContent.distinct().size)
    }

    @Test fun miniStaysAtItsDockAndOnlyItsUpperEdgeReachesTowardsTheClosingWindow() {
        mount()
        fun bounds() = compose.onNodeWithTag("mini",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
        val dock = bounds()
        compose.runOnIdle { state.expandSoft() }
        repeat(45) {
            advance(16); val b = bounds()
            assertEquals(dock.top,b.top,1f); assertEquals(dock.bottom,b.bottom,1f)
        }
        compose.runOnIdle { state.collapseSoft() }
        var highest = dock.top; var captured = false
        repeat(45) {
            advance(16); val b=bounds(); highest=minOf(highest,b.top)
            assertEquals(dock.bottom,b.bottom,1f)
            assertEquals(dock.left,b.left,1f); assertEquals(dock.right,b.right,1f)
            assertTrue(b.height <= dock.height*1.046f+1f)
            if (!captured && b.top < dock.top-0.5f) {capture("mini-source-stretch").recycle();captured=true}
        }
        assertTrue(highest < dock.top-0.5f)
        assertEquals(dock.top,bounds().top,1f)
        capture("closed").recycle()
    }

    @Test fun stretchHasNoIndependentClockOrOpeningBounce() {
        assertTrue(playerMiniStretch(0.08f,-3f)>0f)
        assertEquals(0f,playerMiniStretch(0.08f,3f),0f)
        assertEquals(0f,playerMiniStretch(0.08f,0f),0f)
        assertEquals(0f,playerMiniStretch(0f,-3f),0f)
        assertEquals(0f,playerMiniStretch(1f,-3f),0f)
        for (step in 0..1000) assertTrue(playerMiniStretch(step/1000f,-100f) in 0f..0.045f)
    }

    @Test fun compactSwipeDismissStillMovesMiniDownIndependently() {
        mount()
        fun top()=compose.onNodeWithTag("mini",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot.top
        val dock=top()
        compose.runOnIdle {state.dispatchRawUserDelta(with(compose.density){(-20).dp.toPx()},true,true)}
        assertEquals(with(compose.density){20.dp.toPx()},top()-dock,1f)
        compose.runOnIdle {state.snapTo(state.collapsedBound)}
        assertEquals(dock,top(),1f)
    }

    @Test fun aTapMovesTheWindowWithinTwoDisplayFrames() {
        mount()
        compose.onNodeWithTag("mini",useUnmergedTree=true).performTouchInput {click(center)}
        advance(32)
        compose.runOnIdle {assertTrue(state.isExpandedOrExpanding);assertTrue(state.visualProgress>0.005f)}
    }

    @Test fun repeatedRequestsDoNotRestartTheSpringAndItSettlesWithoutALongTail() {
        mount(); compose.runOnIdle {state.expandSoft()}
        advance(80);compose.runOnIdle {state.expandSoft()}
        advance(80);compose.runOnIdle {state.expandSoft()}
        advance(550)
        compose.runOnIdle {assertTrue(state.isExpanded);assertFalse(state.isAnimationRunning)}
        compose.runOnIdle {state.collapseSoft()};advance(650)
        compose.runOnIdle {assertTrue(state.isCollapsed);assertFalse(state.isAnimationRunning)}
    }

    @Test fun reversalContinuesFromTheSamePositionAndLatestIntentWins() {
        mount();compose.runOnIdle {state.expandSoft()};advance(96)
        val before=compose.runOnIdle {state.value.value}
        compose.runOnIdle {state.collapseSoft();assertEquals(before,state.value.value,0.01f)}
        advance(96)
        val closing=compose.runOnIdle {state.value.value};assertTrue(closing<before)
        compose.runOnIdle {state.expandSoft();assertEquals(closing,state.value.value,0.01f)}
        advance(700)
        compose.runOnIdle {assertTrue(state.isExpanded);assertEquals(1,contentMounts)}
    }

    @Test fun reopeningAtTheSourceDoesNotRebuildTheVisiblePageOrClickTheApp() {
        mount();compose.runOnIdle {state.expandSoft()};advance(700)
        compose.runOnIdle {state.collapseSoft()}
        var frames=0
        while(compose.runOnIdle {state.visualProgress}>0.09f && frames++<60) advance(16)
        compose.runOnIdle {assertTrue(state.compactForegroundAcceptsInput);assertEquals(0,contentDisposals)}
        capture("source-handoff").recycle()
        compose.onNodeWithTag("mini",useUnmergedTree=true).performTouchInput {click(center)}
        advance(700)
        compose.runOnIdle {assertTrue(state.isExpanded);assertEquals(1,contentMounts);assertEquals(0,navigationClicks)}
    }

    @Test fun theStationaryAppAndNavigationNeverReceiveTransitionTaps() {
        mount();capture("collapsed").recycle()
        compose.runOnIdle {state.expandSoft()};advance(32)
        compose.onNodeWithTag("scene").performTouchInput {click(Offset(center.x,height-20f))}
        compose.runOnIdle {assertEquals(0,navigationClicks)}
        advance(700);capture("expanded").recycle()
        compose.onNodeWithTag("control").performClick()
        compose.runOnIdle {assertEquals(1,controlClicks)}
    }

    @Test fun sourceMaterialAndAppDrawingStopAfterTheWindowBecomesOpaque() {
        mount();compose.runOnIdle {state.expandSoft()};advance(700)
        val source=materialDraws;val app=sceneDraws;val compositions=sceneCompositions
        advance(1500)
        compose.runOnIdle {assertEquals(source,materialDraws);assertEquals(app,sceneDraws);assertEquals(compositions,sceneCompositions)}
    }

    @Test fun stoppingTheActivityCompletesTheCurrentTargetWithoutHiddenAnimationFrames() {
        mount();compose.runOnIdle {state.expandSoft()};advance(80)
        compose.runOnIdle {foreground.value=false};advance(32)
        compose.runOnIdle {assertTrue(state.isExpanded);assertFalse(state.isAnimationRunning)}
        val draws=sceneDraws;advance(1000)
        compose.runOnIdle {assertEquals(draws,sceneDraws)}
    }

    @Test fun backClosesNestedSurfaceThenPlayerAndConsumesRepeatsDuringClosing() {
        mount();compose.runOnIdle {state.expandSoft()};advance(700)
        compose.runOnIdle {nestedSurface=true;compose.activity.onBackPressedDispatcher.onBackPressed()}
        compose.runOnIdle {assertFalse(nestedSurface);assertTrue(state.isExpanded)}
        compose.runOnIdle {compose.activity.onBackPressedDispatcher.onBackPressed()};advance(64)
        compose.runOnIdle {compose.activity.onBackPressedDispatcher.onBackPressed();assertEquals(0,backgroundBacks)}
        advance(700)
        compose.runOnIdle {assertTrue(state.isCollapsed);compose.activity.onBackPressedDispatcher.onBackPressed();assertEquals(1,backgroundBacks)}
    }

    @Test fun routeRecompositionDoesNotStealPlayerBackOwnership() {
        mount();compose.runOnIdle {routeEpoch.value++};advance(32)
        compose.runOnIdle {state.expandSoft()};advance(700)
        compose.runOnIdle {compose.activity.onBackPressedDispatcher.onBackPressed()};advance(700)
        compose.runOnIdle {assertTrue(state.isCollapsed);assertEquals(0,backgroundBacks)}
    }

    @Test fun cornersFinishOutsideTheSquareViewportInsteadOfLosingTheirRadius() {
        val p=mutableStateOf(0.97f)
        compose.setContent {
            Box(Modifier.size(260.dp,240.dp).background(Color.Green).testTag("scene")) {
                Box(Modifier.fillMaxSize().graphicsLayer {
                    val contour=playerFrameContour(p.value,12.dp.toPx(),14.dp.toPx(),density)
                    shape=PlayerWindowShape(androidx.compose.ui.geometry.Rect(0f,0f,size.width,size.height),contour.radius,contour.bleed)
                    clip=true
                }.background(Color.Red))
            }
        }
        fun ink(name:String):Int {
            val bitmap=capture(name);val edge=with(compose.density){26.dp.toPx()}.roundToInt()
            val count=(0 until edge).sumOf{y->(0 until edge).count{x->AndroidColor.red(bitmap.getPixel(x,y))>200}}
            bitmap.recycle();return count
        }
        val start=ink("rounded-attached");compose.runOnIdle{p.value=0.99f};val leaving=ink("rounded-leaving")
        compose.runOnIdle{p.value=1f};val end=ink("rounded-outside")
        assertTrue(leaving>start);assertTrue(end>leaving)
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
