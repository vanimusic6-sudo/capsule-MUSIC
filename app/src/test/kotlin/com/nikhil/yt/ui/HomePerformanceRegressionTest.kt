package com.nikhil.yt.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Structural regressions only; frame timing must be measured on Android hardware. */
class HomePerformanceRegressionTest {
    private fun source(path: String): String =
        listOf(File("src/main/kotlin/com/nikhil/yt/$path"), File("app/src/main/kotlin/com/nikhil/yt/$path"))
            .firstOrNull { it.isFile }?.readText() ?: error("Missing $path")

    @Test fun keepListeningPressKeepsSemanticsButHasNoRowOverlay() {
        val row = source("ui/screens/HomeScreenComponents.kt")
            .substringAfter("private fun KeepListeningRow(").substringBefore("fun ForgottenFavoritesSection(")
        // Active tracks must retain the SongListItem selection background.
        // The unwanted PRESS ripple remains separately disabled.
        assertFalse(row.contains("showActiveBackground = false"))
        assertTrue(row.contains("isActive = isActive"))
        assertTrue(row.contains("indication = null"))
        assertTrue(row.contains("interactionSource = remember { MutableInteractionSource() }"))
        assertTrue(row.contains("onLongClick = showSongMenu"))
        assertTrue(row.contains("playerConnection.playQueue"))
        assertTrue(source("ui/component/Items.kt").contains("showActiveBackground: Boolean = true"))
    }

    @Test fun homeHasNoFloatingShuffleOrPerPixelPaginationWork() {
        val home = source("ui/screens/HomeScreen.kt")
        assertFalse(home.contains("ScrollActionButton("))
        assertFalse(home.contains("HideOnScrollFAB("))
        assertTrue(home.contains("snapshotFlow {"))
        assertTrue(home.contains("distinctUntilChanged()"))
        assertFalse(home.contains("HomeScrollSnapshot("))
        assertTrue(home.contains("home:collections-button"))
    }

    @Test fun personalizedMusicShelvesAndPagingStayOnMainHome() {
        val home = source("ui/screens/HomeScreen.kt")
        assertTrue(home.contains("isLoading || isLoadingMore"))
        assertTrue(home.contains("homePage?.sections"))
        assertTrue(home.contains("home:listener-community-playlists"))
        assertTrue(home.contains("home:youtube-new-release-albums"))
        assertTrue(source("viewmodels/HomeViewModel.kt").contains("val isLoadingMore = _isLoadingMore.asStateFlow()"))
        assertTrue(source("viewmodels/HomeViewModel.kt").contains("checkedContinuations < 2"))
        assertTrue(source("ui/screens/HomeScreenComponents.kt").contains("verticalAlignment = Alignment.Top"))
        assertTrue(home.contains("keepListening?.takeIf { it.isNotEmpty() }"))
        assertTrue(home.contains("navController.navigate(\"mood_and_genres\")"))
    }
}
