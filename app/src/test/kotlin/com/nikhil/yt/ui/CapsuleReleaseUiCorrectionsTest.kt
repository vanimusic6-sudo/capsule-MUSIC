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

    @Test fun artistSubscriptionIsTextOnlyAndToolbarSealsWithoutLateTint() {
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
        assertTrue(toolbar.contains("artworkFade >= 1f"))
        assertFalse(toolbar.contains("return artworkFade.coerceIn(0f, 1f)"))
        assertFalse(toolbar.contains("((fade - 0.98f) / 0.02f)"))
    }

    @Test fun downloadsShareLocalPlaylistSemanticsAndNeverCloseMoreOnTap() {
        val shared = source("ui/menu/CapsuleDownloadAction.kt")
        assertTrue(Regex("""VeluneLoader\\(size\\s*=\\s*24\\.dp""").containsMatchIn(shared))
        assertTrue(shared.contains("completed -> confirmRemoval = true"))
        assertTrue(shared.contains("active -> onStop()"))
        assertTrue(shared.contains("else -> onDownload()"))
        assertTrue(shared.contains("DefaultDialog("))
        assertTrue(shared.contains("confirmRemoval = false\n                    onRemove()"))
        assertFalse("Delete cannot open a second nested dialog", source("ui/menu/PlaylistMenu.kt").contains("showRemoveDownloadDialog"))
        val local = source("ui/menu/PlaylistMenu.kt")
        val online = source("ui/menu/YouTubePlaylistMenu.kt")
        val single = source("ui/menu/YouTubeDownloadMenuItems.kt")
        val player = source("ui/menu/PlayerMenu.kt")
        assertTrue(local.contains("CapsuleDownloadAction("))
        assertTrue(online.contains("CapsuleDownloadAction("))
        assertTrue(single.contains("CapsuleDownloadAction("))
        assertTrue(online.contains("OnlinePlaylistDownloadCoordinator.stop("))
        assertTrue(online.contains("OnlinePlaylistDownloadCoordinator.start("))
        assertTrue(online.contains("OnlinePlaylistDownloadCoordinator.cancelAndRemove("))
        assertFalse(online.contains("if (hasAnyCollectionDownload)"))
        assertFalse(online.contains("headlineContent = { Text(stringResource(R.string.capsule_stop_and_delete_download)) }"))
        assertFalse("Player More must not be abruptly dismissed by a download click",
            single.contains("onDismiss()"))
        assertTrue(player.contains("if (downloading) VeluneLoader(size = 24.dp)"))
        assertFalse(local.contains("playlist?.id?.let { clearPlaylist(it) }"))
    }

    @Test fun onlinePlaylistStopKeepsCacheAndRemovalRecoversFullMembership() {
        val coordinator = source("playback/OnlinePlaylistDownloadCoordinator.kt")
        assertTrue(coordinator.contains("session?.job?.cancel()"))
        assertTrue(coordinator.contains("stopCollectionDownloads(downloadManager, ids)"))
        assertTrue(coordinator.contains("sendSetStopReason("))
        assertTrue(coordinator.contains("removeCollectionDownloads(downloadManager, ids, preserveManual = false)"))
        assertTrue(coordinator.contains("DownloadService.sendRemoveDownload("))
        assertTrue(coordinator.contains("putStringSet(playlistId, ids.toSet())"))
        assertTrue(coordinator.contains("rememberedIds(context, playlistId)"))
    }

    @Test fun explicitBadgePrecedesItsFirstMetadataLine() {
        val items = source("ui/component/Items.kt")
        assertTrue(items.contains("Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth())"))
        assertTrue(items.contains("Box(Modifier.weight(1f)) { subtitle() }"))
    }
}
