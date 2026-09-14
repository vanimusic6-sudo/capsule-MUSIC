package com.nikhil.yt.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import com.nikhil.yt.ui.component.BackRepeatGuard
import com.nikhil.yt.ui.screens.routeComposable
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Holding the navigation bar's Back button makes the system deliver a stream of back events, and
 * each one pops another destination — a long press walks several screens down the settings tree
 * instead of stepping back once.
 *
 * The guard's placement is as important as its logic, and is the part that is easy to get wrong:
 * back callbacks are consulted newest-first, and Material's `Scaffold` subcomposes its content
 * during the measure pass. A guard registered from the outer composition is therefore registered
 * *before* navigation's own callback and never sees an event. So this test uses the same shape as
 * the app — NavHost inside a Scaffold, guard after it — rather than a flat composition that would
 * pass either way.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35, 36], application = Application::class)
class BackRepeatGuardTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var controller: NavHostController

    /** Explicit clock: Robolectric does not move SystemClock in step with Compose's test clock. */
    private var nowMillis = 1_000L

    private fun showSettingsStack(withGuard: Boolean) {
        compose.setContent {
            MaterialTheme {
                controller = rememberNavController()
                Scaffold { padding ->
                    Box(Modifier.fillMaxSize()) {
                        NavHost(controller, startDestination = "settings") {
                            listOf(
                                "settings",
                                "settings/appearance",
                                "settings/appearance/palette_picker",
                            ).forEach { route ->
                                routeComposable(route) { Box(Modifier.fillMaxSize()) }
                            }
                        }
                        if (withGuard) BackRepeatGuard(enabled = true, now = { nowMillis })
                        padding.calculateTopPadding()
                    }
                }
            }
        }
        compose.waitForIdle()

        compose.runOnIdle { controller.navigate("settings/appearance") }
        compose.waitForIdle()
        compose.runOnIdle { controller.navigate("settings/appearance/palette_picker") }
        compose.waitForIdle()
        assertEquals("settings/appearance/palette_picker", controller.currentDestination?.route)
    }

    private fun pressBack(times: Int, gapMillis: Long) {
        repeat(times) {
            compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
            nowMillis += gapMillis
            compose.waitForIdle()
        }
    }

    @Test fun aHeldBackButtonStepsBackExactlyOnce() {
        showSettingsStack(withGuard = true)

        // The stream a held button produces: many events, tens of milliseconds apart.
        pressBack(times = 6, gapMillis = 12L)

        assertEquals(
            "a long press walked past one screen",
            "settings/appearance",
            controller.currentDestination?.route,
        )
    }

    @Test fun withoutTheGuardTheSameStreamWalksTheWholeStack() {
        showSettingsStack(withGuard = false)

        pressBack(times = 6, gapMillis = 12L)

        // Proof that the scenario is real and that the guard is what changes the outcome.
        assertEquals("settings", controller.currentDestination?.route)
    }

    @Test fun theVeryFirstPressIsNeverSwallowed() {
        // Regression: the guard held its previous timestamp in a Long sentinel, and
        // `now - Long.MIN_VALUE` overflows to a negative number. That reads as "too soon" forever,
        // so every back press was dropped and Back stopped working entirely.
        showSettingsStack(withGuard = true)

        pressBack(times = 1, gapMillis = 0L)

        assertEquals(
            "the first back press after start-up was swallowed",
            "settings/appearance",
            controller.currentDestination?.route,
        )
    }

    @Test fun aClockThatStartsAtZeroStillLetsTheFirstPressThrough() {
        nowMillis = 0L
        showSettingsStack(withGuard = true)

        pressBack(times = 1, gapMillis = 0L)

        assertEquals("settings/appearance", controller.currentDestination?.route)
    }

    @Test fun deliberateSeparatePressesStillEachStepBack() {
        showSettingsStack(withGuard = true)

        // Two presses at human speed must both count, or Back would feel broken.
        pressBack(times = 1, gapMillis = 400L)
        assertEquals("settings/appearance", controller.currentDestination?.route)

        pressBack(times = 1, gapMillis = 400L)
        assertEquals("settings", controller.currentDestination?.route)
    }
}
