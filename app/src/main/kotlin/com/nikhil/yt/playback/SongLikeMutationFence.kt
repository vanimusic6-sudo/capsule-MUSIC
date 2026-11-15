package com.nikhil.yt.playback

/** A remote snapshot must not undo a local like/unlike accepted after that request began. */
internal class SongLikeMutationFence(private val capacity: Int = 1024) {
    private var version = 0L
    private val mutations = LinkedHashMap<String, Long>()

    @Synchronized fun snapshot(): Long = version

    @Synchronized fun record(mediaId: String) {
        if (mediaId.isBlank()) return
        mutations.remove(mediaId)
        mutations[mediaId] = ++version
        while (mutations.size > capacity) mutations.remove(mutations.keys.first())
    }

    @Synchronized fun changedSince(mediaId: String, requestVersion: Long): Boolean =
        (mutations[mediaId] ?: 0L) > requestVersion

    companion object {
        val Shared = SongLikeMutationFence()
    }
}
