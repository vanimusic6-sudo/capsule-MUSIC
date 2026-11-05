package com.nikhil.yt.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.nikhil.yt.LocalPlayerAwareWindowInsets
import com.nikhil.yt.R
import com.nikhil.yt.ui.component.PersistentPlaylistAction
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A DOM/string assertion cannot prove the floating button actually draws. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w393dp-h851dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PersistentPlaylistFabTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun createAndSaveActionsAreVisibleAndClickableAboveTheContent() {
        var visible by mutableStateOf(true)
        var saved by mutableStateOf(false)
        var clicks = 0
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalPlayerAwareWindowInsets provides WindowInsets()) {
                    Box(Modifier.size(393.dp, 600.dp)) {
                        PersistentPlaylistAction(
                            visible = visible,
                            icon = if (saved) R.drawable.library_add_check else R.drawable.add,
                            onClick = { clicks++; saved = true },
                        )
                    }
                }
            }
        }
        compose.onNodeWithTag("capsule:playlist-floating-action").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, clicks) }
        compose.onNodeWithTag("capsule:playlist-floating-action").assertIsDisplayed()
        compose.runOnIdle { visible = false }
        compose.onNodeWithTag("capsule:playlist-floating-action").assertDoesNotExist()
        compose.runOnIdle { visible = true }
        compose.onNodeWithTag("capsule:playlist-floating-action").assertIsDisplayed()
    }
}
