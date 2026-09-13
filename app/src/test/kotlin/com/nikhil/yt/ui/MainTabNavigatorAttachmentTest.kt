package com.nikhil.yt.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import com.nikhil.yt.ui.screens.MainTabNavigator
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MainTabNavigatorAttachmentTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun attachBeforeNavHostDoesNotTouchUnattachedComposeNavigatorState() {
        var attachedWithoutCrash = false

        compose.setContent {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            val earlyController =
                remember(context) {
                    NavHostController(context).apply {
                        navigatorProvider.addNavigator(ComposeNavigator())
                    }
                }

            DisposableEffect(earlyController, scope) {
                val earlyNavigator = MainTabNavigator(earlyController, scope)
                earlyNavigator.attach()
                attachedWithoutCrash = true
                onDispose { earlyNavigator.detach() }
            }
        }

        compose.waitForIdle()
        assertTrue(attachedWithoutCrash)
    }
}
