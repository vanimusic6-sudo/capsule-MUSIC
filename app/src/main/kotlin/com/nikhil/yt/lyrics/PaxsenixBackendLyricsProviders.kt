/**
 * Capsule MUSIC
 * Paxsenix Apple Music relay backend.
 * GPL-3.0
 */

package com.nikhil.yt.lyrics

import android.content.Context
import com.nikhil.yt.constants.EnablePaxsenixAppleMusicKey
import com.nikhil.yt.utils.dataStore
import com.nikhil.yt.utils.get
import com.nikhil.yt.utils.runCatchingCancellable

object PaxsenixAppleMusicLyricsProvider : LyricsProvider {
    override val name = "Paxsenix: Apple Music"

    override fun isEnabled(context: Context): Boolean =
        context.dataStore[EnablePaxsenixAppleMusicKey] ?: false

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        album: String?,
        duration: Int,
    ): Result<String> =
        runCatchingCancellable {
            PaxsenixLyricsProvider
                .fetchAppleMusic(title, artist, album, duration)
                ?.takeIf(String::isNotBlank)
                ?: throw NoLyricsFromProvider("Paxsenix Apple Music returned no lyrics")
        }
}
