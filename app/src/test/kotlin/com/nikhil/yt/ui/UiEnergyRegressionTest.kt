package com.nikhil.yt.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cheap source-level guards for the UI work that exists specifically to avoid warming the phone.
 *
 * These do not benchmark thermals. They pin the conditions that prevent hidden/paused decorative
 * work from quietly returning during later visual refactors.
 */
class UiEnergyRegressionTest {
    private fun source(relative: String): String {
        val candidates =
            listOf(
                File("src/main/kotlin/$relative"),
                File("app/src/main/kotlin/$relative"),
            )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("Could not find $relative")
    }

    @Test
    fun `mini player procedural motion stops when playback is not active`() {
        val source = source("com/nikhil/yt/ui/player/CapsuleMiniPlayer.kt")
        assertTrue(
            source.contains(
                Regex(
                    """animated\s*=\s*visualsActive\s*&&\s*isPlaying\s*&&\s*playbackState\s*==\s*Player.STATE_READY""",
                ),
            ),
        )
    }

    @Test
    fun `collapsed player refresh is slower than expanded player refresh`() {
        val source = source("com/nikhil/yt/ui/player/Player.kt")
        assertTrue(source.contains("state.isExpanded -> 300L"))
        assertTrue(source.contains("else -> 1_000L"))
        assertTrue(source.contains("!isPlaying -> 1_500L"))
    }

    @Test
    fun `rapid track changes do not immediately start high resolution visual preloads`() {
        val source = source("com/nikhil/yt/ui/player/CapsuleMediaPreload.kt")
        assertTrue(source.contains("CAPSULE_VISUAL_PREFETCH_SETTLE_MS = 1_200L"))
        assertTrue(source.contains("delay(CAPSULE_VISUAL_PREFETCH_SETTLE_MS)"))
    }

    @Test
    fun `single artist tracks do not preload chooser portraits`() {
        val source = source("com/nikhil/yt/ui/component/ArtistSelectionItem.kt")
        assertTrue(source.contains("if (targets.size < 2) return@LaunchedEffect"))
    }

    @Test
    fun `download rows ignore unrelated download map updates`() {
        val source = source("com/nikhil/yt/playback/DownloadUtil.kt")
        assertTrue(source.contains(".distinctUntilChanged()"))
    }

    @Test
    fun `live search suggestions are debounced`() {
        val source = source("com/nikhil/yt/viewmodels/OnlineSearchSuggestionViewModel.kt")
        assertTrue(source.contains(".debounce(180L)"))
    }

    @Test
    fun `home hero artwork is decoded at display size`() {
        val source = source("com/nikhil/yt/ui/screens/HomeScreenComponents.kt")
        assertTrue(source.contains(".size(Size(requestWidthPx, requestHeightPx))"))
        assertTrue(!source.contains("song.song.thumbnailUrl?.toHighResThumbnail()"))
    }
}
