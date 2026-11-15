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
    private var miniDraws = 0
    private var miniMounts = 0
    private var miniDisposals = 0
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
                            DisposableEffect(Unit) {
                                miniMounts++
                                onDispose { miniDisposals++ }
                            }
                            Box(Modifier.fillMaxSize().drawWithContent { miniDraws++;drawContent() }
                                .background(Color(0xFF7C3331)).testTag("mini"))
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
        // Exercise the same mutation/intent owner as a real Mini tap. Raw-delta injection is a
        // gesture escape hatch and does not establish the player's tap-only presentation intent.
        compose.runOnIdle { state.expandSoft() }
        var frames = 0
        while (compose.runOnIdle { state.visualProgress } < progress && frames++ < 100) advance(16)
        compose.runOnIdle {
            assertTrue("opening did not reach sample $progress", state.visualProgress >= progress)
            assertEquals("the native sample must include the actual page tree", 1, contentMounts)
        }
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

    @Test fun realPageBeginsAtTheMovingMiniWithoutASeparatePalettePanel() {
        mount();sampleAt(0.04f)
        val p=compose.runOnIdle {state.visualProgress}
        val alpha=playerPageOpacity(p)
        assertTrue("the actual page must begin with the source handoff",alpha>0f && alpha<1f)
        val frame=playerWindowBounds(p,Size(360f,700f),48f,110f,12f)
        val bitmap=capture("direct-mini-page-handoff")
        // Below the moving Mini footprint but inside the growing window. Only the REAL page
        // can paint this region; the old separate palette panel painted a different opaque colour.
        val x=with(compose.density){40.dp.toPx()}.roundToInt()
        val y=with(compose.density){(frame.top+60f).dp.toPx()}.roundToInt()
        assertTrue(frame.top+60f<frame.bottom)
        val pixel=bitmap.getPixel(x,y)
        val focus=playerSceneDim(p)
        fun mix(app:Int,page:Int)=app*(1f-focus)*(1f-alpha)+page*alpha
        assertEquals(mix(49,155),AndroidColor.red(pixel).toFloat(),3f)
        assertEquals(mix(123,40),AndroidColor.green(pixel).toFloat(),3f)
        assertEquals(mix(80,35),AndroidColor.blue(pixel).toFloat(),3f)
        bitmap.recycle()
    }

    @Test fun onlyTheRealPagePaintsTheWindowAfterTheShortHandoff() {
        mount();sampleAt(0.43f)
        val p=compose.runOnIdle {state.visualProgress}
        assertEquals(1f,playerPageOpacity(p),0f)
        assertEquals(0f,playerMiniDrawAlpha(p),0f)
        val frame=playerWindowBounds(p,Size(360f,700f),48f,110f,12f)
        val bitmap=capture("real-page-no-palette-layer")
        val x=with(compose.density){40.dp.toPx()}.roundToInt()
        val y=with(compose.density){(frame.center.y+20f).dp.toPx()}.roundToInt()
        assertEquals(AndroidColor.rgb(155,40,35),bitmap.getPixel(x,y))
        bitmap.recycle()
    }

    @Test fun miniAndPageUseOneComplementaryHandoffWithoutAMaterialOnlyStage() {
        assertEquals(0f,playerPageOpacity(0.015f),0f)
        assertEquals(1f,playerPageOpacity(0.25f),0f)
        assertEquals(1f,playerMiniDrawAlpha(0f),0f)
        assertEquals(0f,playerMiniDrawAlpha(0.25f),0f)
        var previous=0f
        for(step in 0..1000) {
            val p=step/1000f
            val alpha=playerPageOpacity(p)
            assertTrue(alpha>=previous-0.000001f);previous=alpha
            assertEquals(1f,alpha+playerMiniDrawAlpha(p),0.000001f)
        }
    }

    @Test fun pageKeepsOneMeasurementAndUniformAspectThroughoutTheTrip() {
        mount()
        for (p in listOf(0.25f, 0.43f, 0.66f, 0.85f, 0.98f)) {
            sampleAt(p)
            capture("uniform-page-${(p*100).roundToInt()}").recycle()
            val a = pagePoint(0f, 0f); val b = pagePoint(1f, 1f)
            assertEquals(360f / 700f, (b.x-a.x)/(b.y-a.y), 0.0001f)
            val centre = pagePoint(0.5f,0.5f)
            val actual = compose.runOnIdle { state.visualProgress }
            val frame = playerWindowBounds(actual, Size(360f,700f),48f,110f,12f)
            assertEquals(with(compose.density) { frame.center.y.dp.toPx() }, centre.y, 1f)
        }
        assertEquals(1, measuredContent.distinct().size)
    }

    @Test fun miniTravelsWithTheWindowOnBothOpeningAndClosingAndKeepsItsSize() {
        mount()
        fun bounds()=compose.onNodeWithTag("mini",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
        val dock=bounds()
        fun checkPosition() {
            val p=compose.runOnIdle {state.visualProgress}
            val b=bounds()
            val expected=with(compose.density){playerMiniTop(p,700f,110f).dp.toPx()}
            assertEquals("Mini and the growing frame must share their upper edge",expected,b.top,1f)
            assertEquals(dock.height,b.height,1f)
            assertEquals(dock.width,b.width,1f)
        }
        compose.runOnIdle {state.expandSoft()};advance(32)
        checkPosition()
        assertTrue("Mini must move while it is still visibly handing off",bounds().top<dock.top-1f)
        assertTrue(compose.runOnIdle {playerMiniDrawAlpha(state.visualProgress)}>0f)
        capture("moving-mini-opening").recycle()
        repeat(43){advance(16);checkPosition()}
        compose.runOnIdle {state.collapseSoft()}
        var captured=false
        repeat(45){
            advance(16);checkPosition()
            val p=compose.runOnIdle {state.visualProgress}
            if(!captured && p>0.02f && p<0.20f){capture("moving-mini-closing").recycle();captured=true}
        }
        assertEquals(dock.top,bounds().top,1f)
        capture("closed").recycle()
    }

    @Test fun miniAndWindowShareOnePositionCurveWithoutAnIndependentLiftClock() {
        var previous=590f
        for(step in 0..1000) {
            val p=step/1000f
            val top=playerMiniTop(p,700f,110f)
            val frame=playerWindowBounds(p,Size(360f,700f),48f,110f,12f)
            assertEquals(frame.top,top,0f)
            assertTrue(top<=previous+0.001f);previous=top
        }
        assertEquals(590f,playerMiniTop(0f,700f,110f),0f)
        assertEquals(0f,playerMiniTop(1f,700f,110f),0f)
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

    @Test fun coveredMiniAndAppDrawingStopAfterTheWindowBecomesOpaque() {
        mount();compose.runOnIdle {state.expandSoft()};advance(32)
        capture("mini-active").recycle();advance(700)
        val source=miniDraws;val app=sceneDraws;val compositions=sceneCompositions
        assertTrue(source>0)
        advance(1500)
        compose.runOnIdle {assertEquals(source,miniDraws);assertEquals(app,sceneDraws);assertEquals(compositions,sceneCompositions)}
    }

    @Test fun miniAndDestinationRemainAttachedThroughMotionAndRetireOnlyAtRest() {
        mount();sampleAt(0.80f)
        compose.runOnIdle {
            assertTrue(state.isAnimationRunning)
            assertEquals(1,miniMounts);assertEquals(0,miniDisposals)
            assertEquals(1,contentMounts);assertEquals(0,contentDisposals)
            assertTrue(state.compactSurfaceVisible)
        }
        advance(700)
        compose.runOnIdle {assertEquals(0,contentDisposals);assertFalse(state.compactSurfaceVisible)}
        compose.runOnIdle {state.collapseSoft()};advance(64)
        compose.runOnIdle {
            assertEquals(1,miniMounts);assertEquals(0,miniDisposals)
            assertEquals(1,contentMounts);assertEquals(0,contentDisposals)
            assertTrue(state.compactSurfaceVisible)
        }
        advance(700)
        compose.runOnIdle {
            assertEquals(0,miniDisposals);assertEquals(1,contentDisposals)
            assertTrue(state.isCollapsed)
        }
    }

    @Test fun opaquePageCoversTheWholeWindowOnPhoneAndRailWidths() {
        for (width in listOf(320f,360f,400f,600f,960f)) {
            for (step in 660..1000) {
                val p=step/1000f
                val frame=playerWindowBounds(p,Size(width,700f),64f,120f,12f)
                val scale=playerPageScale(p,frame.width/width)
                assertTrue("no app-colour side seam at width $width and progress $p",width*scale+0.001f>=frame.width)
                assertTrue(700f*scale+0.001f>=frame.height)
            }
        }
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
