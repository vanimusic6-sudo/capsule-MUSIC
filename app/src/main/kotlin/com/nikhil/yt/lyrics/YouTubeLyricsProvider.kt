/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */



package com.nikhil.yt.lyrics

import com.nikhil.yt.utils.runCatchingCancellable
import android.content.Context
import com.nikhil.yt.innertube.YouTube
import com.nikhil.yt.innertube.models.WatchEndpoint

object YouTubeLyricsProvider : LyricsProvider {
    override val name = "YouTube Music"

    override fun isEnabled(context: Context) = true

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        album: String?,
        duration: Int,
    ): Result<String> {
        // Metrolist's Android Music browse route provides real line timestamps.
        // Prefer it over unsynced text; a missing/blocked timed response must
        // not suppress the existing plain-panel and transcript fallbacks.
        val timed = YouTube.synchronizedLyrics(id)
        timed.getOrNull()?.takeIf { it.isNotBlank() }?.let { return Result.success(it) }

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
        if (!text.isNullOrBlank()) return Result.success(text)

        // Existing Innertube transcript client; no new API, cookie, or service.
        // A transcript may still be unavailable for music videos. Reject
        // non-vocal boilerplate rather than showing it as a lyric.
        return YouTube.transcript(id).mapCatching { captions ->
            val clean = captions.trim()
            if (clean.isBlank() ||
                clean.equals("[Music]", ignoreCase = true) ||
                clean.equals("♪", ignoreCase = true)
            ) throw NoLyricsFromProvider("YouTube has no readable lyrics or captions")
            clean
        }
    }

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
