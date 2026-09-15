package com.nikhil.yt.playback.audio

import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import java.io.IOException

/** A media id is a song identity, not an identity of its encoded bytes. */
internal object AudioCacheIdentity {
    private const val PREFIX = "capsule:audio:"
    private const val FORMAT_KEY = "custom_capsule_audio_format"

    fun key(mediaId: String, playback: CapsuleAudioEngine.PlaybackData): String =
        "$PREFIX$mediaId:${playback.format.itag}:${playback.format.contentLength ?: -1}"

    fun mediaId(key: String): String =
        if (key.startsWith(PREFIX)) key.removePrefix(PREFIX).substringBefore(':') else key

    fun keys(cache: Cache, mediaId: String): List<String> =
        cache.keys.filter { mediaId(it) == mediaId }

    fun completeKey(cache: Cache, mediaId: String, legacyLength: Long? = null): String? =
        keys(cache, mediaId).firstOrNull { key -> isComplete(cache, key, legacyLength.takeIf { key == mediaId }) }

    fun isComplete(cache: Cache, key: String, legacyLength: Long? = null): Boolean {
        val length = ContentMetadata.getContentLength(cache.getContentMetadata(key))
            .takeIf { it > 0 } ?: legacyLength?.takeIf { it > 0 } ?: return false
        return cache.isCached(key, 0, length)
    }

    fun setLength(cache: Cache, key: String, length: Long?) {
        if (length == null || length <= 0) return
        cache.applyContentMetadataMutations(key, ContentMetadataMutations().apply {
            ContentMetadataMutations.setContentLength(this, length)
        })
    }

    /** Called before a downloader is created, so its first cached read sees one format only. */
    fun prepareDownload(cache: Cache, mediaId: String, formatKey: String, length: Long?) {
        if (isComplete(cache, mediaId)) return
        val previous = cache.getContentMetadata(mediaId).get(FORMAT_KEY, "")
        if (previous != formatKey) cache.removeResource(mediaId)
        cache.applyContentMetadataMutations(mediaId, ContentMetadataMutations().set(FORMAT_KEY, formatKey))
        setLength(cache, mediaId, length)
    }

    fun remove(cache: Cache, mediaId: String) = keys(cache, mediaId).forEach(cache::removeResource)
}

internal class AudioFormatChangedException : IOException("Audio format changed; recreate the media source")

/** Media3 byte offsets and extractor state belong to the first format opened by this source. */
internal class AudioStreamContract {
    private var key: String? = null
    fun bind(next: String) {
        if (key != null && key != next) throw AudioFormatChangedException()
        key = next
    }
}
