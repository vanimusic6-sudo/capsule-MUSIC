/**
 * Capsule MUSIC
 * Individual Paxsenix relay backends.
 * GPL-3.0
 */

package com.nikhil.yt.lyrics

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import com.nikhil.yt.constants.EnablePaxsenixAppleMusicKey
import com.nikhil.yt.constants.EnablePaxsenixMusixmatchKey
import com.nikhil.yt.constants.EnablePaxsenixNetEaseKey
import com.nikhil.yt.constants.EnablePaxsenixSpotifyKey
import com.nikhil.yt.constants.EnablePaxsenixYouTubeKey
import com.nikhil.yt.utils.dataStore
import com.nikhil.yt.utils.get
import com.nikhil.yt.utils.runCatchingCancellable

private fun Context.paxsenixBackendEnabled(key: Preferences.Key<Boolean>): Boolean =
    dataStore[key] ?: false

private suspend fun paxsenixResult(
    backend: String,
    fetch: suspend () -> String?,
): Result<String> =
    runCatchingCancellable {
        fetch()?.takeIf(String::isNotBlank)
            ?: throw NoLyricsFromProvider("Paxsenix $backend returned no lyrics")
    }

object PaxsenixAppleMusicLyricsProvider : LyricsProvider {
    override val name = "Paxsenix: Apple Music"

    override fun isEnabled(context: Context): Boolean =
        context.paxsenixBackendEnabled(EnablePaxsenixAppleMusicKey)

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        album: String?,
        duration: Int,
    ): Result<String> =
        paxsenixResult("Apple Music") {
            PaxsenixLyricsProvider.fetchAppleMusic(title, artist, album, duration)
        }
}

object PaxsenixSpotifyLyricsProvider : LyricsProvider {
    override val name = "Paxsenix: Spotify"

    override fun isEnabled(context: Context): Boolean =
        context.paxsenixBackendEnabled(EnablePaxsenixSpotifyKey)

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        album: String?,
        duration: Int,
    ): Result<String> =
        paxsenixResult("Spotify") {
            PaxsenixLyricsProvider.fetchSpotify(title, artist, duration)
        }
}

object PaxsenixMusixmatchLyricsProvider : LyricsProvider {
    override val name = "Paxsenix: Musixmatch"

    override fun isEnabled(context: Context): Boolean =
        context.paxsenixBackendEnabled(EnablePaxsenixMusixmatchKey)

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        album: String?,
        duration: Int,
    ): Result<String> =
        paxsenixResult("Musixmatch") {
            PaxsenixLyricsProvider.fetchPaxsenixMusixmatch(title, artist, duration)
        }
}

object PaxsenixNetEaseLyricsProvider : LyricsProvider {
    override val name = "Paxsenix: NetEase"

    override fun isEnabled(context: Context): Boolean =
        context.paxsenixBackendEnabled(EnablePaxsenixNetEaseKey)

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        album: String?,
        duration: Int,
    ): Result<String> =
        paxsenixResult("NetEase") {
            PaxsenixLyricsProvider.fetchNetEase(title, artist, duration)
        }
}

object PaxsenixYouTubeLyricsProvider : LyricsProvider {
    override val name = "Paxsenix: YouTube"

    override fun isEnabled(context: Context): Boolean =
        context.paxsenixBackendEnabled(EnablePaxsenixYouTubeKey)

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        album: String?,
        duration: Int,
    ): Result<String> =
        paxsenixResult("YouTube") {
            PaxsenixLyricsProvider.fetchYouTube(title, artist, duration)
        }
}
