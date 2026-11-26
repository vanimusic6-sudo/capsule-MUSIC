package com.nikhil.yt.ui.component

import android.app.Application
import com.nikhil.yt.ui.player.CapsuleVideoArtworkTransition
import com.nikhil.yt.ui.player.capsuleInlineLyricsStops
import com.nikhil.yt.ui.player.CAPSULE_COVER_HANDOFF_MS
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
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
import androidx.compose.ui.graphics.luminance
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
                val fixturePainter = remember {
                    object : Painter() {
                        override val intrinsicSize = Size(180f, 180f)
                        override fun DrawScope.onDraw() {
                            drawRect(Color(0xFFFFBF4C))
                            drawCircle(Color.Cyan, radius = size.minDimension * 0.25f)
                        }
                    }
                }
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
                                    .drawWithContent { miniDraws++;drawContent() }
                                    .background(Color(0xFF7C3331)).testTag("mini")) {
                                    Image(fixturePainter, contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.align(Alignment.CenterStart).padding(start=10.dp).size(40.dp)
                                            .clip(CircleShape)
                                            .playerArtworkEndpoint("fixture", fixturePainter, mini = true, radius = 20.dp)
                                            .testTag("mini-art"))
                                    Text("PLAYER",Modifier.align(Alignment.CenterStart).padding(start=62.dp)
                                        .testTag("mini-title"),
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
                                        if (sharedDecoded) Image(fixturePainter, contentDescription = null,
                                            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
                                                .playerArtworkEndpoint("fixture", fixturePainter, mini = false, radius = 16.dp))
                                        else Box(Modifier.fillMaxSize().background(Color.Magenta))
                                    }
                                } else if (sharedDecoded) Image(fixturePainter, contentDescription = null,
                                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
                                        .clip(RoundedCornerShape(16.dp))
                                        .playerArtworkEndpoint("fixture", fixturePainter, mini = false, radius = 16.dp))
                                else Box(Modifier.fillMaxSize().background(Color.Magenta))
                            }
                            Text("PLAYER", Modifier.align(Alignment.Center)
                                .testTag("full-title"),fontSize=23.sp,color=Color.White)
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

    @Test fun coordinatedTravelAndSurfaceHandoffHaveContinuousEndpoints() {
        for (i in 0..1000) {
            val p = i / 1000f
            val mini = playerMiniDrawAlpha(p)
            val shell = playerPresentationOpacity(p)
            val coverage = shell + mini * (1f - shell)
            assertTrue("No optical hole during the handoff", coverage >= 0.985f)
            val classicShell = playerClassicSurfaceAlpha(p)
            val classicMini = playerClassicMiniAlpha(p)
            assertTrue("Classic player retains coverage at the dock",
                classicShell + classicMini * (1f - classicShell) >= 0.985f)
            assertEquals(classicShell, playerClassicContentAlpha(p), 0f)
            assertEquals(classicShell, playerClassicBackdropAlpha(p), 0f)
            if (classicMini == 0f) assertEquals(1f, classicShell, 0.00001f)
            assertTrue(playerMiniApproach(p) in 0.964f..1f)
            assertTrue(playerMiniHeightScale(p) in 0.980f..1f)
            if (p >= 0.74f) assertEquals(1f, playerContentOpacity(p), 0.00001f)
        }
        assertTrue(playerNavigationTuck(0.18f) > 0.58f)
        assertEquals(1f, playerNavigationTuck(0.32f), 0f)
        assertEquals(0f, playerNavigationTuck(0f), 0f)
        assertEquals(1f, playerMiniApproach(0f), 0f)
        assertEquals(1f, playerMiniApproach(1f), 0f)
    }

    @Test fun classicMiniDoesNotFlyWithExpandedSheetAndContentDoesNotStretch() {
        for (density in listOf(1f, 2f, 3.5f)) {
            for (i in 0..1000) {
                val p = i / 1000f
                val travel = 640f * density * p
                val remaining = 640f * density - travel
                val dockOffset = -travel + playerClassicMiniCounterTravel(travel) +
                    playerClassicMiniDockLift(p, density)
                assertTrue(dockOffset in (-5.001f * density)..0.001f)
                val lead = playerClassicContentLead(p, remaining, density)
                assertTrue(lead <= 0f && -lead <= remaining)
                assertTrue(-lead <= 28.8f * density + 0.001f)
            }
            assertEquals(0f, playerClassicContentLead(1f, 0f, density), 0f)
            assertEquals(0f, playerClassicMiniDockLift(0f, density), 0f)
        }
        assertEquals(0f, playerClassicMiniCounterTravel(-100f), 0f)
        assertEquals(0f, playerClassicMiniCounterTravel(Float.NaN), 0f)
        assertEquals(0f, playerClassicContentLead(0.5f, Float.NaN, 1f), 0f)
    }

    @Test fun physicalSpringRespondsEarlyAndSettlesWithoutRebound() {
        for (spec in listOf(PlayerOpenAnimation, PlayerCloseAnimation)) {
            val animation = TargetBasedAnimation(
                animationSpec = spec, typeConverter = Float.VectorConverter,
                initialValue = 0f, targetValue = 1000f,
            )
            fun at(ms: Long) = animation.getValueFromNanos(ms * 1_000_000L)
            assertTrue("Responsive first two frames", at(33) in 110f..150f)
            assertTrue("Most travel is complete by 200 ms", at(200) > 850f)
            var previous = 0f
            for (ms in 0L..600L) {
                val value = at(ms)
                assertTrue("Rest-to-rest spring cannot bounce", value >= previous - 0.01f)
                assertTrue(value in 0f..1000.01f)
                previous = value
            }
            assertTrue("No persistent animation", animation.durationNanos < 600_000_000L)
        }
        val reversal = TargetBasedAnimation(
            animationSpec = PlayerCloseAnimation, typeConverter = Float.VectorConverter,
            initialValue = 600f, targetValue = 0f,
            initialVelocityVector = AnimationVector1D(1200f),
        )
        assertEquals(600f, reversal.getValueFromNanos(0L), 0.01f)
        assertTrue("An interrupted spring retains the outgoing velocity",
            reversal.getValueFromNanos(1_000_000L) > 600f)
        assertTrue("The latest target wins", reversal.getValueFromNanos(150_000_000L) < 600f)
    }

    @Test fun fullPlayerMotionFinishesWithinShortBoundedTime() {
        mount()
        compose.runOnIdle { state.expandSoft() }
        advance(650)
        compose.runOnIdle { assertTrue(state.isExpanded); assertFalse(state.isAnimationRunning) }
        compose.runOnIdle { state.collapseSoft() }
        advance(650)
        compose.runOnIdle { assertTrue(state.isCollapsed); assertFalse(state.isAnimationRunning) }
    }

    @Test fun contourStartsAtRealMiniRadiusAndStaysRoundUntilFullscreen() {
        assertEquals(14f, playerPageContour(0f, 14f, 1f).radius, 0f)
        assertEquals(0f, playerPageContour(0f, 14f, 1f).bleed, 0f)
        assertTrue(playerPageContour(0.85f, 14f, 1f).bleed > 0f)
        assertTrue(playerPageContour(0.85f, 14f, 1f).radius in 9f..11f)
        assertTrue(playerPageContour(1f, 14f, 1f).bleed > 0f)
        for (i in 1..99) {
            val p = i / 100f
            assertTrue("No blank handoff between Mini and destination",
                playerMiniDrawAlpha(p) > 0f || playerPresentationOpacity(p) >= 0.99f)
        }
    }

    @Test fun closingGeometryCompressesTheActualWindowBeforeItsContentFades() {
        assertEquals(0f, playerFrameGeometryProgress(Float.NaN), 0f)
        assertEquals(0f, playerFrameGeometryProgress(0f), 0f)
        assertEquals(1f, playerFrameGeometryProgress(1f), 0f)
        var previous = 0f
        for (step in 0..100) {
            val progress = step / 100f
            val contour = playerFrameGeometryProgress(progress)
            assertTrue("no jump or inversion in the player outline", contour >= previous)
            assertTrue("closing contracts toward Mini ahead of the raw sheet travel",
                contour <= progress + 0.0001f)
            previous = contour
        }
        val halfway = playerPresentationBounds(0.5f, 360f, 700f, 590f, 48f, 12f)
        assertEquals("the frame follows the actual anchor without extra deformation",
            295f, halfway.top, 0.001f)
        assertTrue("the actual Mini is present during the close handoff",
            playerMiniDrawAlpha(0.20f) > 0f)
    }

    @Test fun embeddedLyricsPaletteIsReadableEvenForWhiteOrEmptyCovers() {
        val empty = capsuleInlineLyricsStops(emptyList())
        val bright = capsuleInlineLyricsStops(listOf(Color.White, Color.Yellow))
        assertEquals(3, empty.size)
        assertEquals(3, bright.size)
        assertTrue("the lyric floor is now cover-derived, not a fixed black stop",
            empty.last() != bright.last())
        assertTrue("The bright cover remains legible without darkening at the bottom",
            bright.last().luminance() >= bright.first().luminance() * 0.65f)
        for (stop in bright) {
            assertTrue("the lyrics card must never flash a white background", stop.red < 0.65f)
            assertTrue("the lyrics card must never flash a white background", stop.green < 0.65f)
            assertTrue("the lyrics card must never flash a white background", stop.blue < 0.65f)
        }
        assertTrue("cover crossfade must be finite, not a perpetual animated layer",
            CAPSULE_COVER_HANDOFF_MS in 160..260)
    }

    @Test fun presentationMorphsFromActualMiniBoundsToTheExactFullscreenViewport() {
        assertEquals(0f, playerPresentationOpacity(Float.NaN), 0f)
        assertEquals(0f, playerPresentationOpacity(-1f), 0f)
        assertEquals(1f, playerPresentationOpacity(2f), 0f)
        assertEquals(1f, playerPresentationScale(1f), 0f)
        assertEquals(1f, playerPresentationScale(0f), 0f)
        assertEquals(0f, playerPresentationContentY(1f, 0f, 1f), 0f)

        val source = playerPresentationBounds(0f, 360f, 700f, 590f, 48f, 12f)
        assertEquals(androidx.compose.ui.geometry.Rect(12f, 590f, 348f, 638f), source)
        val target = playerPresentationBounds(1f, 360f, 700f, 590f, 48f, 12f)
        assertEquals(androidx.compose.ui.geometry.Rect(0f, 0f, 360f, 700f), target)

        var previous = source
        var previousOpacity = 0f
        var previousScale = 0f
        for (i in 0..100) {
            val p = i / 100f
            val frame = playerPresentationBounds(p, 360f, 700f, 590f, 48f, 12f)
            val opacity = playerPresentationOpacity(p)
            val scale = playerPresentationScale(p)
            assertTrue("outer frame grows upward", frame.top <= previous.top)
            assertTrue("outer frame grows downward", frame.bottom >= previous.bottom)
            assertTrue("frame grows horizontally", frame.left <= previous.left && frame.right >= previous.right)
            assertTrue(opacity >= previousOpacity && opacity in 0f..1f)
            assertTrue(scale >= previousScale && scale in 0.94f..1f)
            previous = frame; previousOpacity = opacity; previousScale = scale
        }
    }

    @Test fun realPageContentIsUniformAndFollowsTheExpandingClip() {
        mount()
        for (fraction in listOf(0.15f, 0.45f, 0.75f, 0.95f)) {
            sampleAt(fraction)
            val p = compose.runOnIdle { state.visualProgress }
            val a = pagePoint(0f, 0f); val b = pagePoint(1f, 1f)
            val page = compose.onNodeWithTag("page", useUnmergedTree = true).fetchSemanticsNode()
            val actualScale = (b.x - a.x) / page.layoutInfo.coordinates.size.width
            val heightScale = (b.y - a.y) / page.layoutInfo.coordinates.size.height
            assertEquals("photographs cannot be stretched to fill the growing frame", actualScale, heightScale, 0.001f)
            assertEquals(playerPresentationScale(p), actualScale, 0.002f)
            val frame = playerPresentationBounds(p, with(compose.density) { 360.dp.toPx() },
                with(compose.density) { 700.dp.toPx() }, with(compose.density) { 590.dp.toPx() },
                with(compose.density) { 48.dp.toPx() }, with(compose.density) { 12.dp.toPx() })
            assertEquals("Light stays in its final layout underneath the shared artwork",
                0f, a.y, 2f)
            val poster = rawBounds("poster")
            assertEquals(with(compose.density) { 180.dp.toPx() } * actualScale, poster.width, 2f)
            assertEquals("cover stays attached to its moving content",
                a.y + with(compose.density) { 56.dp.toPx() } * actualScale, poster.top, 2f)
            capture("morph-open-${(fraction * 100).roundToInt()}").recycle()
        }
        assertEquals("do not remeasure the full player on every frame", 1, measuredContent.distinct().size)
    }

    @Test fun thePageDoesNotCollapseIntoASideStripAtAnyPointInTheMorph() {
        mount()
        for (fraction in listOf(0.10f, 0.25f, 0.45f, 0.65f, 0.85f)) {
            sampleAt(fraction)
            val a = pagePoint(0f, 0f)
            val b = pagePoint(1f, 1f)
            val p = compose.runOnIdle { state.visualProgress }
            val screenCenter = with(compose.density) { 180.dp.toPx() }
            assertEquals("content stays horizontally centered, even during close", screenCenter,
                (a.x + b.x) / 2f, 2f)
            assertEquals("content is never crushed below 95% width",
                with(compose.density) { 360.dp.toPx() } * playerPresentationScale(p),
                b.x - a.x, 2f)
            assertEquals("the content scale never stretches a portrait",
                b.x - a.x, (b.y - a.y) / 700f * 360f, 2f)
        }
    }

    @Test fun nativePhotoCircleStaysRoundThroughPortraitPagePresentation() {
        mount(portraitPoster = true)
        for (fraction in listOf(0.65f, 0.80f, 0.95f)) {
            sampleAt(fraction)
            val bitmap = capture("surface-portrait-${(fraction * 100).roundToInt()}")
            var left = bitmap.width; var top = bitmap.height; var right = -1; var bottom = -1
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                val c = bitmap.getPixel(x, y)
                if (AndroidColor.red(c) < 80 && AndroidColor.green(c) > 140 && AndroidColor.blue(c) > 140) {
                    left = minOf(left, x); top = minOf(top, y); right = maxOf(right, x); bottom = maxOf(bottom, y)
                }
            }
            assertTrue("native photograph must paint", right > left && bottom > top)
            assertEquals("no stretching during either page axis", (right - left).toFloat(), (bottom - top).toFloat(), 3f)
            bitmap.recycle()
        }
    }

    @Test fun actualArtworkAndTitlePaintTogetherAtTheEndOfSharedHandoff() {
        mount(actualArtworkCard = true)
        sampleAt(0.94f)
        val bitmap = capture("surface-complete-content")
        val poster = rawBounds("poster")
        val center = bitmap.getPixel(poster.center.x.roundToInt(), poster.center.y.roundToInt())
        assertTrue("actual artwork must paint in its own slot", AndroidColor.green(center) > 130 && AndroidColor.blue(center) > 125)
        val title = rawBounds("full-title")
        var titlePixels = 0
        for (y in title.top.toInt() until title.bottom.toInt()) for (x in title.left.toInt() until title.right.toInt()) {
            val c = bitmap.getPixel(x, y)
            // The entire page is translucent here. Detect its light glyphs after compositing
            // over the actual green app, rather than expecting already-opaque white pixels.
            if (AndroidColor.red(c) > 140 && AndroidColor.green(c) > 130 && AndroidColor.blue(c) > 130) titlePixels++
        }
        assertTrue("title must arrive with the photograph", titlePixels > 30)
        bitmap.recycle()
    }

    @Test fun sharedArtworkTravelsFromMiniToFullWithoutChangingItsTrack() {
        mount()
        val source = rawBounds("mini-art")
        sampleAt(0.40f)
        val moving = capture("shared-artwork-in-flight")
        val ink = yellowBounds(moving)
        assertTrue("one decoded image grows out of the compact source", ink.width() > source.width * 1.15f)
        assertTrue("the cover actually travels upward", ink.centerY() < source.center.y)
        moving.recycle()
        advance(700)
        val settled = capture("shared-artwork-settled")
        val end = yellowBounds(settled)
        val destination = rawBounds("poster")
        assertEquals(destination.center.x, end.exactCenterX(), 4f)
        assertEquals(destination.center.y, end.exactCenterY(), 4f)
        settled.recycle()
    }

    @Test fun materialHandoffAndArtworkStayWithinOnePhysicalContainer() {
        val dock = androidx.compose.ui.geometry.Rect(22f, 594f, 62f, 634f)
        val target = androidx.compose.ui.geometry.Rect(60f, 56f, 300f, 296f)
        var lastMaterial = 0f
        var lastScene = 0f
        var lastMini = 1f
        for (i in 0..1000) {
            val p = i / 1000f
            val frame = playerPresentationBounds(p, 360f, 700f, 590f, 48f, 12f)
            for (start in listOf(0f, 0.005f, 0.10f)) {
                val art = playerContainedArtworkBounds(dock, target, playerArtworkTravel(p, start), frame)
                assertTrue(art.left >= frame.left - 0.001f && art.top >= frame.top - 0.001f)
                assertTrue(art.right <= frame.right + 0.001f && art.bottom <= frame.bottom + 0.001f)
            }
            val material = playerMaterialReveal(p)
            val scene = playerAtomicSceneAlpha(p)
            val mini = playerMiniMaterialAlpha(p)
            assertTrue(material >= lastMaterial - 0.00001f && material in 0f..1f)
            assertTrue(scene >= lastScene - 0.00001f && scene in 0f..1f)
            assertTrue(mini <= lastMini + 0.00001f && mini in 0f..1f)
            lastMaterial = material
            lastScene = scene
            lastMini = mini
        }
        assertEquals(1f, lastMaterial, 0f)
        assertEquals(1f, lastScene, 0f)
        assertEquals(0f, lastMini, 0f)
        assertEquals(1f, playerMiniMaterialAlpha(0f), 0f)
        assertEquals(0f, playerAtomicSceneAlpha(0f), 0f)
        assertEquals(0f, playerSharedForegroundOpacity(0.60f), 0f)
        assertEquals(1f, playerSharedForegroundOpacity(0.92f), 0f)
        assertEquals(0f, playerAtomicContentY(Float.NaN), 0f)
    }

    @Test fun sharedTravelDoesNotExposeDuplicateDestinationOrCropThePhoto() {
        val dock = androidx.compose.ui.geometry.Rect(22f, 594f, 62f, 634f)
        for (target in listOf(
            androidx.compose.ui.geometry.Rect(56f, 48f, 304f, 296f),
            androidx.compose.ui.geometry.Rect(12f, 12f, 348f, 540f),
            androidx.compose.ui.geometry.Rect(120f, 72f, 240f, 192f),
        )) {
            var lastForeground = 0f
            for (i in 0..1000) {
                val p = i / 1000f
                val frame = playerPresentationBounds(p, 360f, 700f, 590f, 48f, 12f)
                val art = playerContainedArtworkBounds(dock, target, playerArtworkTravel(p, 0.005f), frame)
                assertTrue(art.left >= frame.left - 0.001f && art.top >= frame.top - 0.001f)
                assertTrue(art.right <= frame.right + 0.001f && art.bottom <= frame.bottom + 0.001f)
                val foreground = playerSharedForegroundOpacity(p)
                assertTrue(foreground >= lastForeground - 0.00001f)
                if (p <= 0.60f) assertEquals(0f, foreground, 0f)
                assertEquals(frame.top, playerAtomicContentY(frame.top), 0f)
                lastForeground = foreground
            }
        }
    }

    @Test fun glassDockAndSharedArtworkHaveOneContinuousOwnershipHandoff() {
        val dock = playerPresentationBounds(0f, 360f, 700f, 590f, 48f, 12f)
        var lastShell = 0f
        var lastSource = 1f
        var lastNative = 0f
        var lastGeometry = 0f
        for (i in 0..1000) {
            val p = i / 1000f
            val shell = playerPresentationOpacity(p)
            val outer = playerMiniDrawAlpha(p)
            val source = outer * playerMiniForegroundLayerAlpha(p)
            val material = outer * playerLayerAlpha(playerMiniMaterialAlpha(p), outer)
            val native = playerSharedDestinationAlpha(p)
            val geometry = playerFrameGeometryProgress(p)
            assertEquals("Source and shell share ownership", 1f, source + shell, 0.001f)
            assertTrue("Opaque dock material stays covered", material + shell * (1f - material) >= 0.985f)
            assertTrue(shell >= lastShell - 0.00001f)
            assertTrue(source <= lastSource + 0.00001f)
            assertTrue(native >= lastNative - 0.00001f)
            assertTrue(geometry >= lastGeometry - 0.00001f)
            assertEquals("Glass samples fixed Home geometry", 1f, playerSceneScale(p), 0f)
            if (p <= 0.035f) {
                assertEquals(0f, shell, 0f)
                assertEquals(dock, playerPresentationBounds(p, 360f, 700f, 590f, 48f, 12f))
                assertEquals(1f, source, 0.001f)
            }
            if (p <= 0.995f) assertEquals(1f, playerSharedOverlayAlpha(p), 0f)
            assertTrue("Dock handoff cannot accelerate into a second jerk", geometry - lastGeometry <= 0.00190f)
            lastShell = shell
            lastSource = source
            lastNative = native
            lastGeometry = geometry
        }
        assertEquals(1f, lastShell, 0f)
        assertEquals(1f, lastNative, 0f)
        assertEquals(0f, playerSharedOverlayAlpha(1f), 0f)
        assertEquals(0f, lastSource, 0f)
    }

    @Test fun sharedBoundsRemainFiniteAndReversibleWithAsymmetricInsets() {
        val source = androidx.compose.ui.geometry.Rect(38f, 590f, 78f, 630f)
        val target = androidx.compose.ui.geometry.Rect(80f, 56f, 280f, 256f)
        assertEquals(source, playerArtworkBounds(source, target, 0f))
        assertEquals(target, playerArtworkBounds(source, target, 1f))
        assertEquals(source, playerArtworkBounds(source, target, Float.NaN))
        for (i in 0..1000) {
            val p = i / 1000f
            val r = playerArtworkBounds(source, target, p)
            assertTrue(r.width in 40f..200f && r.height in 40f..200f)
            assertEquals(r, playerArtworkBounds(source, target, p))
        }
        assertEquals(androidx.compose.ui.geometry.Rect(12f, 590f, 330f, 638f),
            playerPresentationBounds(0f, 360f, 700f, 590f, 48f, 12f, 30f))
        assertEquals(androidx.compose.ui.geometry.Rect(0f, 0f, 360f, 700f),
            playerPresentationBounds(1f, 360f, 700f, 590f, 48f, 12f, 30f))
    }

    @Test fun lateArtworkChangesDoNotRestartTheWindowOrReplaceItsPage() {
        mount(actualArtworkCard = true, decodedArtwork = false)
        sampleAt(0.90f)
        val before = compose.runOnIdle { state.visualProgress }
        capture("surface-artwork-loading").recycle()
        compose.runOnIdle { sharedDecoded = true }; advance(16)
        compose.runOnIdle {
            assertTrue(state.visualProgress >= before)
            assertEquals(1, contentMounts); assertEquals(0, contentDisposals)
        }
        advance(700)
        val bitmap = capture("surface-artwork-ready")
        val poster = rawBounds("poster")
        assertEquals(AndroidColor.rgb(255, 191, 76), bitmap.getPixel(
            (poster.left + poster.width * 0.2f).roundToInt(),
            (poster.top + poster.height * 0.2f).roundToInt()))
        bitmap.recycle()
    }

    @Test fun idleCompactButtonsKeepTheirActualTouchTargetsAndDoNotOpenThePlayer() {
        mount()
        compose.onNodeWithTag("mini-control", useUnmergedTree = true).performTouchInput { click(center) }
        advance(32)
        compose.runOnIdle { assertEquals(1, compactClicks); assertTrue(state.isCollapsed) }
        compose.onNodeWithTag("navigation").performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(1, navigationClicks); assertTrue(state.isCollapsed) }
    }

    @Test fun closingRetreatsAsOnePageAndLeavesNoArtworkOrInputLayerBelowMini() {
        mount(); val source = rawBounds("mini-art")
        compose.runOnIdle { state.expandSoft() }; advance(700)
        compose.runOnIdle { state.collapseSoft() }
        var sampled = false
        repeat(45) {
            advance(16)
            val p = compose.runOnIdle { state.visualProgress }
            if (!sampled && p in 0.15f..0.5f) {
                val a = pagePoint(0f, 0f)
                assertEquals(0f, a.y, 2f)
                capture("surface-closing").recycle(); sampled = true
            }
        }
        assertTrue(sampled)
        val bitmap = capture("closed-no-ghost")
        val ink = yellowBounds(bitmap)
        assertEquals(source.left, ink.left.toFloat(), 3f)
        assertEquals(source.width, ink.width().toFloat(), 4f)
        val navY = with(compose.density) { 670.dp.toPx() }.roundToInt()
        assertEquals(AndroidColor.BLUE, bitmap.getPixel(bitmap.width / 2, navY))
        bitmap.recycle()
        compose.onNodeWithTag("navigation").performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(1, navigationClicks) }
    }

    @Test fun settledPlayerIsExactlyIdentityAndAcceptsItsNativeControls() {
        mount(); compose.runOnIdle { state.expandSoft() }; advance(700)
        val a = pagePoint(0f, 0f); val b = pagePoint(1f, 1f)
        assertEquals(0f, a.x, 1f); assertEquals(0f, a.y, 1f)
        assertEquals(with(compose.density) { 360.dp.toPx() }, b.x, 1f)
        assertEquals(with(compose.density) { 700.dp.toPx() }, b.y, 1f)
        compose.onNodeWithTag("control").performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(1, controlClicks) }
    }

    @Test fun emergingPageControlsCannotReceiveAnEarlyTap() {
        mount(); sampleAt(0.35f)
        compose.onNodeWithTag("control").performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(0, controlClicks) }
        advance(700)
        compose.onNodeWithTag("control").performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(1, controlClicks) }
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

    @Test fun firstTapPreparesThePlayerThenMovesWithinThreeDisplayFrames() {
        mount()
        compose.onNodeWithTag("mini",useUnmergedTree=true).performTouchInput {click(center)}
        // Press preparation mounts the destination without a forced frame delay.
        advance(64)
        compose.runOnIdle {
            assertTrue(state.isExpandedOrExpanding)
            assertEquals(1, contentMounts)
            assertTrue(state.visualProgress > 0.005f)
        }
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

    @Test fun openingAndClosingUseTheSameMovingFrameWithoutAVisualTeleport() {
        mount()
        compose.runOnIdle { state.expandSoft() }
        advance(64)
        compose.runOnIdle {
            assertTrue("prepare the first frame before the motion starts", state.visualProgress > 0.005f)
            assertTrue("the moving container is already visible", playerPresentationOpacity(state.visualProgress) > 0f)
        }
        advance(96)
        compose.runOnIdle {
            val p = state.visualProgress
            val frame = playerPresentationBounds(p, 360f, 700f, 590f, 48f, 12f)
            assertTrue("frame travels toward the actual expanded anchor", frame.top in 0f..520f)
            assertTrue("real artwork must not collapse into a narrow offset strip",
                playerPresentationScale(p) >= 0.95f)
        }
        advance(600)
        compose.runOnIdle { assertTrue(state.isExpanded); state.collapseSoft() }
        advance(32)
        compose.runOnIdle {
            assertTrue("closing begins from the existing page rather than fading it out",
                playerPresentationOpacity(state.visualProgress) > 0.90f)
        }
        advance(144)
        compose.runOnIdle {
            val p = state.visualProgress
            val frame = playerPresentationBounds(p, 360f, 700f, 590f, 48f, 12f)
            assertTrue("the closing frame travels into the compact card", frame.top in 20f..590f)
            assertTrue("Mini must reappear before the shrinking frame reaches it", playerMiniDrawAlpha(p) > 0f)
        }
        advance(600)
        compose.runOnIdle { assertTrue(state.isCollapsed); assertFalse(state.isAnimationRunning) }
    }

    @Test fun reversalContinuesFromTheSamePositionAndLatestIntentWins() {
        mount();compose.runOnIdle {state.expandSoft()};advance(96)
        val before=compose.runOnIdle {state.value.value}
        compose.runOnIdle {state.collapseSoft();assertEquals(before,state.value.value,0.01f)}
        // A spring brakes the inherited velocity, then turns toward the newest target.
        advance(64)
        val turning=compose.runOnIdle {state.value.value}
        assertTrue("reversal brakes toward the latest anchor: $before -> $turning", turning <= before + 0.01f)
        advance(160)
        val closing=compose.runOnIdle {state.value.value}
        assertTrue("the latest closing intent must turn the same page: $before -> $closing",closing<before)
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
        val p=mutableStateOf(0.40f)
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
        val start=ink("rounded-attached");compose.runOnIdle{p.value=0.88f};val leaving=ink("rounded-leaving")
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
