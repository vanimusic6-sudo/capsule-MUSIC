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

    @Test fun keepListeningUsesSharedPressFeedbackAndKeepsActiveTrackSemantics() {
        val row = source("ui/screens/HomeScreenComponents.kt")
            .substringAfter("private fun KeepListeningRow(").substringBefore("fun ForgottenFavoritesSection(")
        // Active tracks must retain the SongListItem selection background.
        // Shared draw-phase feedback owns the press; Material ripple stays disabled.
        assertFalse(row.contains("showActiveBackground = false"))
        assertTrue(row.contains("isActive = isActive"))
        assertTrue(row.contains(".capsulePressCombinedClickable("))
        val feedback = source("ui/component/CapsulePressFeedback.kt")
            .substringAfter("fun Modifier.capsulePressCombinedClickable(")
        assertTrue(feedback.contains("indication = null"))
        assertTrue(feedback.contains("remember { MutableInteractionSource() }"))
        assertTrue(feedback.contains("onLongClick = onLongClick"))
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
        // Source selection intentionally hides YouTube shelves only when the user
        // opts in and disables YouTube, never on an unconfigured Home.
        assertTrue(home.contains("keepListening?.takeIf { displayYouTube && it.isNotEmpty() }"))
        assertTrue(home.contains("!experimentalSourcesEnabled || HomeFeedSource.YOUTUBE in homeSources"))
        assertTrue(home.contains("navController.navigate(\"mood_and_genres\")"))
    }
}
