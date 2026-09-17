/**
 * Capsule MUSIC
 * The home-screen widget.
 * GPL-3.0
 */

package com.nikhil.yt.ui.widget

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.Action
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
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

/*
 * There is no progress line, and that is a decision rather than an omission.
 *
 * A widget only knows what it was last told, and the service tells it on track changes and on
 * play/pause — so a line drawn from that stands still for the whole song and lies for most of it.
 * Making it true means something waking up to say "still playing, a bit further along", over and
 * over, for as long as music runs: a timer whose entire output is a few pixels moving on a home
 * screen nobody is looking at. That is the same trade we refused for the comet, and the answer is
 * the same. A wrong line is worse than no line, and a right one costs more than it is worth.
 *
 * Losing it also loses seeking by tap, which had nothing else to live on.
 */

/** Everything the widget reads, resolved once per render. */
private class WidgetState(prefs: Preferences) {
    val title: String = prefs[widgetTitleKey]?.takeIf { it.isNotBlank() } ?: "Capsule"
    val artist: String = prefs[widgetArtistKey].orEmpty()
    val isPlaying: Boolean = prefs[widgetIsPlayingKey] ?: false
    val artPath: String? = prefs[widgetArtPathKey]
    val surface: Int = prefs[widgetBgColorKey] ?: CAPSULE_WIDGET_FALLBACK_SURFACE

    val panel = ColorProvider(ComposeColor(surface))
    val ink = ColorProvider(ComposeColor(CAPSULE_WIDGET_INK))
    val inkDim = ColorProvider(ComposeColor(CAPSULE_WIDGET_INK_DIM))

    /**
     * The comet is solid white while something is playing, and plainly grey and mostly faded out
     * while it is not.
     *
     * That is the whole of its animation, on purpose. A widget cannot move without waking the
     * launcher to redraw it, and a mark turning forever on someone's home screen is a cost with no
     * information in it: the one thing it has to say is whether the music is running, and a colour
     * says that for free.
     */
    val comet =
        ColorProvider(
            ComposeColor(if (isPlaying) CAPSULE_WIDGET_INK else CAPSULE_WIDGET_COMET_PAUSED),
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

/** Previous, comet, next. */
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
            boxSize = 36,
            iconSize = 22,
        )
        TransportIcon(
            iconRes = R.drawable.ic_capsule_orbit,
            description = if (state.isPlaying) "Pause" else "Play",
            tint = state.comet,
            action = "com.nikhil.yt.ACTION_PLAY_PAUSE",
            boxSize = 46,
            iconSize = 38,
        )
        TransportIcon(
            iconRes = R.drawable.ic_skip_next,
            description = "Next",
            tint = state.ink,
            action = "com.nikhil.yt.ACTION_NEXT",
            boxSize = 36,
            iconSize = 22,
        )
    }
}

@Composable
private fun NowPlaying(state: WidgetState) {
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            provider = artworkProvider(state),
            contentDescription = state.title,
            modifier =
                GlanceModifier
                    .size(46.dp)
                    .cornerRadius(12.dp)
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

/** What is playing, and the three controls. One row, nothing that has to be kept true. */
class CapsuleBarWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    @SuppressLint("RestrictedApi")
    override suspend fun provideGlance(
        context: Context,
        id: GlanceId,
    ) {
        provideContent {
            val state = WidgetState(currentState())
            Column(
                modifier =
                    GlanceModifier
                        .fillMaxSize()
                        .appWidgetBackground()
                        .cornerRadius(26.dp)
                        .background(state.panel)
                        // The panel is the frame and the launcher adds its own margin around it,
                        // so the artwork sets the height and the padding only keeps it off the
                        // corners.
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NowPlaying(state)
            }
        }
    }
}

class CapsuleBarWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CapsuleBarWidget()
}
