package com.nikhil.yt.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.nikhil.yt.ui.component.ArtistSelectionRow
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArtistSelectionRowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun loadedPortraitAppearsBesideTheNameWithoutMovingTheRowAndKeepsSelectionWorking() {
        var portrait by mutableStateOf<Bitmap?>(null)
        var clicks = 0
        compose.setContent {
            MaterialTheme { ArtistSelectionRow("Artist", "artist-id", portrait) { clicks++ } }
        }
        val avatar = compose.onNodeWithTag("artistPortrait:artist-id", useUnmergedTree = true)
        val name = compose.onNodeWithTag("artistName:artist-id", useUnmergedTree = true)
        val before = avatar.fetchSemanticsNode().boundsInRoot
        val nameBefore = name.fetchSemanticsNode().boundsInRoot
        assertTrue(before.right <= nameBefore.left)
        compose.runOnIdle {
            portrait = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) }
        }
        compose.waitUntil(5_000) {
            var pixel = 0
            compose.runOnIdle {
                val root = compose.activity.findViewById<View>(android.R.id.content)
                val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                root.draw(Canvas(bitmap))
                pixel = bitmap.getPixel(before.center.x.roundToInt(), before.center.y.roundToInt())
                bitmap.recycle()
            }
            android.graphics.Color.red(pixel) > 240 && android.graphics.Color.green(pixel) < 20
        }
        assertEquals(before, avatar.fetchSemanticsNode().boundsInRoot)
        assertEquals(nameBefore, name.fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithText("Artist").performClick()
        compose.runOnIdle { assertEquals(1, clicks) }
    }
}
