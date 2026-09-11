package com.nikhil.yt.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.nikhil.yt.R
import com.nikhil.yt.ui.component.StandardChrome
import com.nikhil.yt.ui.component.StandardHeaderTitle
import com.nikhil.yt.ui.component.StandardHomeChips
import com.nikhil.yt.ui.component.StandardNavigationBar
import com.nikhil.yt.ui.screens.Screens
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w393dp-h851dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StandardChromeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun standardHeaderChipsAndNavigationRemainInteractive() {
        var route by mutableStateOf(Screens.Home.route)
        var chip by mutableStateOf("Energy")
        var accountClicks = 0
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Column(Modifier.fillMaxWidth().background(StandardChrome.background).testTag("standardChrome")) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 16.dp)) {
                        StandardHeaderTitle("V", null) { accountClicks++ }
                    }
                    StandardHomeChips(listOf("Energy" to "Energy", "On the road" to "On the road"), chip) { chip = it }
                    Spacer(Modifier.height(48.dp))
                    StandardNavigationBar(Modifier.fillMaxWidth().height(80.dp), Screens.MainScreens, route) { route = it.route }
                }
            }
        }
        compose.onNodeWithContentDescription(RuntimeEnvironment.getApplication().getString(R.string.account)).performClick()
        assertEquals(1, accountClicks)
        compose.onNodeWithText("On the road").performClick().assertIsSelected()
        val history = RuntimeEnvironment.getApplication().getString(R.string.history)
        compose.onNodeWithText(history).performClick().assertIsSelected()
        assertEquals(Screens.History.route, route)
        compose.waitForIdle()
        val screenshot = compose.onNodeWithTag("standardChrome").captureToImage().asAndroidBitmap()
        val output = File("build/reports/ui-previews/standard-chrome.png")
        output.parentFile.mkdirs()
        output.outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
