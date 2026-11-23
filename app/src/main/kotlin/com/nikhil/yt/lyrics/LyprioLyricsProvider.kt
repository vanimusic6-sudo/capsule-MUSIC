/* Capsule MUSIC — Lyprio secure integration boundary. GPL-3.0. */
package com.nikhil.yt.lyrics

import android.content.Context

/**
 * Lyprio requires an authorized HMAC-SHA256 signing service. Its endpoint and
 * signing key are not public API credentials. Never copy a third-party key into
 * an APK or silently use a differently named provider as Lyprio.
 */
object LyprioLyricsProvider : LyricsProvider {
    override val name = "Lyprio (не подключён)"
    override fun isEnabled(context: Context): Boolean = false

    override suspend fun getLyrics(
        id: String, title: String, artist: String, album: String?, duration: Int,
    ): Result<String> =
        Result.failure(NoLyricsFromProvider("Lyprio requires an authorized signing gateway"))
}
