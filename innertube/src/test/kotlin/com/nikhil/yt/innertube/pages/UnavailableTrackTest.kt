package com.nikhil.yt.innertube.pages

import com.nikhil.yt.innertube.models.MusicResponsiveListItemRenderer
import com.nikhil.yt.innertube.models.Run
import com.nikhil.yt.innertube.models.Runs
import org.junit.Assert.*
import org.junit.Test

class UnavailableTrackTest {
    private fun renderer() = MusicResponsiveListItemRenderer(
        badges = null, fixedColumns = null, thumbnail = null, menu = null, overlay = null, navigationEndpoint = null,
        playlistItemData = MusicResponsiveListItemRenderer.PlaylistItemData(null, "deleted-id"),
        flexColumns = listOf(MusicResponsiveListItemRenderer.FlexColumn(
            MusicResponsiveListItemRenderer.FlexColumn.MusicResponsiveListItemFlexColumnRenderer(Runs(listOf(Run("Deleted video", null)))),
        )), isPlayable = false,
    )
    @Test fun sourceGreyOutFieldIsDecodedFromItsActualWireName() {
        val json = kotlinx.serialization.json.Json { encodeDefaults = true }
        val source = json.encodeToString(renderer().copy(isPlayable = null,
            displayPolicy = "MUSIC_ITEM_RENDERER_DISPLAY_POLICY_GREY_OUT"))
            .replace("\"displayPolicy\"", "\"musicItemRendererDisplayPolicy\"")
        val decoded = json.decodeFromString<MusicResponsiveListItemRenderer>(source)
        assertTrue(decoded.unavailable)
    }

    @Test fun deletedPlaylistEntryWithoutArtworkDurationOrSetIdRemainsVisible() {
        val song = requireNotNull(PlaylistPage.fromMusicResponsiveListItemRenderer(renderer()))
        assertEquals("deleted-id", song.id)
        assertEquals("Deleted video", song.title)
        assertTrue(song.unavailable)
        assertEquals("", song.thumbnail)
        assertNull(song.setVideoId)
    }
    @Test fun absenceOfAnEndpointAloneDoesNotMeanUnavailable() {
        assertFalse(renderer().copy(isPlayable = null).unavailable)
        assertTrue(renderer().copy(isPlayable = null, displayPolicy = "MUSIC_ITEM_RENDERER_DISPLAY_POLICY_GREY_OUT").unavailable)
    }
    @Test fun unavailableAlbumEntryDoesNotCrashOnMissingAlbumEndpointOrDuration() {
        val song = requireNotNull(AlbumPage.getSong(renderer()))
        assertTrue(song.unavailable)
        assertNull(song.album)
        assertNull(song.duration)
    }
}
