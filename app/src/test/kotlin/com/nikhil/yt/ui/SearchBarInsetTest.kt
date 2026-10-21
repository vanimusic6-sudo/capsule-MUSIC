package com.nikhil.yt.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
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
    @Test
    fun `search first result is below BOTH the search box and filter chips`() {
        val source = source("com/nikhil/yt/ui/screens/search/OnlineSearchResult.kt")
        // Search route is not a top-level pinned-header destination. Its list
        // receives only the system inset from LocalPlayerAwareWindowInsets.
        // 64dp search bar + 48dp filter bar + 8dp breathing room belong here.
        assertTrue(source.contains(
            ".add(WindowInsets(top = AppBarHeight + SearchFilterHeight + 8.dp))",
        ))
        assertFalse(source.contains(
            ".add(WindowInsets(top = SearchFilterHeight + 8.dp))",
        ))
        // The chip surface must continue to anchor under the outer search bar.
        assertTrue(source.contains(
            ".add(WindowInsets(top = AppBarHeight))",
        ))
    }

    @Test
    fun `other detail pages with overlaid headers reserve toolbar height`() {
        val paths = listOf(
            "com/nikhil/yt/ui/screens/AccountScreen.kt",
            "com/nikhil/yt/ui/screens/artist/ArtistAlbumsScreen.kt",
            "com/nikhil/yt/ui/screens/artist/ArtistSongsScreen.kt",
            "com/nikhil/yt/ui/screens/artist/ArtistItemsScreen.kt",
        )
        paths.forEach { path ->
            val screen = source(path)
            assertTrue("$path lacks pinned TopAppBar", screen.contains("TopAppBar("))
            assertTrue("$path draws its first row below its TopAppBar",
                screen.contains(".add(WindowInsets(top = AppBarHeight))"))
        }
        val artistItems = source("com/nikhil/yt/ui/screens/artist/ArtistItemsScreen.kt")
        assertTrue(artistItems.contains(
            "windowInsetsPadding(LocalPlayerAwareWindowInsets.current.add(WindowInsets(top = AppBarHeight)))",
        ))
    }

}
