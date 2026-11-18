package com.nikhil.yt.ui.player

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.nikhil.yt.constants.MiniPlayerHeight
import com.nikhil.yt.models.MediaMetadata
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Vertical glyph visibility in the actual 64dp dock at narrow device widths. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w320dp-h640dp-xhdpi")
class CapsuleMiniMetadataLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun assertMetadataFits(fontScale: Float, explicit: Boolean) {
        val metadata = MediaMetadata(
            id = "mini-narrow", title = "Life Goes On",
            artists = listOf(MediaMetadata.Artist(null, "Giulio Cercato")),
            duration = 148, explicit = explicit,
        )
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
            ) {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Box(
                        Modifier.size(220.dp, MiniPlayerHeight)
                            .clipToBounds().testTag("mini"),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize().padding(
                                horizontal = 10.dp,
                                vertical = capsuleMiniMetadataVerticalPadding(fontScale),
                            ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Spacer(Modifier.width(44.dp))
                            Spacer(Modifier.width(12.dp))
                            CapsuleMiniSongMetadata(
                                mediaMetadata = metadata,
                                hasError = false,
                                visible = false,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(8.dp))
                            Spacer(Modifier.width(32.dp))
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val panel = compose.onNodeWithTag("mini").fetchSemanticsNode().boundsInRoot
        val title = compose.onNodeWithText("Life Goes On", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val artist = compose.onNodeWithText("Giulio Cercato", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertTrue("Title top is clipped at $fontScale: $title / $panel",
            title.top >= panel.top)
        assertTrue("Artist is clipped below the dock at $fontScale: $artist / $panel",
            artist.bottom <= panel.bottom)
        assertTrue("Artist and title collide at $fontScale",
            artist.top >= title.bottom)
        if (explicit) {
            compose.onNodeWithText("E", useUnmergedTree = true).assertExists()
        }
    }

    @Test fun narrowPhoneDisplaysArtistAtLargeFontScale() =
        assertMetadataFits(fontScale = 1.6f, explicit = false)

    @Test fun narrowPhoneKeepsArtistVisibleWithExplicitBadgeAndLargeText() =
        assertMetadataFits(fontScale = 1.9f, explicit = true)
}
