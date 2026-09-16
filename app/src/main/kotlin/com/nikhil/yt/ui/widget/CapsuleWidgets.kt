/**
 * Capsule MUSIC
 * The three home-screen widgets: bar, shelf and vinyl.
 * GPL-3.0
 */

package com.nikhil.yt.ui.widget

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity as actionStartActivityIntent
import androidx.glance.appwidget.action.actionStartService
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.layout.wrapContentHeight
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.nikhil.yt.R
import com.nikhil.yt.playback.MusicService
import org.json.JSONArray

/*
 * The state the launcher holds for a placed widget.
 *
 * These carry their original names because they are already written into every placed widget's
 * stored Preferences; renaming them would silently blank every widget on an update.
 */
val widgetTitleKey = stringPreferencesKey("widget_title")
val widgetArtistKey = stringPreferencesKey("widget_artist")
val widgetArtPathKey = stringPreferencesKey("widget_art_path")
val widgetIsPlayingKey = booleanPreferencesKey("widget_is_playing")
val widgetBgColorKey = intPreferencesKey("widget_bg_color")
val widgetTextColorKey = intPreferencesKey("widget_text_color")

/** How far through the track we are, 0..1. Written when the service reports it, never polled. */
val widgetProgressKey = floatPreferencesKey("widget_progress")

/**
 * The playlist shelf.
 *
 * Glance state is a Preferences bag with no list type, so the shelf is stored as a JSON array of
 * [id, name] pairs. JSON rather than a separator because a playlist name is the user's text and
 * can contain anything, including whatever character seemed safe to delimit on.
 */
val widgetPlaylistsKey = stringPreferencesKey("widget_playlists")

/** id to name, in shelf order. */
internal fun encodeWidgetPlaylists(playlists: List<Pair<String, String>>): String {
    val array = JSONArray()
    playlists.forEach { (id, name) ->
        array.put(JSONArray().put(id).put(name))
    }
    return array.toString()
}

internal fun decodeWidgetPlaylists(packed: String?): List<Pair<String, String>> {
    if (packed.isNullOrEmpty()) return emptyList()
    return runCatching {
        val array = JSONArray(packed)
        (0 until array.length()).mapNotNull { index ->
            val record = array.optJSONArray(index) ?: return@mapNotNull null
            val id = record.optString(0)
            if (id.isEmpty()) null else id to record.optString(1)
        }
    }.getOrDefault(emptyList())
}

/** Everything the three widgets read, resolved once per render. */
private class WidgetState(prefs: Preferences) {
    val title: String = prefs[widgetTitleKey]?.takeIf { it.isNotBlank() } ?: "Capsule"
    val artist: String = prefs[widgetArtistKey].orEmpty()
    val isPlaying: Boolean = prefs[widgetIsPlayingKey] ?: false
    val artPath: String? = prefs[widgetArtPathKey]
    val progress: Float = (prefs[widgetProgressKey] ?: 0f).coerceIn(0f, 1f)
    val surface: Int = prefs[widgetBgColorKey] ?: CAPSULE_WIDGET_FALLBACK_SURFACE
    val playlists: List<Pair<String, String>> = decodeWidgetPlaylists(prefs[widgetPlaylistsKey])

    val panel = ColorProvider(ComposeColor(surface))
    val ink = ColorProvider(ComposeColor(CAPSULE_WIDGET_INK))
    val inkDim = ColorProvider(ComposeColor(CAPSULE_WIDGET_INK_DIM))

    /**
     * The comet is white while something is playing and grey while it is not.
     *
     * That is the whole of its animation, on purpose. A widget cannot move without waking the
     * launcher to redraw it, and a mark that turns forever on someone's home screen is a cost with
     * no information in it: the one thing it has to say is whether the music is running, and a
     * colour says that for free.
     */
    val comet =
        ColorProvider(
            ComposeColor(if (isPlaying) CAPSULE_WIDGET_INK else CAPSULE_WIDGET_INK_DIM),
        )
}

@Composable
private fun artworkProvider(state: WidgetState): ImageProvider {
    val bitmap = state.artPath?.let { runCatching { BitmapFactory.decodeFile(it) }.getOrNull() }
    return if (bitmap != null) ImageProvider(bitmap) else ImageProvider(R.drawable.ic_velune_concept)
}

@Composable
private fun openAppAction(): Action =
    actionStartActivity(
        ComponentName(LocalContext.current.packageName, "com.nikhil.yt.MainActivity"),
    )

@Composable
private fun openPlaylistAction(playlistId: String): Action {
    val context = LocalContext.current
    val intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("velune://playlist/" + playlistId)).apply {
            component = ComponentName(context.packageName, "com.nikhil.yt.MainActivity")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
    return actionStartActivityIntent(intent)
}

@Composable
private fun transportAction(action: String): Action {
    val context = LocalContext.current
    return actionStartService(
        Intent(context, MusicService::class.java).apply { this.action = action },
        isForegroundService = true,
    )
}

@Composable
private fun TransportIcon(
    iconRes: Int,
    description: String,
    tint: ColorProvider,
    action: String,
    boxSize: Int,
    iconSize: Int,
) {
    Box(
        modifier = GlanceModifier.size(boxSize.dp).clickable(transportAction(action)),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(iconRes),
            contentDescription = description,
            colorFilter = ColorFilter.tint(tint),
            modifier = GlanceModifier.size(iconSize.dp),
        )
    }
}

/** Previous, comet, next: the row every widget shares. */
@Composable
private fun Transport(state: WidgetState) {
    Row(
        modifier = GlanceModifier.wrapContentHeight(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TransportIcon(
            iconRes = R.drawable.ic_skip_previous,
            description = "Previous",
            tint = state.ink,
            action = "com.nikhil.yt.ACTION_PREV",
            boxSize = 42,
            iconSize = 24,
        )
        TransportIcon(
            iconRes = R.drawable.ic_capsule_orbit,
            description = if (state.isPlaying) "Pause" else "Play",
            tint = state.comet,
            action = "com.nikhil.yt.ACTION_PLAY_PAUSE",
            boxSize = 54,
            iconSize = 44,
        )
        TransportIcon(
            iconRes = R.drawable.ic_skip_next,
            description = "Next",
            tint = state.ink,
            action = "com.nikhil.yt.ACTION_NEXT",
            boxSize = 42,
            iconSize = 24,
        )
    }
}

/**
 * The progress line.
 *
 * Two boxes rather than a ProgressBar: it only ever needs to be two rectangles whose split moves
 * when the service says so, and every widget redraw is a round trip through the launcher.
 */
@Composable
private fun ProgressLine(state: WidgetState) {
    Box(
        modifier = GlanceModifier.fillMaxWidth().height(3.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier =
                GlanceModifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .cornerRadius(1.dp)
                    .background(state.inkDim),
        ) {}
        // Glance has no fractional width, so the filled part is measured against the widget size
        // the launcher reports rather than taken as a weight.
        val filled = (LocalSize.current.width.value * state.progress).toInt().coerceAtLeast(0)
        Box(
            modifier =
                GlanceModifier
                    .width(filled.dp)
                    .height(2.dp)
                    .cornerRadius(1.dp)
                    .background(state.ink),
        ) {}
    }
}

@Composable
private fun NowPlaying(
    state: WidgetState,
    artSize: Int,
) {
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            provider = artworkProvider(state),
            contentDescription = state.title,
            modifier =
                GlanceModifier
                    .size(artSize.dp)
                    .cornerRadius(14.dp)
                    .clickable(openAppAction()),
        )
        Spacer(GlanceModifier.width(12.dp))
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                text = state.title,
                style =
                    TextStyle(
                        color = state.ink,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                maxLines = 1,
            )
            if (state.artist.isNotEmpty()) {
                Text(
                    text = state.artist,
                    style = TextStyle(color = state.inkDim, fontSize = 12.sp),
                    maxLines = 1,
                )
            }
        }
        Transport(state)
    }
}

@Composable
private fun WidgetPanel(
    state: WidgetState,
    content: @Composable () -> Unit,
) {
    Column(
        modifier =
            GlanceModifier
                .fillMaxSize()
                .appWidgetBackground()
                .cornerRadius(26.dp)
                .background(state.panel)
                .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}

private const val SHELF_TILES = 5

@Composable
private fun PlaylistShelf(state: WidgetState) {
    Row(modifier = GlanceModifier.fillMaxWidth()) {
        state.playlists.take(SHELF_TILES).forEachIndexed { index, entry ->
            if (index > 0) Spacer(GlanceModifier.width(8.dp))
            Box(
                modifier =
                    GlanceModifier
                        .defaultWeight()
                        .height(58.dp)
                        .cornerRadius(14.dp)
                        .background(state.inkDim)
                        .clickable(openPlaylistAction(entry.first))
                        .padding(6.dp),
                contentAlignment = Alignment.BottomStart,
            ) {
                Text(
                    text = entry.second,
                    style = TextStyle(color = state.ink, fontSize = 10.sp),
                    maxLines = 2,
                )
            }
        }
    }
}

/** The plain one: what is playing, and the three controls. */
class CapsuleBarWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    @SuppressLint("RestrictedApi")
    override suspend fun provideGlance(
        context: Context,
        id: GlanceId,
    ) {
        provideContent {
            val state = WidgetState(currentState())
            WidgetPanel(state) {
                NowPlaying(state, artSize = 58)
                Spacer(GlanceModifier.height(10.dp))
                ProgressLine(state)
            }
        }
    }
}

/** The same, with the playlists you have saved underneath. */
class CapsuleShelfWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    @SuppressLint("RestrictedApi")
    override suspend fun provideGlance(
        context: Context,
        id: GlanceId,
    ) {
        provideContent {
            val state = WidgetState(currentState())
            WidgetPanel(state) {
                NowPlaying(state, artSize = 58)
                Spacer(GlanceModifier.height(10.dp))
                ProgressLine(state)
                Spacer(GlanceModifier.height(12.dp))
                PlaylistShelf(state)
            }
        }
    }
}

/** The record: a round cover with the comet resting on its edge. */
class CapsuleVinylWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    @SuppressLint("RestrictedApi")
    override suspend fun provideGlance(
        context: Context,
        id: GlanceId,
    ) {
        provideContent {
            val state = WidgetState(currentState())
            val side = minOf(LocalSize.current.width.value, LocalSize.current.height.value).toInt()
            Box(
                modifier = GlanceModifier.fillMaxSize().appWidgetBackground(),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    provider = artworkProvider(state),
                    contentDescription = state.title,
                    modifier =
                        GlanceModifier
                            .size(side.dp)
                            // Glance has no circle shape; half the side as a corner radius is one.
                            .cornerRadius((side / 2).dp)
                            .clickable(openAppAction()),
                )
                Box(
                    modifier = GlanceModifier.fillMaxSize(),
                    contentAlignment = Alignment.BottomEnd,
                ) {
                    Image(
                        provider = ImageProvider(R.drawable.ic_capsule_orbit),
                        contentDescription = if (state.isPlaying) "Pause" else "Play",
                        colorFilter = ColorFilter.tint(state.comet),
                        modifier =
                            GlanceModifier
                                .size((side * 2 / 5).dp)
                                .clickable(transportAction("com.nikhil.yt.ACTION_PLAY_PAUSE")),
                    )
                }
            }
        }
    }
}

class CapsuleBarWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CapsuleBarWidget()
}

class CapsuleShelfWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CapsuleShelfWidget()
}

class CapsuleVinylWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CapsuleVinylWidget()
}
