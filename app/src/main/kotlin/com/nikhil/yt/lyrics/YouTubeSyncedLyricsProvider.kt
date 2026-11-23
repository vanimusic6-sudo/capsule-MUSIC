/* Capsule MUSIC — truly synchronized YouTube Music lyrics. GPL-3.0. */
package com.nikhil.yt.lyrics

import android.content.Context
import com.nikhil.yt.constants.EnableYouTubeSyncedLyricsKey
import com.nikhil.yt.innertube.YouTube
import com.nikhil.yt.utils.dataStore
import com.nikhil.yt.utils.get
import kotlinx.coroutines.CancellationException

/** Never present an untimed YouTube panel as synchronized lyrics. */
object YouTubeSyncedLyricsProvider : LyricsProvider {
    override val name = "YouTube · с синхронизацией"
    override fun isEnabled(context: Context): Boolean =
        context.dataStore[EnableYouTubeSyncedLyricsKey] ?: true

    override suspend fun getLyrics(
        id: String, title: String, artist: String, album: String?, duration: Int,
    ): Result<String> = try {
        val timed = YouTube.synchronizedLyrics(id).getOrThrow()
        if (timed.isNotBlank() &&
            Regex("""\[[0-9]{1,2}:[0-9]{2}[.:][0-9]{2,3}]""").containsMatchIn(timed)
        ) Result.success(timed)
        else Result.failure(NoLyricsFromProvider("YouTube Music returned no timed lyrics"))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Result.failure(failure)
    }
}
