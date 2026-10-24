package com.nikhil.yt.discovery

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guardrails for source integration; these are NOT on-device playback tests.
 * They catch the accidental replacement of native YouTube download behavior
 * by independent MediaStore downloads.
 */
class DownloadIsolationRegressionTest {
    private fun source(path: String): String =
        listOf(
            File("src/main/kotlin/com/nikhil/yt/$path"),
            File("app/src/main/kotlin/com/nikhil/yt/$path"),
        ).firstOrNull(File::isFile)?.readText() ?: error("Missing $path")

    @Test fun youtubeStillUsesMedia3AndItsOwnIndex() {
        val menu = source("ui/menu/PlayerMenu.kt")
        val util = source("playback/DownloadUtil.kt")
        val viewModel = source("viewmodels/AutoPlaylistViewModel.kt")
        assertTrue(menu.contains("!isExternalCatalog || isAudius"))
        assertTrue(menu.contains("DownloadService.sendAddDownload("))
        assertTrue(menu.contains("ExoDownloadService::class.java"))
        assertTrue(util.contains("ResolvingAudioDownloader("))
        assertTrue(util.contains("DefaultDownloadIndex(databaseProvider)"))
        assertTrue(util.contains("setCache(downloadCache)"))
        assertTrue(viewModel.contains("downloads[it.id]?.state == Download.STATE_COMPLETED"))
    }

    @Test fun externalAudiusUsesOnlyIndependentMediaStorePipeline() {
        val external = source("discovery/ExperimentalDownload.kt")
        val queue = source("discovery/OfflineAudioQueue.kt")
        val service = source("playback/MusicService.kt")
        assertTrue(external.contains("MediaStore.Downloads.EXTERNAL_CONTENT_URI"))
        assertFalse(external.contains("ExoDownloadService"))
        assertFalse(external.contains("DownloadService.sendAddDownload"))
        assertTrue(queue.contains("setUri(file.uri)"))
        assertTrue(queue.contains("thumbnailUrl = file.artwork"))
        assertTrue(service.contains("return externalStreamingSource.createMediaSource(mediaItem)"))
    }

    @Test fun nativeQueueDoesNotThrottleAllSongsOnGenericErrors() {
        val util = source("playback/DownloadUtil.kt")
        val classifier = util.substringAfter("private fun isProbablyThrottleSignal(")
            .substringBefore("companion object")
        assertFalse(classifier.contains("\"403\""))
        assertFalse(classifier.contains("\"timeout\""))
        assertFalse(classifier.contains("\"unavailable\""))
        assertTrue(classifier.contains("isRateLimitedException(exception)"))
        assertTrue(util.contains("if (exception == null || !isProbablyThrottleSignal(exception))"))
    }

    @Test fun downloadedScreensKeepCompactArtAndUnobscuredToolbar() {
        val screen = source("ui/screens/playlist/AutoPlaylistScreen.kt")
        val library = source("ui/screens/library/LibrarySongsScreen.kt")
        val rows = source("ui/component/OfflineSongListItem.kt")
        assertTrue(screen.contains("OfflineSongListItem("))
        assertTrue(library.contains("OfflineSongListItem("))
        assertTrue(screen.contains("Spacer(Modifier.height(AppBarHeight))"))
        assertTrue(screen.contains("itemsIndexed("))
        assertTrue(rows.contains("ItemThumbnail("))
        assertTrue(rows.contains("LocalSongListVisuals.current"))
        assertTrue(rows.contains("onMoreClick"))
        assertTrue(source("discovery/OfflineAudioQueue.kt").contains("fun removeDeletedFromPlayer("))
        assertTrue(library.contains("OfflineAudioQueue.removeDeletedFromPlayer("))
        assertTrue(screen.contains("OfflineAudioQueue.removeDeletedFromPlayer("))
        assertTrue(source("ui/menu/OfflineDownloadMenu.kt").contains("OfflineAudioQueue.mediaItem(file)"))
        assertTrue(source("extensions/MediaItemExt.kt").contains("ContentUris.withAppendedId"))
        assertTrue(source("ui/menu/OfflineDownloadMenu.kt").contains("allowSyncing = false"))
        assertTrue(source("ui/menu/PlayerMenu.kt").contains("isAudius || isOfflineAudio"))
        assertTrue(source("ui/menu/SongMenu.kt").contains("|| offlineSong"))
        assertTrue(source("ui/menu/SongMenu.kt").contains("getSongById(song.id) == null"))
        assertTrue(source("ui/menu/SongMenu.kt").contains("it.copy(isLocal = true, inLibrary = LocalDateTime.now())"))
        assertTrue(source("ui/menu/SongMenu.kt").contains("existing.song.localToggleLike()"))
        assertTrue(source("extensions/MediaItemExt.kt").contains("capsule-open:audius:"))
        assertTrue(source("playback/MusicService.kt").contains("!mediaId.startsWith(EXPERIMENTAL_MEDIA_PREFIX)"))
        assertTrue(source("ui/menu/PlayerMenu.kt").contains("allowSyncing = !isExternalCatalog"))
        assertTrue(source("discovery/ExperimentalDownload.kt").contains("suspend fun deleteSaved"))
        assertTrue(source("ui/screens/playlist/AutoPlaylistScreen.kt").contains("OfflineDownloadMenu("))
        assertTrue(source("ui/screens/library/LibrarySongsScreen.kt").contains("OfflineDownloadMenu("))
        assertTrue(source("ui/screens/library/LibrarySongsScreen.kt").contains("playerConnection.player.clearMediaItems()"))
        assertTrue(source("ui/screens/playlist/AutoPlaylistScreen.kt").contains("playerConnection.player.clearMediaItems()"))
        assertTrue(screen.contains("capsule:offline:hero"))
        assertTrue(source("discovery/ExperimentalDownload.kt").contains("recoverEmbeddedMetadata"))
        assertTrue(source("discovery/ExperimentalDownload.kt").contains("sourceMediaId = metadata.getString"))
        assertTrue(source("ui/component/OfflineSongListItem.kt").contains("file.sourceLabel"))
        assertTrue(source("ui/menu/OfflineDownloadMenu.kt").contains("file.sourceLabel"))
    }
}
