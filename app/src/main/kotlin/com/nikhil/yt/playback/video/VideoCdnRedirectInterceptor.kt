package com.nikhil.yt.playback.video

import com.nikhil.yt.innertube.video.VideoStreamValidation
import com.nikhil.yt.playback.audio.forCdnTarget
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import timber.log.Timber

/** Separate per-network, bounded redirect memory. Never persists signed URLs. */
internal class VideoCdnRedirectMemory(
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private data class Entry(val target: HttpUrl, val until: Long)
    private val entries = LinkedHashMap<Pair<Long, HttpUrl>, Entry>(16, .75f, true)

    @Synchronized fun get(network: Long?, origin: HttpUrl): HttpUrl? {
        val key = (network ?: return null) to origin
        val entry = entries[key] ?: return null
        val expirySeconds = entry.target.queryParameter("expire")?.toLongOrNull()
        if (entry.until <= nowMs() ||
            (expirySeconds != null && expirySeconds <= System.currentTimeMillis() / 1000L + 15L)
        ) { entries.remove(key); return null }
        return entry.target
    }

    @Synchronized fun remember(network: Long?, origin: HttpUrl, target: HttpUrl) {
        if (network == null || origin == target || origin.queryParameter("range") != null ||
            target.queryParameter("range") != null || !VideoStreamValidation.isSupportedUrl(target.toString())) return
        // Successful ranges extend the short lease; a pause should not
        // immediately cost another full redirect/TLS handshake.
        entries[network to origin] = Entry(target, nowMs() + 180_000L)
        while (entries.size > 32) entries.remove(entries.keys.first())
    }

    @Synchronized fun forget(network: Long?, origin: HttpUrl) {
        if (network != null) entries.remove(network to origin)
    }
}

/** The same confirmed endpoint may serve subsequent byte ranges without an extra
 * redirect/socket. Preserve the complete server-provided URL and Range header;
 * no rewriting signatures or credentials, no cross-origin redirects outside
 * the validated media domain. A 429 always reaches the request guard. */
internal class VideoCdnRedirectInterceptor(
    private val memory: VideoCdnRedirectMemory = VideoCdnRedirectMemory(),
    private val networkIdentity: () -> Long?,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val origin = chain.request()
        if (!VideoStreamValidation.isSupportedUrl(origin.url.toString())) throw IOException("Untrusted VIDEO media address")
        if (origin.method != "GET" && origin.method != "HEAD") throw IOException("Unsupported VIDEO media method")
        val network = networkIdentity()
        val clean = origin.newBuilder().removeHeader("Authorization").removeHeader("Cookie")
            .removeHeader("X-Goog-AuthUser").removeHeader("X-Goog-PageId").build()
        val shortcut = memory.get(network, origin.url)?.takeUnless {
            VideoRejectedLinks.isRejected(it.toString())
        }
        var current = if (shortcut != null) clean.forCdnTarget(shortcut) else clean
        var usingShortcut = shortcut != null
        val visited = mutableSetOf<HttpUrl>()
        repeat(6) {
            if (networkIdentity() != network) throw IOException("VIDEO network changed during CDN open")
            if (VideoRejectedLinks.isRejected(current.url.toString())) {
                VideoRejectedLinks.reject(origin.url.toString())
                memory.forget(network, origin.url)
                throw VideoRetiredLinkException()
            }
            if (!visited.add(current.url)) throw IOException("VIDEO CDN redirect loop")
            val response = try {
                chain.proceed(current)
            } catch (failure: IOException) {
                if (usingShortcut) memory.forget(network, origin.url)
                throw failure
            }
            if (usingShortcut && response.code == 404) {
                response.close()
                memory.forget(network, origin.url)
                usingShortcut = false
                current = clean
                return@repeat
            }
            if (response.code !in setOf(301, 302, 303, 307, 308)) {
                if (response.code == 206 && response.header("Content-Range") != null &&
                    networkIdentity() == network) memory.remember(network, origin.url, current.url)
                if (response.code == 403 || response.code == 410) VideoRejectedLinks.reject(current.url.toString())
                if (!response.isSuccessful) memory.forget(network, origin.url)
                return response
            }
            val target = response.header("Location")?.let(current.url::resolve)
            response.close()
            if (target == null || !VideoStreamValidation.isSupportedUrl(target.toString())) {
                throw IOException("Untrusted VIDEO CDN redirect")
            }
            if (com.nikhil.yt.utils.GlobalLog.isEnabled)
                Timber.tag("CapsuleVideo").d("video-cdn-redirect from=%s to=%s", current.url.host, target.host)
            current = current.forCdnTarget(target)
            usingShortcut = false
        }
        throw IOException("VIDEO CDN redirect limit")
    }
}
