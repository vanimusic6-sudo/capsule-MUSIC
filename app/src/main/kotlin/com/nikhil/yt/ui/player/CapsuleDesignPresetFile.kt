/*
 * Capsule MUSIC
 * File transport for player-layout presets.
 * GPL-3.0
 */

package com.nikhil.yt.ui.player

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.nikhil.yt.constants.CapsuleCustomizeTarget
import java.io.File
import java.util.Locale

internal const val CapsuleDesignPresetFileExtension = "capsule"
private const val CapsuleDesignPresetMaxChars = 64 * 1024

internal fun writeCapsuleDesignPresetFile(
    context: Context,
    target: CapsuleCustomizeTarget,
    payload: String,
): Pair<File, Uri> {
    val directory =
        File(context.cacheDir, "capsule-designs").apply {
            mkdirs()
        }
    val targetName =
        target.name
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9_-]"), "-")
    val file =
        File(
            directory,
            "Capsule-$targetName.$CapsuleDesignPresetFileExtension",
        )

    file.writeText(payload, Charsets.UTF_8)

    val uri =
        FileProvider.getUriForFile(
            context,
            "${context.packageName}.FileProvider",
            file,
        )

    return file to uri
}

/**
 * Preset files are intentionally tiny. Refuse oversized input instead of letting an arbitrary file
 * selected by a document provider be read unbounded into memory.
 */
internal fun readCapsuleDesignPresetFile(
    context: Context,
    uri: Uri,
): String? =
    runCatching {
        context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { reader ->
            val result = StringBuilder()
            val buffer = CharArray(4_096)

            while (true) {
                val read = reader.read(buffer)
                if (read < 0) break
                if (result.length + read > CapsuleDesignPresetMaxChars) {
                    return@use null
                }
                result.append(buffer, 0, read)
            }

            result.toString()
        }
    }.getOrNull()
