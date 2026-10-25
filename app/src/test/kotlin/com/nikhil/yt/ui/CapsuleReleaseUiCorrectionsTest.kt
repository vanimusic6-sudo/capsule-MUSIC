package com.nikhil.yt.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Static regression guards for the precise defects shown in the artist screenshots. */
class CapsuleReleaseUiCorrectionsTest {
    private fun source(path: String): String =
        listOf(File("src/main/kotlin/com/nikhil/yt/$path"),
            File("app/src/main/kotlin/com/nikhil/yt/$path"))
            .first { it.isFile }.readText()

    @Test fun miniPlayerNeverOffersArtistSubscribeControls() {
        val mini = source("ui/player/CapsuleMiniPlayer.kt")
        assertFalse(mini.contains("CapsuleSubscribeButton("))
        assertFalse(mini.contains("CapsuleSubscribeIcon("))
        assertTrue(mini.contains("CapsuleFavoriteButton("))
    }

    @Test fun artistSubscriptionIsTextOnlyAndToolbarFillsEarly() {
        val hero = source("ui/component/ArtistHero.kt")
        assertTrue(hero.contains("R.string.capsule_artist_subscribed"))
        assertFalse(hero.contains("CapsuleSubscribeIcon("))
        assertFalse(hero.contains("CapsuleSubscribeLabel("))
        val toolbar = source("ui/component/ArtistToolbar.kt")
        // The portrait and toolbar must now sample the same scroll state
        // in the DRAW phase. The old compositional alpha could lag after a
        // fast fling and its previous 90%-threshold made the gap visible.
        assertTrue(toolbar.contains("artistToolbarSurfaceAlpha(artworkFadeProvider())"))
        assertTrue(toolbar.contains("drawBehind"))
        assertFalse(toolbar.contains("fade >= 1f"))
    }

    @Test fun activeDownloadActionsConfirmFullDeletionAndDoNotDestroyPlaylists() {
        val actions = source("ui/menu/YouTubeDownloadMenuItems.kt")
        assertTrue(actions.contains("confirmPartialDelete = true"))
        assertTrue(actions.contains("DownloadRemovalDialog("))
        assertTrue(actions.contains("DownloadService.sendRemoveDownload("))
        assertFalse(actions.contains("sendSetStopReason("))
        val album = source("ui/screens/AlbumScreen.kt")
        assertTrue(album.contains("confirmActiveDownloadRemoval = true"))
        val local = source("ui/screens/playlist/LocalPlaylistScreen.kt")
        assertTrue(local.contains("showRemoveDownloadDialog = true"))
        assertFalse(local.contains("playlist?.id?.let { clearPlaylist(it) }"))
    }

    @Test fun explicitBadgePrecedesItsFirstMetadataLine() {
        val items = source("ui/component/Items.kt")
        assertTrue(items.contains("Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth())"))
        assertTrue(items.contains("Box(Modifier.weight(1f)) { subtitle() }"))
    }
}
