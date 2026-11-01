package com.nikhil.yt.playback.audio

import java.io.IOException

/** A pinned extractor can still reference an API missing on a particular Android runtime.
 * Keep that incompatibility inside the client attempt so the existing bounded plan can
 * advance. Coroutine cancellation and VM failures retain their original semantics.
 */
internal suspend fun <T> audioExtractorAttempt(extract: suspend () -> T): T =
    try {
        extract()
    } catch (incompatible: LinkageError) {
        throw AudioExtractorCompatibilityException(incompatible)
    }

internal class AudioExtractorCompatibilityException(cause: LinkageError) :
    IOException("AUDIO extractor is incompatible with this runtime", cause)
