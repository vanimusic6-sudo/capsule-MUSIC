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
        // The volume stays fixed; quick actions and every list section
        // share the same LazyColumn and scroll with one native gesture.
        val volume = player.indexOf("PlayerVolumeCard(playerConnection =")
        val list = player.indexOf("    LazyColumn(")
        assertTrue("Volume must stay pinned above scrolling content", volume in 0 until list)
        assertTrue("Quick actions must begin scrolling directly after volume",
            player.indexOf("item(key = \"quick-actions\"") > list)
        assertTrue(player.contains("contentType = \"menu-section\""))
        assertTrue(lyrics.contains("NewActionGrid("))
        assertTrue(lyrics.contains(".verticalScroll(rememberScrollState())"))
        assertTrue(lyrics.contains(".heightIn(max ="))
        assertFalse(lyrics.contains("userScrollEnabled = !isPortrait"))
    }

    @Test fun audioVolumeDoesNotRecomposeTheEntireScrollSurface() {
        val player = source("com/nikhil/yt/ui/menu/PlayerMenu.kt")
        val leaf = player.indexOf("private fun PlayerVolumeCard(")
        val producer = player.indexOf("service.playerVolume.collectAsState()")
        assertTrue("volume subscription must live inside the small card", producer > leaf)
        assertFalse(player.contains("basicMarquee()"))
        val tiles = source("com/nikhil/yt/ui/component/NewMenuComponents.kt")
        assertFalse("rapid menu scrolling must not run per-tile marquee clocks",
            tiles.contains("basicMarquee()"))
        assertTrue(tiles.contains("CapsuleMenuMinActionHeight = 100.dp"))
    }

    @Test fun nativeSheetStillHandlesItsOwnGesturesInsteadOfCustomNestedDrags() {
        val menu = source("com/nikhil/yt/ui/component/BottomSheetMenu.kt")
        assertTrue(menu.contains("rememberModalBottomSheetState(skipPartiallyExpanded = false)"))
        assertFalse(menu.contains("rememberDraggableState("))
        assertTrue(menu.contains("RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)"))
    }
    @Test fun allMoreMenusScrollInPortraitLikePlayer() {
        val variants = listOf(
            "AlbumMenu", "ArtistMenu", "PlaylistMenu", "SelectionSongsMenu",
            "SongMenu", "YouTubeAlbumMenu", "YouTubeArtistMenu",
            "YouTubePlaylistMenu", "YouTubeSongMenu",
        )
        variants.forEach { name ->
            val menu = source("com/nikhil/yt/ui/menu/$name.kt")
            assertFalse("$name must never disable the content list in portrait",
                menu.contains("userScrollEnabled = !isPortrait"))
            assertTrue("$name should keep its own independent scroll state",
                menu.contains("rememberLazyListState()"))
        }
        val offline = source("com/nikhil/yt/ui/menu/OfflineDownloadMenu.kt")
        assertTrue("Offline More must also reach its destructive bottom action",
            offline.contains(".verticalScroll(rememberScrollState(),"))
    }

    @Test fun theSharedPressStyleReplacesMismatchedRipplesOnRowsAndMenus() {
        val press = source("com/nikhil/yt/ui/component/CapsulePressFeedback.kt")
        assertTrue(press.contains("secondaryContainer.copy(alpha = ActiveBoxAlpha)"))
        assertTrue(press.contains("indication = null"))
        assertTrue(press.contains("drawRoundRect("))
        val tiles = source("com/nikhil/yt/ui/component/NewMenuComponents.kt")
        assertTrue(tiles.contains("interactionSource = interactionSource"))
        assertTrue(tiles.contains("normal = CapsuleMenuBlack") ||
            tiles.contains("CapsuleMenuBlack, CapsuleMenuPressed, pressed"))
        assertTrue("No unnecessary colour animation when black is fixed",
            !tiles.contains("val animatedBackground by animateColorAsState"))
        val search = source("com/nikhil/yt/ui/screens/search/OnlineSearchResult.kt")
        assertTrue(search.contains("capsulePressCombinedClickable("))
    }

}
