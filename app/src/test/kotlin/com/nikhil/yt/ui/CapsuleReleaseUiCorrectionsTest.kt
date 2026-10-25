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
        // The hero already fades to the page colour. A second blended toolbar
        // at the same fractional alpha causes a visibly darker stripe.
        // The toolbar must wait until that photo has nearly vanished.
        assertTrue(toolbar.contains("artistToolbarSurfaceAlpha(artworkFadeProvider())"))
        assertTrue(toolbar.contains("drawBehind"))
        assertTrue(toolbar.contains("((fade - 0.98f) / 0.02f)"))
        assertFalse(toolbar.contains("return artworkFade.coerceIn(0f, 1f)"))
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
        val public = source("ui/menu/YouTubePlaylistMenu.kt")
        assertTrue(public.contains("OnlinePlaylistDownloadCoordinator.cancelAndRemove("))
        assertTrue(public.contains("OnlinePlaylistDownloadCoordinator.start("))
        val coordinator = source("playback/OnlinePlaylistDownloadCoordinator.kt")
        assertTrue(coordinator.contains("session?.job?.cancel()"))
        assertTrue(coordinator.contains("DownloadService.sendRemoveDownload("))
        assertTrue(public.contains("key = \"playlist-download-action\""))
        assertFalse(public.contains("R.string.capsule_stop_and_delete_download),"))
        assertFalse(local.contains("playlist?.id?.let { clearPlaylist(it) }"))
    }

    @Test fun explicitBadgePrecedesItsFirstMetadataLine() {
        val items = source("ui/component/Items.kt")
        assertTrue(items.contains("Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth())"))
        assertTrue(items.contains("Box(Modifier.weight(1f)) { subtitle() }"))
    }
}
