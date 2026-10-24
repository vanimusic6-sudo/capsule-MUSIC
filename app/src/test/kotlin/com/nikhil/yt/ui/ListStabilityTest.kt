package com.nikhil.yt.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.CompositionLocalProvider
import com.nikhil.yt.ui.component.GridItem
import com.nikhil.yt.ui.component.GridSubtitle
import com.nikhil.yt.ui.component.VeluneLoader
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h800dp-xhdpi")
class ListStabilityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun partialCatalogArrivalsDoNotReshuffleAPublishedDiscoveryShelf() {
        val state = com.nikhil.yt.ui.screens.CatalogHomeState()
        fun track(i: Int) = com.nikhil.yt.discovery.ExperimentalCatalog.Track(
            source = com.nikhil.yt.discovery.ExperimentalCatalog.Source.AUDIUS, id = "$i", title = "Track $i",
            artist = "Artist $i", artwork = null, streamUrl = "https://example.org/$i.mp3", sourcePage = "",
            durationSeconds = 180)
        fun lane(tracks: List<com.nikhil.yt.discovery.ExperimentalCatalog.Track>) =
            com.nikhil.yt.discovery.ExperimentalCatalog.HomeLane(com.nikhil.yt.discovery.HomeFeedSource.AUDIUS,
                com.nikhil.yt.discovery.ExperimentalCatalog.HomeLaneKind.TRENDING, tracks)
        state.lanes = listOf(lane((1..10).map(::track)))
        state.publishDiscovery(emptyList(), 42)
        val published = state.discoverySnapshot
        state.lanes = listOf(lane((1..50).map(::track)))
        assertSame(published, state.discoverySnapshot)
        state.publishDiscovery(emptyList(), 42)
        assertEquals(com.nikhil.yt.discovery.ExperimentalRanking.select((1..50).map(::track), dayKey = 42),
            state.discoverySnapshot)
    }
    @Test fun metadataAndDownloadChangesDoNotMoveTheNextShelfEvenAtLargeFont() {
        var subtitle by mutableStateOf("Artist")
        var downloading by mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                    Column(Modifier.width(320.dp)) {
                        GridItem(title = { Text("Track", maxLines = 1) },
                            subtitle = { GridSubtitle(subtitle) },
                            badges = { if (downloading) VeluneLoader(size = 18.dp) },
                            thumbnailContent = { Box(Modifier.fillMaxSize()) },
                            modifier = Modifier.testTag("card"))
                        Box(Modifier.width(100.dp).testTag("following"))
                    }
                }
            }
        }
        val card = compose.onNodeWithTag("card").fetchSemanticsNode().boundsInRoot
        val below = compose.onNodeWithTag("following").fetchSemanticsNode().boundsInRoot.top
        compose.runOnIdle { subtitle = "A much longer artist credit that wraps over two metadata lines"; downloading = true }
        assertEquals(card.height, compose.onNodeWithTag("card").fetchSemanticsNode().boundsInRoot.height, 1f)
        assertEquals(below, compose.onNodeWithTag("following").fetchSemanticsNode().boundsInRoot.top, 1f)
    }
}
