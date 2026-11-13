/*
 * Capsule MUSIC — a tiny persisted colour snapshot of the last live VIDEO
 * frame. It is never a video bitmap, never a decoder, and contains no sound.
 * Restores correctly after Activity recreation or an app process restart.
 */
package com.nikhil.yt.ui.player

import android.content.Context
import android.os.SystemClock
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb

internal object CapsuleVideoAmbientResume {
    private const val PREFS = "capsule_last_video_ambient"
    private const val FIELD_KEY = "rgb"
    private const val MEDIA_KEY = "media_id"
    private const val SAVE_INTERVAL_MS = 16_000L
    private val recent = object : LinkedHashMap<String, CapsuleVideoAmbientFrame>(
        4, 0.75f, true,
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CapsuleVideoAmbientFrame>?) =
            size > 3
    }
    private var previousSavedId: String? = null
    private var previousSaveMs = 0L

    @Synchronized
    fun get(context: Context, mediaId: String?): CapsuleVideoAmbientFrame? {
        if (mediaId.isNullOrBlank()) return null
        recent[mediaId]?.let { return it }
        val preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (preferences.getString(MEDIA_KEY, null) != mediaId) return null
        val encoded = preferences.getString(FIELD_KEY, null) ?: return null
        val size = CAPSULE_FIELD_COLUMNS * CAPSULE_FIELD_ROWS
        if (encoded.length != size * 6) return null
        val colors = try {
            List(size) { i ->
                val rgb = encoded.substring(i * 6, (i + 1) * 6).toInt(16)
                Color(0xFF000000.toInt() or rgb)
            }
        } catch (_: NumberFormatException) {
            return null
        }
        val field = CapsuleColorField(CAPSULE_FIELD_COLUMNS, CAPSULE_FIELD_ROWS, colors)
        return CapsuleVideoAmbientFrame(mediaId, capsuleBandsFromField(field), field)
            .also { recent[mediaId] = it }
    }

    @Synchronized
    fun put(context: Context, frame: CapsuleVideoAmbientFrame) {
        if (frame.mediaId.isBlank()) return
        recent[frame.mediaId] = frame
        val now = SystemClock.elapsedRealtime()
        if (previousSavedId == frame.mediaId &&
            now - previousSaveMs in 0 until SAVE_INTERVAL_MS
        ) return
        previousSavedId = frame.mediaId
        previousSaveMs = now
        // One tiny snapshot every ~16s, or immediately for a new song.
        // Activity/process restoration does not depend on Play being pressed.
        val encoded = buildString(frame.field.cells.size * 6) {
            for (color in frame.field.cells) {
                append((color.toArgb() and 0xFFFFFF).toString(16).padStart(6, '0'))
            }
        }
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(MEDIA_KEY, frame.mediaId)
            .putString(FIELD_KEY, encoded).apply()
    }

    /** Only used by tests; does not change persistent SharedPreferences. */
    @Synchronized
    internal fun clearMemory() {
        recent.clear()
        previousSavedId = null
        previousSaveMs = 0L
    }
}

private fun capsuleBandsFromField(field: CapsuleColorField): List<Color> = List(7) { band ->
    val begin = band * field.width / 7
    val end = ((band + 1) * field.width / 7).coerceAtLeast(begin + 1)
    var rr = 0f
    var gg = 0f
    var bb = 0f
    var count = 0
    for (y in field.height / 6 until field.height * 5 / 6) for (x in begin until end) {
        val sample = field.cell(x, y)
        rr += sample.red
        gg += sample.green
        bb += sample.blue
        count++
    }
    if (count == 0) Color(0xFF101014) else lerp(
        Color(rr / count, gg / count, bb / count),
        Color(0xFF101014), 0.50f,
    )
}
