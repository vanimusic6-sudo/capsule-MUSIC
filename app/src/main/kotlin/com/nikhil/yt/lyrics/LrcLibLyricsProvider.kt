/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */



package com.nikhil.yt.lyrics

import android.content.Context
import com.nikhil.yt.lrclib.LrcLib
import com.nikhil.yt.constants.EnableLrcLibKey
import com.nikhil.yt.db.entities.LyricsEntity.Companion.LYRICS_NOT_FOUND
import com.nikhil.yt.utils.dataStore
import com.nikhil.yt.utils.get
import kotlinx.coroutines.CancellationException
import timber.log.Timber

object LrcLibLyricsProvider : LyricsProvider {
    override val name = "LrcLib"
    private val requestGuard = LrcLibRequestGuard(
        onTransientFailure = { message -> Timber.tag("LrcLib").d(message) },
    )

    override fun isEnabled(context: Context): Boolean = context.dataStore[EnableLrcLibKey] ?: true

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        album: String?,
        duration: Int,
    ): Result<String> = try {
        val lyrics = requestGuard.request(title, artist, duration) {
            LrcLib.getLyrics(title, artist, duration).getOrThrow()
                ?.takeIf { it.isNotBlank() }
        }
        // A normal miss or temporary deferral is not an exception to report.
        Result.success(lyrics ?: LYRICS_NOT_FOUND)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        // Unexpected failures still reach LyricsHelper.reportProviderFailure.
        Result.failure(failure)
    }

    override suspend fun getAllLyrics(
        id: String,
        title: String,
        artist: String,
        album: String?,
        duration: Int,
        callback: (String) -> Unit,
    ) {
        val lyrics = requestGuard.request(title, artist, duration) {
            val results = mutableListOf<String>()
            LrcLib.getAllLyrics(title, artist, duration, null) {
                if (it.isNotBlank()) results += it
            }
            results.takeIf { it.isNotEmpty() }
        }
        // Consumer failures must never be recorded as provider/network failures.
        lyrics?.forEach(callback)
    }
}
