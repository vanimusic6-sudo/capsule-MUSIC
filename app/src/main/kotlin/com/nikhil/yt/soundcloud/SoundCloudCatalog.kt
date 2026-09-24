package com.nikhil.yt.soundcloud

import com.nikhil.yt.innertube.soundcloud.SoundCloudNewPipe

/**
 * Real SoundCloud results from the already bundled NewPipeExtractor.
 * No pasted API/OAuth token and no YouTube fallback or mislabeled matches.
 */
internal object SoundCloudCatalog {
    data class Track(
        val title: String,
        val artist: String,
        val artworkUrl: String?,
        val permalink: String,
        val durationSeconds: Long,
    )

    sealed interface Result {
        data class Tracks(val items: List<Track>) : Result
        data object RateLimited : Result
        data object Unavailable : Result
    }

    fun search(query: String): Result = try {
        Result.Tracks(
            SoundCloudNewPipe.search(query).map { track ->
                Track(
                    title = track.title,
                    artist = track.artist,
                    artworkUrl = track.artworkUrl,
                    permalink = track.url,
                    durationSeconds = track.durationSeconds,
                )
            }
        )
    } catch (exception: Exception) {
        val message = generateSequence(exception as Throwable?) { it.cause }
            .take(6)
            .mapNotNull { it?.message }
            .joinToString(" ")
        if ("429" in message || "rate limit" in message.lowercase()) {
            Result.RateLimited
        } else Result.Unavailable
    }
}
