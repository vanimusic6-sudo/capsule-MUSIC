package com.nikhil.yt.lyrics

import com.nikhil.yt.utils.reportException
import com.nikhil.yt.lyrics.LyricsUtils.isChinese
import com.nikhil.yt.lyrics.LyricsUtils.isJapanese
import com.nikhil.yt.lyrics.LyricsUtils.isKorean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Work belongs to the currently visible lyric document. Reopening reuses completed lines. */
internal suspend fun prepareLyricsRomanization(
    lines: List<LyricsEntry>, japanese: Boolean, korean: Boolean,
    romanizeJapanese: suspend (String) -> String = LyricsUtils::romanizeJapanese,
    romanizeKorean: suspend (String) -> String = LyricsUtils::romanizeKorean,
    onFailure: (Exception) -> Unit = { reportException(it) },
) = coroutineScope {
    for (entry in lines) {
        if (entry.romanizedTextFlow.value != null) continue
        val convert = when {
            japanese && isJapanese(entry.text) && !isChinese(entry.text) -> romanizeJapanese
            korean && isKorean(entry.text) -> romanizeKorean
            else -> continue
        }
        launch {
            try {
                val result = convert(entry.text)
                currentCoroutineContext().ensureActive()
                entry.romanizedTextFlow.value = result
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                onFailure(failure)
            }
        }
    }
}
