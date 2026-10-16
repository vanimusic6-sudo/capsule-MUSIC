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
        assertTrue(row.contains("showActiveBackground = false"))
        assertTrue(row.contains("indication = null"))
        assertTrue(row.contains("interactionSource = remember { MutableInteractionSource() }"))
        assertTrue(row.contains("onLongClick = showSongMenu"))
        assertTrue(row.contains("playerConnection.playQueue"))
        assertTrue(source("ui/component/Items.kt").contains("showActiveBackground: Boolean = true"))
    }

    @Test fun homeDoesNotRecomposeItsFloatingButtonForEveryScrollPixel() {
        val home = source("ui/screens/HomeScreen.kt")
        assertTrue(home.contains("HomeScrollSnapshot("))
        assertTrue(home.contains("if (scrollingUp != movingUp) scrollingUp = movingUp"))
        assertTrue(home.contains("ScrollActionButton("))
        assertFalse(home.contains("HideOnScrollFAB("))
        assertFalse(home.contains("LaunchedEffect(forgottenFavorites)"))
        assertTrue(home.contains("remember(forgottenFavoritesLazyGridState, horizontalLazyGridItemWidthFactor)"))
    }

    @Test fun paginationAndTask01StateStayIntact() {
        val home = source("ui/screens/HomeScreen.kt")
        assertTrue(home.contains("isLoading || isLoadingMore"))
        assertTrue(home.contains("firstOrNull()?.id.orEmpty()"))
        assertTrue(source("viewmodels/HomeViewModel.kt").contains("val isLoadingMore = _isLoadingMore.asStateFlow()"))
        assertTrue(source("viewmodels/HomeViewModel.kt").contains("checkedContinuations < 2"))
        assertTrue(source("ui/screens/HomeScreenComponents.kt").contains("verticalAlignment = Alignment.Top"))
    }
}
