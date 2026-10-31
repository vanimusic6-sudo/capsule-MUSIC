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

    @Test fun artistActionsAndLatestReleaseShareOneHorizontalGrid() {
        val hero = source("ui/component/ArtistHero.kt")
        val screen = source("ui/screens/artist/ArtistScreen.kt")
        assertTrue(hero.contains(".padding(horizontal = 22.dp)"))
        assertTrue(screen.contains(".padding(start = 22.dp, top = 8.dp, end = 22.dp"))
        assertTrue(hero.contains("showCandle"))
        assertTrue(screen.contains("viewModel.memorialInfo != null"))
        assertFalse(hero.contains("showRadio"))
        assertFalse(screen.contains("onRadio ="))
    }

    @Test fun artistBiographyFollowsReleaseAndCandleDialogIsACompactRitual() {
        val artist = source("ui/screens/artist/ArtistScreen.kt")
        val release = artist.indexOf("latestRelease?.let { release ->")
        val description = artist.indexOf("""item(key = "description")""")
        assertTrue("Latest release must appear before biography", release >= 0 && description > release)

        val dialog = source("ui/screens/artist/ArtistMemorialDialog.kt")
        assertTrue(dialog.contains("R.drawable.capsule_candle"))
        assertTrue(dialog.contains("R.drawable.close"))
        assertTrue(dialog.contains("R.drawable.favorite_border"))
        assertTrue(dialog.contains("Brush.sweepGradient("))
        assertTrue(dialog.contains("drawArc("))
        assertTrue(dialog.contains("R.string.artist_candle_send"))
        assertTrue(dialog.contains("R.string.artist_candle_sent"))
        assertTrue(dialog.contains("submitted:"))
        assertTrue(dialog.contains("0 -> if (LocalDensity.current.fontScale > 1.3f) 176.dp else 132.dp"))
        assertTrue(source("ui/component/CapsuleCandleIcon.kt").contains("R.drawable.capsule_candle_flame"))
        assertTrue(dialog.contains("onMinuteBegin()"))
        assertTrue(dialog.contains("dismissOnClickOutside = false"))
        assertTrue(dialog.contains("memorial-wish-canvas"))
        assertTrue(dialog.contains("BasicTextField("))
        assertTrue(dialog.contains("getSharedPreferences("))
        assertTrue(dialog.contains("artist_candle_note_hint"))
        assertFalse("No stacked content when changing candle modes", dialog.contains("Crossfade("))
        assertFalse("The dialog must never animate its own height", dialog.contains(".animateContentSize("))
        assertFalse("Memorial notes are never posted to other apps", dialog.contains("Intent.ACTION_SEND"))
        assertFalse("Avoid the old oversized footer button", dialog.contains("MemorialActionButton("))
        val hero = source("ui/component/ArtistHero.kt")
        // Exact positions and matching bottom margins are verified by the
        // rendering/geometry tests in ArtistHeroTest. This static guard should
        // verify the dynamic memorial row concept rather than fragile source
        // snippets such as a particular .height(...) call.
        assertTrue("The memorial row must be optional, not always reserved",
            hero.contains("memorialExtraHeight"))
        assertFalse(hero.contains(".animateContentSize("))
    }

    @Test fun downloadsShareLocalPlaylistSemanticsAndNeverCloseMoreOnTap() {
        val shared = source("ui/menu/CapsuleDownloadAction.kt")
        assertTrue(shared.contains("VeluneLoader(size = 24.dp"))
        assertTrue(shared.contains("completed && !stopRequested -> confirmRemoval = true"))
        assertTrue(shared.contains("currentlyDownloading -> {"))
        assertTrue(shared.contains("capsule_download_stop_delete"))
        assertTrue(shared.contains("CollectionDownloadStopUi.request(actionKey)"))
        // A previously reported stuck spinner must never disable the resume row.
        assertTrue(shared.contains("stopRequested -> idleDownloadTitle"))
        assertTrue(shared.contains("isLoading = currentlyDownloading || deleting"))
        assertFalse(shared.contains("isLoading = currentlyDownloading || deleting || stopRequested"))
        assertTrue(shared.contains("choiceOpenedDuringDownload"))
        assertTrue(shared.contains("pendingStart = true") && shared.contains("onDownload()"))
        assertTrue(shared.contains("DefaultDialog("))
        assertTrue(shared.contains("confirmRemoval = false\n                    requestRemoval()"))
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

    @Test fun onlinePlaylistAndAlbumCanDownloadWithoutPreviewSongs() {
        val playlist = source("ui/menu/YouTubePlaylistMenu.kt")
        assertTrue(playlist.contains("enabled = true"))
        assertFalse(playlist.contains("enabled = membershipReady"))
        assertTrue(playlist.contains("OnlinePlaylistDownloadCoordinator.start("))
        val album = source("ui/menu/YouTubeAlbumMenu.kt")
        assertTrue(album.contains("enabled = true"))
        assertTrue(album.contains("if (albumSongs.isEmpty())"))
        assertTrue(album.contains("OnlinePlaylistDownloadCoordinator.start("))
        val coordinator = source("playback/OnlinePlaylistDownloadCoordinator.kt")
        assertTrue(coordinator.contains("YouTube.playlist(playlistId.removePrefix(\"VL\"))"))
        assertTrue(coordinator.contains("YouTube.queue(playlistId = playlistId.removePrefix(\"VL\"))"))
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
