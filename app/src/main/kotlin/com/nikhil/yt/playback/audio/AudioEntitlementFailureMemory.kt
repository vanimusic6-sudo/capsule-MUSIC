package com.nikhil.yt.playback.audio

import com.nikhil.yt.innertube.YouTubeFailureKind
import com.nikhil.yt.playback.PlaybackFailureKind
import java.io.IOException

/** Preserve only explicit access verdicts, never network failures or raw server messages. */
internal fun terminalAudioEntitlement(kind: YouTubeFailureKind, authenticated: Boolean): PlaybackFailureKind? =
    when (kind) {
        YouTubeFailureKind.AGE_RESTRICTED ->
            if (authenticated) PlaybackFailureKind.AGE_RESTRICTED else PlaybackFailureKind.AUTH_REQUIRED
        YouTubeFailureKind.LOGIN_REQUIRED -> if (authenticated) null else PlaybackFailureKind.AUTH_REQUIRED
        else -> null
    }

internal class AudioEntitlementException(val kind: PlaybackFailureKind) :
    IOException("Audio access unavailable ($kind)")

/** Short-lived, process-local verdicts tied to the actual extraction session.
 * Session comparison is exact; no credentials or signed URLs are logged/persisted.
 */
internal class AudioEntitlementFailureMemory(
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private data class Entry(val kind: PlaybackFailureKind, val until: Long)
    private var session: Any? = null
    private val entries = LinkedHashMap<String, Entry>(16, .75f, true)

    @Synchronized fun get(identity: Any, id: String): PlaybackFailureKind? {
        if (session != identity) return null
        val entry = entries[id] ?: return null
        if (entry.until <= nowMs()) { entries.remove(id); return null }
        return entry.kind
    }

    @Synchronized fun record(identity: Any, id: String, kind: PlaybackFailureKind) {
        if (kind !in setOf(PlaybackFailureKind.AUTH_REQUIRED, PlaybackFailureKind.AGE_RESTRICTED,
                PlaybackFailureKind.ACCESS_RESTRICTED)) return
        if (session != identity) { entries.clear(); session = identity }
        entries[id] = Entry(kind, nowMs() + 300_000L)
        while (entries.size > 256) entries.remove(entries.keys.first())
    }

    @Synchronized fun clear() { entries.clear(); session = null }
}
