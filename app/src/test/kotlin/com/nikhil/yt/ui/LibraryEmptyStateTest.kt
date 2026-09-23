package com.nikhil.yt.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A library tab must not draw its empty state before the query has answered.
 *
 * `stateIn(..., emptyList())` makes "nothing here yet" and "you own nothing" the same value, and
 * Room's real answer arrives at least a frame later than the seed. So every tab opened by drawing a
 * centred "you have no albums" placeholder and replacing it with a grid a frame later — which is
 * what reads as a lurch in the first moments, and is the same mistake as the stock-tab flash that
 * priming the preference snapshot fixed.
 *
 * Seeding with null says "not known yet", which is the truth and lets the screen draw neither.
 *
 * This is a source check because the failure is invisible when it comes back: someone adds a flow,
 * seeds it with an empty list out of habit, and the tab flickers for one frame in a way nobody can
 * reproduce on demand.
 */
class LibraryEmptyStateTest {
    private val viewModels: File by lazy {
        val candidates = listOf(
            File("src/main/kotlin/com/nikhil/yt/viewmodels/LibraryViewModels.kt"),
            File("app/src/main/kotlin/com/nikhil/yt/viewmodels/LibraryViewModels.kt"),
        )
        candidates.firstOrNull { it.isFile }
            ?: error(
                "Could not find LibraryViewModels.kt from ${File(".").absolutePath}; " +
                    "tried ${candidates.joinToString { it.path }}",
            )
    }

    private val screens: List<File> by lazy {
        listOf("LibraryAlbumsScreen.kt", "LibraryArtistsScreen.kt").map { name ->
            val candidates = listOf(
                File("src/main/kotlin/com/nikhil/yt/ui/screens/library/$name"),
                File("app/src/main/kotlin/com/nikhil/yt/ui/screens/library/$name"),
            )
            candidates.firstOrNull { it.isFile } ?: error("Could not find $name")
        }
    }

    @Test fun `the flows behind an empty state say when they do not know yet`() {
        val source = viewModels.readText()

        listOf("allAlbums", "allArtists").forEach { flow ->
            val declaration = source.substringAfter("val $flow =").substringBefore("\n\n")
            assertTrue(
                "$flow is seeded with an empty list, so its screen cannot tell " +
                    "\"not loaded\" from \"empty\" and will flash its placeholder",
                "SharingStarted.Lazily, null" in declaration,
            )
        }
    }

    @Test fun `the screens with an empty state check that the answer has arrived`() {
        screens.forEach { screen ->
            val source = screen.readText()
            val placeholders = Regex("EmptyPlaceholder\\(").findAll(source).count()
            assertTrue("${screen.name} no longer has an empty state to guard", placeholders > 0)

            val guards = Regex("loaded\\w+ != null").findAll(source).count()
            assertTrue(
                "${screen.name} draws ${placeholders} empty states behind only $guards " +
                    "checks that the query has answered",
                guards >= placeholders,
            )
        }
    }
}
