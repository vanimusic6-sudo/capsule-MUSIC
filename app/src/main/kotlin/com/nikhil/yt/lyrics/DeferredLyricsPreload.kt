package com.nikhil.yt.lyrics

import kotlinx.coroutines.delay

/** Admit only a settled, enabled queue request before snapshotting any songs. */
internal suspend fun <T> preloadSettledLyrics(
    readCount: suspend () -> Int,
    nextSongs: suspend (Int) -> List<T>,
    preload: suspend (List<T>) -> Unit,
) {
    delay(800L)
    val count = readCount().coerceIn(0, 10)
    if (count == 0) return
    val songs = nextSongs(count)
    if (songs.isNotEmpty()) preload(songs)
}

/** Snapshot at most N relevant metadata entries, never materialize the whole queue. */
internal fun <T : Any> nextLyricsPreloadItems(currentIndex: Int, itemCount: Int, count: Int, item: (Int) -> T?): List<T> {
    if (currentIndex !in 0 until itemCount || count <= 0) return emptyList()
    val end = minOf(itemCount.toLong(), currentIndex.toLong() + 1L + count.coerceAtMost(10)).toInt()
    return (currentIndex + 1 until end).mapNotNull(item)
}
