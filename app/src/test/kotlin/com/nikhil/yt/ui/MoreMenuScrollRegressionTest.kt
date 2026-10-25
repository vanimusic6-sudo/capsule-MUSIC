package com.nikhil.yt.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A 60Hz sheet's LazyColumn must not compete with marquee clocks or a dragged host. */
class MoreMenuScrollRegressionTest {
    private fun source(path: String): String =
        listOf(File("src/main/kotlin/$path"), File("app/src/main/kotlin/$path"))
            .firstOrNull(File::isFile)?.readText() ?: error("Missing $path")

    @Test fun nestedListsKeepIndependentScrollAndStableKeys() {
        val player = source("com/nikhil/yt/ui/menu/PlayerMenu.kt")
        val lyrics = source("com/nikhil/yt/ui/menu/LyricsMenu.kt")
        assertTrue(player.contains("rememberLazyListState()"))
        // The action cards and their background stay pinned above the scroll list.
        val pinned = player.indexOf("NewActionGrid(")
        val list = player.indexOf("    LazyColumn(")
        assertTrue("More action surface must not slide with list rows", pinned in 0 until list)
        assertFalse(player.contains("key = \"quick-actions\""))
        assertTrue(player.contains("contentType = \"menu-section\""))
        assertTrue(lyrics.contains("key = \"lyrics-actions\""))
        assertFalse(lyrics.contains("userScrollEnabled = !isPortrait"))
    }

    @Test fun audioVolumeDoesNotRecomposeTheEntireScrollSurface() {
        val player = source("com/nikhil/yt/ui/menu/PlayerMenu.kt")
        val leaf = player.indexOf("private fun PlayerVolumeCard(")
        val producer = player.indexOf("service.playerVolume.collectAsState()")
        assertTrue("volume subscription must live inside the small card", producer > leaf)
        assertFalse(player.contains("basicMarquee()"))
        val tiles = source("com/nikhil/yt/ui/component/NewMenuComponents.kt")
        assertTrue(tiles.contains("if (pressGuard) Modifier else Modifier.basicMarquee()"))
    }

    @Test fun nativeSheetStillHandlesItsOwnGesturesInsteadOfCustomNestedDrags() {
        val menu = source("com/nikhil/yt/ui/component/BottomSheetMenu.kt")
        assertTrue(menu.contains("rememberModalBottomSheetState(skipPartiallyExpanded = false)"))
        assertFalse(menu.contains("rememberDraggableState("))
        assertTrue(menu.contains("RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)"))
    }
}
