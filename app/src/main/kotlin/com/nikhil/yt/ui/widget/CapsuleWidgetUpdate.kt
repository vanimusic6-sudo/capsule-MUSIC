/**
 * Capsule MUSIC
 * Feeding the home-screen widgets.
 * GPL-3.0
 */

package com.nikhil.yt.ui.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.datastore.preferences.core.MutablePreferences
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.palette.graphics.Palette
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.net.URL

/**
 * One scope for widget updates.
 *
 * The previous version created a fresh CoroutineScope on every track change and never cancelled
 * it, so a long listening session left one dangling scope per song.
 */
private val widgetScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

private const val ART_SIDE = 256



/**
 * Pushes the current track to every placed Capsule widget.
 *
 * Artwork is fetched and the panel colour derived only when the track actually changes, so the
 * per-second calls that carry nothing but a new position do no work beyond writing one float.
 */
fun updateCapsuleWidgets(
    context: Context,
    title: String,
    artist: String,
    isPlaying: Boolean,
    thumbnailUrl: String?,
    progress: Float,
) {
    widgetScope.launch {
        val manager = GlanceAppWidgetManager(context)
        val placed = manager.getGlanceIds(CapsuleBarWidget::class.java)
        if (placed.isEmpty()) return@launch

        val anyId = placed.first()
        val existing =
            getAppWidgetState(
                context = context,
                glanceId = anyId,
                definition = PreferencesGlanceStateDefinition,
            )

        val trackChanged = existing[widgetTitleKey] != title
        var artPath = existing[widgetArtPathKey]
        var surface = existing[widgetBgColorKey] ?: CAPSULE_WIDGET_FALLBACK_SURFACE

        if (trackChanged && !thumbnailUrl.isNullOrBlank()) {
            runCatching {
                val connection = URL(thumbnailUrl).openConnection()
                connection.connect()
                val decoded =
                    connection.getInputStream().use { BitmapFactory.decodeStream(it) }
                        ?: return@runCatching
                val scaled = Bitmap.createScaledBitmap(decoded, ART_SIDE, ART_SIDE, true)
                val file = File(context.cacheDir, "widget_art.png")
                FileOutputStream(file).use { out ->
                    scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                artPath = file.absolutePath
                surface = capsuleWidgetSurface(capsuleWidgetArtworkColor(Palette.from(scaled).generate()))
            }
        }

        val write: MutablePreferences.() -> Unit = {
            this[widgetTitleKey] = title
            this[widgetArtistKey] = artist
            this[widgetIsPlayingKey] = isPlaying
            this[widgetProgressKey] = progress.coerceIn(0f, 1f)
            this[widgetBgColorKey] = surface
            this[widgetTextColorKey] = CAPSULE_WIDGET_INK
            artPath?.let { this[widgetArtPathKey] = it }
        }

        val widget = CapsuleBarWidget()
        placed.forEach { glanceId ->
            updateAppWidgetState(context, glanceId, write)
            widget.update(context, glanceId)
        }
    }
}

