/* Capsule MUSIC — Better Lyrics TTML source. GPL-3.0. */
package com.nikhil.yt.lyrics

import android.content.Context
import com.nikhil.yt.betterlyrics.BetterLyrics
import com.nikhil.yt.constants.EnableBetterLyricsKey
import com.nikhil.yt.utils.dataStore
import com.nikhil.yt.utils.get

object BetterLyricsProvider : LyricsProvider {
    override val name = "Better Lyrics · TTML"
    override fun isEnabled(context: Context): Boolean =
        context.dataStore[EnableBetterLyricsKey] ?: true

    override suspend fun getLyrics(
        id: String, title: String, artist: String, album: String?, duration: Int,
    ): Result<String> = BetterLyrics.getLyrics(title, artist, album, duration)
}
