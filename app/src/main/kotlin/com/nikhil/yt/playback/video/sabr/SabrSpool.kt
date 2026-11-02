/*
 * Capsule VIDEO SABR disk storage.
 * GPL-3.0; compatible with ZemerTeam/zemer-app SABR buffer.
 */
package com.nikhil.yt.playback.video.sabr

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * One bounded directory for ephemeral SABR video/audio segment reassembly.
 * A process crash cannot leave files pinned in cache permanently.
 */
internal object SabrSpool {
    @Volatile private var directory: File? = null

    @Synchronized fun init(context: Context) {
        if (directory != null) return
        val path = File(context.cacheDir, "capsule-video-sabr")
        check(path.isDirectory || path.mkdirs()) { "Cannot allocate VIDEO SABR spool" }
        path.listFiles()?.filter { it.name.endsWith(".part") }?.forEach {
            runCatching { it.delete() }
        }
        directory = path
    }

    fun partFile(mediaId: String, itag: Int, suffix: String = ""): File {
        val parent = checkNotNull(directory) { "SABR spool not initialized" }
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(mediaId.toByteArray(Charsets.UTF_8))
            .take(8).joinToString("") { "%02x".format(it) }
        return File(parent, "$hash-$itag-$suffix-${UUID.randomUUID()}.part")
    }
}
