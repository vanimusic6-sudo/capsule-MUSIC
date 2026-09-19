package com.nikhil.yt.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every scrolling screen has to know where the bottom chrome is.
 *
 * The navigation bar is on screen everywhere now, and the mini-player was already there whenever
 * something was playing. A screen that scrolls without consuming [com.nikhil.yt.LocalPlayerAwareWindowInsets]
 * therefore runs its last rows underneath both of them — which is exactly what happened to five
 * settings pages, and what nobody notices until they try to tap the setting that is hidden.
 *
 * This is a source check, and deliberately a blunt one: it can only tell whether a screen consults
 * the inset at all, not whether it applies it to the right edge. That is still the failure that
 * actually occurs — a new screen is written without the inset because nothing made it obvious that
 * one was needed — and this is the cheapest thing that makes it obvious.
 */
class ScreenBottomInsetTest {
    /**
     * Screens that are only ever shown while the bottom chrome is hidden.
     *
     * The search overlay covers the screen with its own input surface and the navigation bar is
     * taken away for its duration, so there is nothing at the bottom for their content to collide
     * with. Anything added here needs that kind of reason, not just a wish to be left alone.
     */
    private val shownWithoutBottomChrome = setOf(
        "search/OnlineSearchScreen.kt",
        "search/LocalSearchScreen.kt",
        // The welcome flow is an opaque layer over the entire window on a first launch. There is
        // no navigation bar and no mini-player beneath it to collide with — there is no app
        // beneath it — so the player-aware inset would be the wrong measurement, not a missing
        // one. It uses the system bars directly.
        "onboarding/CapsuleWelcome.kt",
    )

    /** Files under screens/ that are not screens: dialogs, managers, activities. */
    private val notAScreen = setOf(
        "settings/AudioClientPriorityDialog.kt",
        "settings/LyricsProviderPriorityDialog.kt",
        "settings/PriorityOrderDialog.kt",
        "settings/DiscordPresenceManager.kt",
        "settings/ListenBrainzManager.kt",
        "settings/PoTokenExtractionActivity.kt",
    )

    private val screensRoot: File by lazy {
        // Android unit tests run with the module directory as the working directory.
        val candidates = listOf(
            File("src/main/kotlin/com/nikhil/yt/ui/screens"),
            File("app/src/main/kotlin/com/nikhil/yt/ui/screens"),
        )
        candidates.firstOrNull { it.isDirectory }
            ?: error(
                "Could not find the screens source root from ${File(".").absolutePath}; " +
                    "tried ${candidates.joinToString { it.path }}",
            )
    }

    @Test fun `every scrolling screen consumes the player-aware inset`() {
        val offenders =
            screensRoot
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filter { file ->
                    val relative = file.relativeTo(screensRoot).invariantSeparatorsPath
                    relative !in shownWithoutBottomChrome && relative !in notAScreen
                }
                .filter { file ->
                    val source = file.readText()
                    val scrolls =
                        source.contains("verticalScroll") ||
                            source.contains("LazyColumn(") ||
                            source.contains("LazyVerticalGrid(")
                    scrolls && !source.contains("LocalPlayerAwareWindowInsets")
                }
                .map { it.relativeTo(screensRoot).invariantSeparatorsPath }
                .sorted()
                .toList()

        assertTrue(
            "these screens scroll but never consult LocalPlayerAwareWindowInsets, so their last " +
                "rows sit under the navigation bar and the mini-player: $offenders",
            offenders.isEmpty(),
        )
    }

    @Test fun `the allow lists do not outlive the files they name`() {
        // A stale entry here silently re-opens the hole it was excusing.
        (shownWithoutBottomChrome + notAScreen).forEach { relative ->
            assertTrue(
                "$relative is on an allow list but no longer exists",
                File(screensRoot, relative).isFile,
            )
        }
    }
}
