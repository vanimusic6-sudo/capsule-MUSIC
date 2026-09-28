package com.nikhil.yt.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The search header must not move when another surface toggles status-bar visibility.
 *
 * Capsule Immersive hides the Android status bar while it opens. Using visibility-aware system
 * bar insets inside TopSearch makes its top inset collapse to zero at the same moment, so the
 * search screen visibly follows the phone chrome up and then jumps back down on player close.
 */
class SearchBarInsetTest {
    private fun source(vararg relativePaths: String): String {
        val candidates =
            relativePaths.flatMap { relative ->
                listOf(
                    File("src/main/kotlin/$relative"),
                    File("app/src/main/kotlin/$relative"),
                )
            }
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("Could not find any of: ${relativePaths.joinToString()}")
    }

    private fun searchBarSource(): String =
        source("com/nikhil/yt/ui/component/SearchBar.kt")

    @Test
    fun `top search ignores temporary system bar visibility changes`() {
        val source = searchBarSource()

        assertTrue(
            "TopSearch must reserve the physical system-bar inset even while Immersive hides it",
            source.contains(
                "windowInsets: WindowInsets = WindowInsets.systemBarsIgnoringVisibility",
            ),
        )
    }

    @Test
    fun `search result filters ignore temporary system bar visibility changes`() {
        val source =
            source("com/nikhil/yt/ui/screens/search/OnlineSearchResult.kt")

        assertTrue(
            "SoundCloud/artist/video chips must stay at a fixed physical top inset",
            source.contains(
                "WindowInsets.systemBarsIgnoringVisibility" +
                    "\n                    .only(WindowInsetsSides.Top)",
            ),
        )
    }
}
