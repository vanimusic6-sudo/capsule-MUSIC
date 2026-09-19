package com.nikhil.yt.playback

/** How many different songs must be turned away before the route, not the song, is to blame. */
internal const val AUTH_WALL_DISTINCT_TRACKS = 2

/**
 * Tells a song that needs an account apart from a network that YouTube will not serve anonymously.
 *
 * Both arrive as the same answer. A capture opened with three songs in a row returning
 * LOGIN_REQUIRED, and it read exactly like three age-restricted songs — right up until the user
 * changed VPN exit and all three played. They were never restricted. The exit address was, and
 * every anonymous request leaving through it was turned away whatever it asked for.
 *
 * One song refused among songs that play is the song. Several different songs refused in a row,
 * with nothing playing in between, is the way out of the phone. The difference matters because the
 * advice is opposite: for the first, log in or skip it; for the second, nothing about the song will
 * help and only a different route or an account will.
 *
 * Only identifiers are held, so the same song failing twice never counts as two.
 */
internal class AuthWallDetector {
    private val turnedAway = LinkedHashSet<String>()

    /** Notes a song that came back needing an account, and says whether the route now looks at fault. */
    fun recordAuthRequired(mediaId: String?): Boolean {
        if (mediaId != null) turnedAway += mediaId
        return isRouteWide()
    }

    /** Anything that plays proves the route is fine, whatever was refused before it. */
    fun recordPlayable() {
        turnedAway.clear()
    }

    fun isRouteWide(): Boolean = turnedAway.size >= AUTH_WALL_DISTINCT_TRACKS

    /** A new route earns a clean slate; the old one's verdict says nothing about it. */
    fun forget() {
        turnedAway.clear()
    }
}
