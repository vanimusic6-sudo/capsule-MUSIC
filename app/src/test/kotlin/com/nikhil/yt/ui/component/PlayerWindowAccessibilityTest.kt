package com.nikhil.yt.ui.component

import android.app.Application
import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.nikhil.yt.constants.MiniPlayerBackgroundStyle
import com.nikhil.yt.ui.player.CapsuleCompactSurfaceBackground
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

/**
 * The field crash was raised by AndroidComposeViewAccessibilityDelegateCompat's deferred
 * semantics checker forcing a remeasure, not by a tap handler. Exercise that owner on API 37
 * while a Scaffold/lazy underlay recycles content and player presentation nodes change lifetime.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class, qualifiers = "w400dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlayerWindowAccessibilityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var state: BottomSheetState
    private lateinit var scope: CoroutineScope
    private var scroll: (Int) -> Unit = {}
    private val shown = mutableStateOf(true)
    private val revision = mutableIntStateOf(0)
    private var accessibilityQueries = 0
    private lateinit var accessibilityManager: AccessibilityManager
    private var accessibilityEventsAtMount = 0

    private fun mount() {
        val manager = RuntimeEnvironment.getApplication().getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        accessibilityManager = manager
        shadowOf(manager).setEnabled(true)
        shadowOf(manager).setTouchExplorationEnabled(true)
        // Compose intentionally ignores an enabled manager with an empty service list (the
        // UIAutomator case). A real spoken service is required to schedule its semantics checker.
        val service = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_SPOKEN
            flags = AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
        }
        shadowOf(manager).setEnabledAccessibilityServiceList(listOf(service))
        assertTrue(manager.isEnabled)
        assertTrue(manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).isNotEmpty())
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                state = rememberBottomSheetState(0.dp, 700.dp, 110.dp,
                    initialAnchor = COLLAPSED_ANCHOR, playerMotion = true)
                PlayerTransitionLifetime(state, shown.value)
                scope = rememberCoroutineScope()
                val list = rememberLazyListState()
                scroll = { index -> scope.launch { list.scrollToItem(index) } }
                val blocked by remember { derivedStateOf {
                    state.isExpandedOrExpanding || shouldRenderExpandedSurface(state.visualProgress, state.isDismissed)
                } }
                Box(Modifier.size(360.dp,700.dp)) {
                    PlayerSceneBackdrop(progress={state.visualProgress},blocked=blocked) {
                        Scaffold(bottomBar={Text("Navigation ${revision.intValue}",Modifier.height(60.dp))}) { padding ->
                            LazyColumn(Modifier.fillMaxSize().padding(padding),state=list) {
                                items((0 until 80).toList(),key={it}) { index ->
                                    Text("Item $index revision ${revision.intValue}",Modifier.fillMaxWidth().height(56.dp))
                                }
                            }
                        }
                    }
                    if (shown.value) {
                        BottomSheet(
                            state=state,backgroundColor=Color(0xFF28242C),gesturesEnabled=false,
                            collapsedContentHeight=48.dp,collapsedHorizontalInset=12.dp,
                            collapsedTopCornerRadius=14.dp,backHandlerEnabled=false,
                            collapsedContent={
                                if (state.compactSurfaceVisible) Text("Mini ${revision.intValue}",Modifier.fillMaxSize())
                                else Box(Modifier.fillMaxSize())
                            },
                            compactBackground={
                                CapsuleCompactSurfaceBackground(MiniPlayerBackgroundStyle.GRADIENT,false,
                                    listOf(Color(0xFF683F5A),Color(0xFF284250),Color(0xFF242831)),
                                    Modifier.fillMaxSize(),animated=false)
                            },
                        ) {
                            Box(Modifier.fillMaxSize().background(Color(0xFF28242C)).testTag("page")) {
                                Text("Player ${revision.intValue}",Modifier.padding(24.dp))
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        queryAccessibility()
        accessibilityEventsAtMount = shadowOf(manager).getSentAccessibilityEvents().size
    }

    private fun findOwner(view: View): View? {
        if (view.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView") return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findOwner(view.getChildAt(index))?.let { return it }
        return null
    }

    private fun queryAccessibility() {
        compose.runOnIdle {
            val owner = findOwner(compose.activity.findViewById(android.R.id.content))
            assertNotNull("test must exercise the real Compose accessibility owner",owner)
            assertTrue(owner!!.isAttachedToWindow)
            owner.requestLayout()
            val provider = owner.accessibilityNodeProvider
            assertNotNull("accessibility provider must be enabled",provider)
            assertNotNull(provider!!.createAccessibilityNodeInfo(View.NO_ID))
            accessibilityQueries++
        }
        // Flush the same delayed semantics-change runnable that appears in the field stack.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
    }

    private fun frames(count: Int) {
        repeat(count) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            queryAccessibility()
        }
    }

    @Test fun accessibilityRemeasureSurvivesRapidReversalsAndLazyUnderlayChanges() {
        mount()
        repeat(16) { cycle ->
            compose.runOnIdle { state.expandSoft();revision.intValue++;scroll(cycle*3%50) }
            frames(2+cycle%7)
            compose.runOnIdle { state.collapseSoft();revision.intValue++ }
            frames(2+cycle%5)
        }
        compose.runOnIdle { state.collapseSoft() }
        frames(45)
        compose.runOnIdle { assertTrue(state.isCollapsed);assertFalse(state.isAnimationRunning) }
        assertTrue(accessibilityQueries>100)
        assertTrue("the deferred accessibility checker must actually emit events",
            shadowOf(accessibilityManager).getSentAccessibilityEvents().size>accessibilityEventsAtMount)
    }

    @Test fun accessibilityRemeasureSurvivesHostRemovalAndRestorationDuringMotion() {
        mount()
        repeat(6) {
            compose.runOnIdle { state.expandSoft() };frames(3)
            compose.runOnIdle { shown.value=false };frames(2)
            compose.runOnIdle { shown.value=true;revision.intValue++ };frames(2)
            compose.runOnIdle { state.collapseSoft() };frames(4)
        }
        compose.runOnIdle { state.collapseSoft() };frames(45)
        compose.runOnIdle { assertTrue(state.isCollapsed);assertFalse(state.isAnimationRunning) }
        assertTrue(accessibilityQueries>80)
        assertTrue("the deferred accessibility checker must actually emit events",
            shadowOf(accessibilityManager).getSentAccessibilityEvents().size>accessibilityEventsAtMount)
    }
}
