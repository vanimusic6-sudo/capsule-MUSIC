package com.nikhil.yt.ui.component

import android.app.Application
import com.nikhil.yt.ui.player.CapsuleVideoArtworkTransition
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
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
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
    private var compactClicks = 0
    private var sharedDecoded by mutableStateOf(true)
    private var nestedSurface = false
    private val routeEpoch = mutableStateOf(0)
    private var contentMounts = 0
    private var contentDisposals = 0
    private val measuredContent = mutableListOf<IntSize>()

    private fun mount(portraitPoster: Boolean = false, actualArtworkCard: Boolean = false, decodedArtwork: Boolean = true) {
        sharedDecoded = decodedArtwork
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
                            Box(Modifier.fillMaxSize().padding(horizontal=12.dp)) {
                                Box(Modifier.fillMaxSize()
                                    .playerMorphElement(PlayerMorphPart.Surface,"fixture",false)
                                    .drawWithContent { miniDraws++;drawContent() }
                                    .background(Color(0xFF7C3331)).testTag("mini")) {
                                    Box(Modifier.align(Alignment.CenterStart).padding(start=10.dp).size(40.dp)
                                        .clip(CircleShape)
                                        .playerMorphElement(PlayerMorphPart.Artwork,"fixture",false,20.dp)
                                        .background(Color(0xFFFFBF4C)).drawBehind { drawCircle(Color.Cyan,radius=10.dp.toPx()) }
                                        .testTag("mini-art"))
                                    Text("PLAYER",Modifier.align(Alignment.CenterStart).padding(start=62.dp)
                                        .playerMorphElement(PlayerMorphPart.Title,"fixture",false,fontSizeSp=14f).testTag("mini-title"),
                                        fontSize=14.sp,color=Color.White)
                                    Box(Modifier.align(Alignment.CenterEnd).size(24.dp)
                                        .clickable{compactClicks++}.testTag("mini-control"))
                                }
                            }
                        },
                    ) {
                        DisposableEffect(Unit) {
                            contentMounts++
                            onDispose { contentDisposals++ }
                        }
                        Box(Modifier.fillMaxSize().testTag("page").onSizeChanged { measuredContent += it }) {
                            Box(Modifier.align(Alignment.TopCenter).padding(top = 56.dp)
                                .size(180.dp,if(portraitPoster)240.dp else 180.dp).testTag("poster")) {
                                if(actualArtworkCard) {
                                    CapsuleVideoArtworkTransition(presentingVideo=false,needsSurface=false,
                                        transitionKey="fixture",artworkCornerRadius=16.dp,
                                        artworkBackground=Color.Magenta,video={}) {
                                        Box(Modifier.fillMaxSize().playerMorphArtworkReady("fixture",sharedDecoded)
                                            .background(if(sharedDecoded)Color(0xFFFFBF4C) else Color.Magenta)
                                            .drawBehind{drawCircle(Color.Cyan,radius=45.dp.toPx())})
                                    }
                                } else Box(Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp))
                                    .playerMorphElement(PlayerMorphPart.Artwork,"fixture",true,16.dp,requiresReadyArtwork=true)
                                    .playerMorphArtworkReady("fixture",sharedDecoded)
                                    .background(if(sharedDecoded)Color(0xFFFFBF4C) else Color.Magenta).drawBehind { drawCircle(Color.Cyan,radius=45.dp.toPx()) })
                            }
                            Text("PLAYER", Modifier.align(Alignment.Center)
                                .playerMorphElement(PlayerMorphPart.Title,"fixture",true,fontSizeSp=23f).testTag("full-title"),fontSize=23.sp,color=Color.White)
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

    private fun sampleAt(progress: Float, expectedMounts: Int = 1) {
        // Exercise the same mutation/intent owner as a real Mini tap. Raw-delta injection is a
        // gesture escape hatch and does not establish the player's tap-only presentation intent.
        compose.runOnIdle { state.expandSoft() }
        var frames = 0
        while (compose.runOnIdle { state.visualProgress } < progress && frames++ < 100) advance(16)
        compose.runOnIdle {
            assertTrue("opening did not reach sample $progress", state.visualProgress >= progress)
            assertEquals("the native sample must include the actual page tree", expectedMounts, contentMounts)
        }
    }

    private fun pagePoint(x: Float, y: Float): Offset {
        val node = compose.onNodeWithTag("page", useUnmergedTree = true).fetchSemanticsNode()
        return compose.runOnIdle {
            node.layoutInfo.coordinates.localToRoot(Offset(node.layoutInfo.coordinates.size.width * x,
                node.layoutInfo.coordinates.size.height * y))
        }
    }

    private fun rawBounds(tag: String): androidx.compose.ui.geometry.Rect =
        compose.onNodeWithTag(tag,useUnmergedTree=true).fetchSemanticsNode().boundsInRoot

    private fun yellowBounds(bitmap: Bitmap): android.graphics.Rect {
        var left=bitmap.width;var top=bitmap.height;var right=-1;var bottom=-1
        for(y in 0 until bitmap.height) for(x in 0 until bitmap.width) {
            val c=bitmap.getPixel(x,y)
            if(AndroidColor.red(c)>245 && AndroidColor.green(c) in 180..200 && AndroidColor.blue(c) in 65..90) {
                left=minOf(left,x);top=minOf(top,y);right=maxOf(right,x);bottom=maxOf(bottom,y)
            }
        }
        assertTrue("the actual shared artwork must paint",right>=left && bottom>=top)
        return android.graphics.Rect(left,top,right+1,bottom+1)
    }

    @Test fun realContainerInterpolatesAllFourMiniEdgesIntoTheScreen() {
        val source=androidx.compose.ui.geometry.Rect(12f,590f,348f,638f)
        val target=androidx.compose.ui.geometry.Rect(0f,0f,360f,700f)
        assertEquals(source,playerMorphBounds(source,target,0f))
        assertEquals(target,playerMorphBounds(source,target,1f))
        val half=playerMorphBounds(source,target,0.5f)
        assertEquals(androidx.compose.ui.geometry.Rect(6f,295f,354f,669f),half)
    }

    @Test fun actualArtworkGrowsOutOfTheMiniInsteadOfRevealingAFinalSizePoster() {
        mount()
        val source=rawBounds("mini-art")
        for(fraction in listOf(0.04f,0.15f,0.30f,0.50f,0.75f,0.92f)) {
            sampleAt(fraction)
            val p=compose.runOnIdle {state.visualProgress}
            val target=rawBounds("poster")
            val expected=playerMorphBounds(source,target,p)
            val bitmap=capture("shared-art-${(fraction*100).roundToInt()}")
            val ink=yellowBounds(bitmap)
            assertEquals("artwork left",expected.left,ink.left.toFloat(),3f)
            assertEquals("artwork top",expected.top,ink.top.toFloat(),3f)
            assertEquals("artwork width must grow rather than show a final-size slice",expected.width,ink.width().toFloat(),4f)
            assertEquals("the cover must remain square",ink.width().toFloat(),ink.height().toFloat(),4f)
            bitmap.recycle()
        }
        assertEquals(1,measuredContent.distinct().size)
    }

    @Test fun aLoadingDestinationKeepsTheRealThumbnailUntilDecodedArtworkIsReady() {
        mount(decodedArtwork=false)
        val source=rawBounds("mini-art")
        sampleAt(0.35f)
        fun check(name:String) {
            val p=compose.runOnIdle{state.visualProgress}
            val expected=playerMorphBounds(source,rawBounds("poster"),p)
            val bitmap=capture(name)
            val ink=yellowBounds(bitmap)
            assertEquals(expected.width,ink.width().toFloat(),4f)
            assertEquals(expected.top,ink.top.toFloat(),3f)
            assertTrue("loading magenta must never replace the shared photograph",
                bitmap.getPixel(expected.center.x.roundToInt(),expected.center.y.roundToInt())!=AndroidColor.MAGENTA)
            bitmap.recycle()
        }
        check("shared-photo-loading-source")
        compose.runOnIdle{sharedDecoded=true};advance(16)
        check("shared-photo-decoded-target")
    }

    private fun whiteBounds(bitmap:Bitmap,region:androidx.compose.ui.geometry.Rect):androidx.compose.ui.geometry.Rect {
        var left=bitmap.width;var top=bitmap.height;var right=-1;var bottom=-1
        for(y in region.top.toInt().coerceAtLeast(0) until region.bottom.toInt().coerceAtMost(bitmap.height))
            for(x in region.left.toInt().coerceAtLeast(0) until region.right.toInt().coerceAtMost(bitmap.width)) {
                val c=bitmap.getPixel(x,y)
                if(AndroidColor.red(c)>220 && AndroidColor.green(c)>220 && AndroidColor.blue(c)>220) {
                    left=minOf(left,x);top=minOf(top,y);right=maxOf(right,x);bottom=maxOf(bottom,y)
                }
            }
        assertTrue("the moving title must paint readable glyphs",right>=left && bottom>=top)
        return androidx.compose.ui.geometry.Rect(left.toFloat(),top.toFloat(),(right+1).toFloat(),(bottom+1).toFloat())
    }

    @Test fun sharedTitleInterpolatesActualGlyphSizeAndBaselineWithoutADoubledLine() {
        mount()
        val sourceTitle=rawBounds("mini-title")
        val closed=capture("title-source")
        val sourceGlyphs=whiteBounds(closed,sourceTitle);closed.recycle()
        compose.runOnIdle{state.expandSoft()};advance(700)
        val targetTitle=rawBounds("full-title")
        val opened=capture("title-target")
        val targetGlyphs=whiteBounds(opened,targetTitle);opened.recycle()
        compose.runOnIdle{state.collapseSoft()};advance(700)
        sampleAt(0.35f, expectedMounts = 2)
        val p=compose.runOnIdle{state.visualProgress}
        val title=playerMorphBounds(sourceTitle,targetTitle,p)
        val bitmap=capture("title-shared-baseline")
        val glyphs=whiteBounds(bitmap,title.inflate(3f))
        val expected=playerMorphBounds(sourceGlyphs,targetGlyphs,p)
        assertEquals("font width",expected.width,glyphs.width,4f)
        assertEquals("font height must not include two differently scaled lines",expected.height,glyphs.height,4f)
        assertEquals("baseline path",expected.bottom,glyphs.bottom,4f)
        bitmap.recycle()
    }

    @Test fun actualCapsuleArtworkCardDoesNotLeaveItsEmptyMatteBehindTheSharedCover() {
        mount(actualArtworkCard=true)
        val source=rawBounds("mini")
        sampleAt(0.70f)
        val p=compose.runOnIdle{state.visualProgress}
        val frame=playerMorphBounds(source,with(compose.density){
            androidx.compose.ui.geometry.Rect(0f,0f,360.dp.toPx(),700.dp.toPx())},p)
        val bitmap=capture("actual-capsule-card-shared-cover")
        val target=rawBounds("poster")
        val x=(target.left+with(compose.density){20.dp.toPx()}).roundToInt()
        val y=(frame.top+with(compose.density){12.dp.toPx()}).roundToInt()
        assertTrue("sample must be in the final artwork slot",y<target.bottom)
        assertEquals("no static magenta artwork matte behind the moving shared image",
            AndroidColor.rgb(155,40,35),bitmap.getPixel(x,y))
        bitmap.recycle()
    }

    @Test fun rectangularDestinationCropsThePhotoWithoutStretchingACircleIntoAnEllipse() {
        mount(portraitPoster=true)
        for(fraction in listOf(0.20f,0.40f,0.70f)) {
            sampleAt(fraction)
            val bitmap=capture("portrait-cover-${(fraction*100).roundToInt()}")
            var left=bitmap.width;var top=bitmap.height;var right=-1;var bottom=-1
            for(y in 0 until bitmap.height) for(x in 0 until bitmap.width) {
                val c=bitmap.getPixel(x,y)
                if(AndroidColor.red(c)<15 && AndroidColor.green(c)>240 && AndroidColor.blue(c)>240) {
                    left=minOf(left,x);top=minOf(top,y);right=maxOf(right,x);bottom=maxOf(bottom,y)
                }
            }
            assertTrue("the photo's circular reference must be visible",right>left && bottom>top)
            assertEquals("uniform crop must keep the circle round",(right-left).toFloat(),(bottom-top).toFloat(),3f)
            bitmap.recycle()
        }
    }

    @Test fun idleCompactButtonsKeepTheirActualTouchTargetsAndDoNotOpenThePlayer() {
        mount()
        compose.onNodeWithTag("mini-control",useUnmergedTree=true).performTouchInput{click(center)}
        advance(32)
        compose.runOnIdle{assertEquals(1,compactClicks);assertTrue(state.isCollapsed)}
    }

    @Test fun expandingContainerStartsAtTheActualInsetCardAndDoesNotPaintOutsideItsBounds() {
        mount();val source=rawBounds("mini")
        sampleAt(0.30f)
        val p=compose.runOnIdle {state.visualProgress}
        val target=with(compose.density){androidx.compose.ui.geometry.Rect(0f,0f,360.dp.toPx(),700.dp.toPx())}
        val frame=playerMorphBounds(source,target,p)
        val bitmap=capture("shared-container")
        // A point inside the new shell but outside the compact strip must contain the real page.
        val x=(frame.left+with(compose.density){12.dp.toPx()}).roundToInt()
        val y=(frame.top+with(compose.density){90.dp.toPx()}).roundToInt()
        assertEquals(AndroidColor.rgb(155,40,35),bitmap.getPixel(x,y))
        // The previous slide leaked an enlarged photo below Mini/navigation. The shell must clip
        // this point until its interpolated bottom edge actually reaches it.
        val navY=with(compose.density){690.dp.toPx()}.roundToInt()
        assertTrue(navY>frame.bottom)
        val nav=bitmap.getPixel(bitmap.width/2,navY)
        assertTrue("navigation must remain outside the expanding container",AndroidColor.blue(nav)>150)
        bitmap.recycle()
    }

    @Test fun bodyWaitsForSpaceWhileSharedArtworkIsAlreadyMoving() {
        assertEquals(0f,playerBodyOpacity(0.20f),0f)
        assertEquals(1f,playerBodyOpacity(0.72f),0f)
        assertFalse(playerPageAcceptsInput(0.20f))
        assertTrue(playerPageAcceptsInput(0.72f))
        assertEquals(1f,playerMiniDrawAlpha(0f),0f)
        assertEquals(0f,playerMiniDrawAlpha(0.28f),0f)
    }

    @Test fun closingArtworkReturnsToTheSameThumbnailAndLeavesNoPosterBelowMini() {
        mount();val source=rawBounds("mini-art")
        compose.runOnIdle{state.expandSoft()};advance(700)
        val target=rawBounds("poster")
        compose.runOnIdle{state.collapseSoft()}
        var sampled=false
        repeat(45) {
            advance(16)
            val p=compose.runOnIdle{state.visualProgress}
            if(!sampled && p in 0.15f..0.5f) {
                val bitmap=capture("shared-art-closing")
                val ink=yellowBounds(bitmap)
                val expected=playerMorphBounds(source,target,p)
                assertEquals(expected.left,ink.left.toFloat(),3f)
                assertEquals(expected.width,ink.width().toFloat(),4f)
                bitmap.recycle();sampled=true
            }
        }
        assertTrue(sampled)
        val bitmap=capture("closed-no-ghost")
        val ink=yellowBounds(bitmap)
        assertEquals(source.left,ink.left.toFloat(),3f)
        assertEquals(source.width,ink.width().toFloat(),4f)
        val navY=with(compose.density){670.dp.toPx()}.roundToInt()
        assertEquals(AndroidColor.BLUE,bitmap.getPixel(bitmap.width/2,navY))
        bitmap.recycle()
    }

    @Test fun finalPageKeepsItsOwnMeasurementsWhileSharedElementsTravel() {
        mount();sampleAt(0.43f)
        val a=pagePoint(0f,0f);val b=pagePoint(1f,1f)
        assertEquals(with(compose.density){360.dp.toPx()},b.x-a.x,1f)
        assertEquals(with(compose.density){700.dp.toPx()},b.y-a.y,1f)
        assertEquals(0f,a.y,1f)
        assertEquals(1,measuredContent.distinct().size)
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
        compose.onNodeWithTag("player-mini-handoff",useUnmergedTree=true).performTouchInput {click(center)}
        advance(700)
        compose.runOnIdle {assertTrue(state.isExpanded);assertEquals(1,contentMounts);assertEquals(0,navigationClicks)}
    }

    @Test fun theStationaryAppAndNavigationNeverReceiveTransitionTaps() {
        mount();capture("collapsed").recycle()
        compose.runOnIdle {state.expandSoft()};advance(32)
        compose.onNodeWithTag("scene").performTouchInput {click(Offset(center.x,height-20f))}
        compose.runOnIdle {assertEquals(0,navigationClicks)}
        advance(700);capture("expanded").recycle()
        compose.onNodeWithTag("control").performTouchInput{click(center)}
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
                    val contour=playerPageContour(p.value,14.dp.toPx(),density)
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
