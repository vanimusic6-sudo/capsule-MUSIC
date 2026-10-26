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
    fun `immersive video motion keeps a single gradient and disposes invisible preloader`() {
        val src = source("com/nikhil/yt/ui/player/CapsuleImmersiveContent.kt")
        assertTrue(src.contains("capsuleImmersiveNeedsArtworkPreload(coverImageReady, artworkTone.ready"))
        assertTrue(src.contains("capsuleImmersiveArtworkOpacity("))
        // Immersion now paints ONE even cheaper opaque pixel-exact album
        // background instead of its former full-screen gradient shader.
        // Guard against accidental second full-screen paint in either mode.
        val solid = src.split("Box(modifier = Modifier.fillMaxSize().background(seamColor))").size - 1
        val oldGradient = src.split("Box(modifier = Modifier.fillMaxSize().background(pageBackground))").size - 1
        assertTrue(
            "exactly one page background layer; no doubled alpha gradient",
            solid + oldGradient == 1,
        )
        // Keep the real video compositor alive to protect the paused frame;
        // removing its surface for a fake energy saving causes black flashes.
        val video = source("com/nikhil/yt/ui/player/CapsuleVideoArtworkTransition.kt")
        assertTrue(video.contains("body.isRunning || lead.isRunning"))
        assertTrue(video.contains("capsuleVideoContentLag(p, lead.value, immersive)"))
    }

    @Test
    fun `mini player procedural motion stops when playback is not active`() {
        val source = source("com/nikhil/yt/ui/player/CapsuleMiniPlayer.kt")
        val normalized = source.replace(Regex("\\s+"), " ")
        assertTrue(
            normalized.contains(
                "animated = visualsActive && isPlaying && playbackState == Player.STATE_READY",
            ),
        )
    }

    @Test
    fun `collapsed player refresh is slower than expanded player refresh`() {
        val source = source("com/nikhil/yt/ui/player/Player.kt")
        assertTrue(source.contains("state.isExpanded -> 300L"))
        assertTrue(source.contains("else -> 1_000L"))
        assertTrue(source.contains("if (!isPlaying) break"))
        assertTrue(source.contains("positionDiscontinuityRevision,"))
    }

    @Test
    fun `rapid track changes do not immediately start high resolution visual preloads`() {
        val source = source("com/nikhil/yt/ui/player/CapsuleMediaPreload.kt")
        // A 420 ms dwell still cancels speculative decoding during rapid skips,
        // but unlike 1.2 s no longer strands stable tracks behind a grey cover.
        assertTrue(source.contains("CAPSULE_VISUAL_PREFETCH_SETTLE_MS = 420L"))
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
