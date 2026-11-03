package com.nikhil.yt.ui.screens.settings

internal data class StorageCacheSnapshot(val image: Long, val player: Long, val video: Long, val downloads: Long)

/** Zero is a successful cache accounting result, not a reason to walk the disk. */
internal fun storageCacheBytes(accountedBytes: () -> Long, directoryBytes: () -> Long): Long =
    try { accountedBytes() } catch (_: Exception) { directoryBytes() }
