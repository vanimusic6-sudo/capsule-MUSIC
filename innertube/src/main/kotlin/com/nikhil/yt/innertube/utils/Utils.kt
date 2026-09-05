/*
 * Velune Project Original (2026)
 * Kòi Natsuko (github.com/koiverse)
 * Licensed Under GPL-3.0 | see git history for contributors
 */



package com.nikhil.yt.innertube.utils

import com.nikhil.yt.innertube.YouTube
import com.nikhil.yt.innertube.runCatchingCancellable
import com.nikhil.yt.innertube.pages.LibraryPage
import com.nikhil.yt.innertube.pages.PlaylistPage
import java.security.MessageDigest

@JvmName("completedLibrary")
suspend fun Result<PlaylistPage>.completed(): Result<PlaylistPage> = runCatchingCancellable {
    val page = getOrThrow()
    val songs = collectCompletePages(page.songs, page.songsContinuation) { token ->
        val next = YouTube.playlistContinuation(token).getOrThrow()
        next.songs to next.continuation
    }
    page.copy(songs = songs, songsContinuation = null)
}

@JvmName("completedPlaylist")
suspend fun Result<LibraryPage>.completed(): Result<LibraryPage> = runCatchingCancellable {
    val page = getOrThrow()
    val items = collectCompletePages(page.items, page.continuation) { token ->
        val next = YouTube.libraryContinuation(token).getOrThrow()
        next.items to next.continuation
    }
    page.copy(items = items, continuation = null)
}

fun ByteArray.toHex(): String = joinToString(separator = "") { eachByte -> "%02x".format(eachByte) }

fun sha1(str: String): String = MessageDigest.getInstance("SHA-1").digest(str.toByteArray()).toHex()

fun parseCookieString(cookie: String): Map<String, String> =
    cookie.split(";")
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .mapNotNull { part ->
            val splitIndex = part.indexOf('=')
            if (splitIndex == -1) {
                null
            } else {
                val key = part.substring(0, splitIndex).trim()
                if (key.isEmpty()) null else key to part.substring(splitIndex + 1).trim()
            }
        }
        .toMap()

fun String.parseTime(): Int? {
    try {
        val parts = split(":").map { it.toInt() }
        if (parts.size == 2) {
            return parts[0] * 60 + parts[1]
        }
        if (parts.size == 3) {
            return parts[0] * 3600 + parts[1] * 60 + parts[2]
        }
    } catch (e: Exception) {
        return null
    }
    return null
}

fun isPrivateId(browseId: String): Boolean {
    return browseId.contains("privately")
}
