package com.nikhil.yt.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Last confirmed content failure, never inferred from network/CDN failures. Bounded to this session. */
internal class TrackAvailabilityStore(private val capacity: Int = 256) {
    private val mutable = MutableStateFlow<Map<String, PlaybackFailureKind>>(emptyMap())
    val states = mutable.asStateFlow()

    fun record(id: String, kind: PlaybackFailureKind) {
        if (kind !in setOf(PlaybackFailureKind.UNAVAILABLE, PlaybackFailureKind.AUTH_REQUIRED,
                PlaybackFailureKind.AGE_RESTRICTED, PlaybackFailureKind.ACCESS_RESTRICTED)) return
        mutable.update { previous ->
            (previous - id + (id to kind)).entries.toList().takeLast(capacity)
                .associate { it.toPair() }
        }
    }
    fun clearAll() { mutable.value = emptyMap() }
    fun clear(id: String) { mutable.update { it - id } }
}

internal val TrackAvailability = TrackAvailabilityStore()
