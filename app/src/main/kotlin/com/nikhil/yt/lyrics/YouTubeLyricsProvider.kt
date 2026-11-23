/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */



package com.nikhil.yt.lyrics

import com.nikhil.yt.utils.runCatchingCancellable
import com.nikhil.yt.constants.EnableYouTubePlainLyricsKey
import com.nikhil.yt.utils.dataStore
import com.nikhil.yt.utils.get
import android.content.Context
import com.nikhil.yt.innertube.YouTube
import com.nikhil.yt.innertube.models.WatchEndpoint

object YouTubeLyricsProvider : LyricsProvider {
    override val name = "YouTube · без синхронизации"

    override fun isEnabled(context: Context) =
        context.dataStore[EnableYouTubePlainLyricsKey] ?: true

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        album: String?,
        duration: Int,
    ): Result<String> {
        // Timed lyrics belong exclusively to YouTubeSyncedLyricsProvider.
        // The official YouTube Music lyrics panel is missing for many videos,
        // even though the video itself has human captions. This is the FINAL
        // (lowest-priority) source; avoid trying a redundant transcript until
        // the primary music panel has genuinely failed.
        val officialPanel = runCatchingCancellable {
            val nextResult = YouTube.next(WatchEndpoint(videoId = id)).getOrThrow()
            YouTube.lyrics(
                endpoint = nextResult.lyricsEndpoint
                    ?: throw NoLyricsFromProvider("YouTube Music has no lyrics panel"),
            ).getOrThrow() ?: throw NoLyricsFromProvider("YouTube Music panel was empty")
        }
        val text = officialPanel.getOrNull()
        if (!text.isNullOrBlank()) return Result.success(stripTiming(text))

        // Existing Innertube transcript client; no new API, cookie, or service.
        // A transcript may still be unavailable for music videos. Reject
        // non-vocal boilerplate rather than showing it as a lyric.
        return YouTube.transcript(id).mapCatching { captions ->
            val clean = captions.trim()
            if (clean.isBlank() ||
                clean.equals("[Music]", ignoreCase = true) ||
                clean.equals("♪", ignoreCase = true)
            ) throw NoLyricsFromProvider("YouTube has no readable lyrics or captions")
            stripTiming(clean)
        }
    }

    private fun stripTiming(raw: String): String =
        if (Regex("""\[[0-9]{1,2}:[0-9]{2}[.:][0-9]{2,3}]""").containsMatchIn(raw)) {
            LyricsUtils.parseLyrics(raw).joinToString("\n") { it.text }.ifBlank { raw }
        } else raw

    override suspend fun getAllLyrics(
        id: String,
        title: String,
        artist: String,
        album: String?,
        duration: Int,
        callback: (String) -> Unit,
    ) {
        getLyrics(id, title, artist, album, duration).onSuccess(callback)
    }
}
