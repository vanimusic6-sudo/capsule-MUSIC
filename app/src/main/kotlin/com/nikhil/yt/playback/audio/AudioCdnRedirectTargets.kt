package com.nikhil.yt.playback.audio

import okhttp3.HttpUrl

/**
 * Where a googlevideo link actually ends up, remembered so the next slice can go straight there.
 *
 * Three captures say the same thing. Roughly a third of all googlevideo requests are redirects;
 * eleven of the thirteen followed in the last one pointed at the host we were already talking to,
 * changing only the URL. And a redirect is never free: it arrives without a length and without
 * chunked framing, so it ends its connection — sixty-two redirects and refusals in one capture,
 * every single one followed by a brand-new socket. That matters because every refusal in every
 * capture landed on a connection's first request and none on a reused one.
 *
 * The reason we pay it over and over is here rather than at googlevideo. A track is read a
 * megabyte at a time, each slice built from the *original* link, because the redirect is resolved
 * inside the HTTP client and never escapes it. So slice two asks the same question slice one
 * already had answered, and gets sent to the same place again.
 *
 * This remembers the answer. The slices of one track share a URL exactly — the window is carried
 * in a `Range` header, not in the query string — so the original link is the key and the resolved
 * one is the value.
 *
 * Bounded, and deliberately small: a handful of tracks are in flight at once, never more.
 */
internal class AudioCdnRedirectTargets(
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    private val resolved =
        object : LinkedHashMap<String, HttpUrl>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, HttpUrl>): Boolean =
                size > capacity
        }

    /** The link to use instead of [original], if one has already been learned. */
    @Synchronized
    fun shortcutFor(original: HttpUrl): HttpUrl? = resolved[original.toString()]

    /**
     * Records where [original] led. A redirect back to itself is not worth remembering, and
     * remembering it would make every later lookup a pointless rewrite.
     */
    @Synchronized
    fun remember(original: HttpUrl, landedOn: HttpUrl) {
        if (original == landedOn) return
        resolved[original.toString()] = landedOn
    }

    /**
     * Drops what was learned for [original].
     *
     * Called the moment a remembered link is refused. A redirect target that has stopped working
     * must never be able to turn one refusal into every slice being refused, so the first failure
     * on a shortcut is also its last.
     */
    @Synchronized
    fun forget(original: HttpUrl) {
        resolved.remove(original.toString())
    }

    /** Forgotten wholesale when the route changes, like the rest of the CDN's memory of a network. */
    @Synchronized
    fun forgetAll() {
        resolved.clear()
    }

    private companion object {
        const val DEFAULT_CAPACITY = 16
    }
}
