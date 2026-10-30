package com.nikhil.yt.ui.screens.artist

import android.content.Intent
import android.os.SystemClock
import androidx.annotation.DrawableRes
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Spring
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nikhil.yt.R
import com.nikhil.yt.ui.component.StandardChrome
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

// Warmth belongs to the candle, never to the artist photo or the app theme.
private val CandleLight = Color(0xFFD7AC74)

/**
 * A quiet, compact memorial ritual. No network posting or imaginary "send to
 * the artist" action: gratitude is shared only via the user's Android chooser.
 *
 * Closing the dialog cancels an active countdown. Playback stays paused until
 * the user explicitly resumes it, including after the full minute has passed.
 */
@Composable
internal fun ArtistMemorialDialog(
    name: String,
    memorial: ArtistMemorialInfo,
    onDismiss: () -> Unit,
    onMinuteBegin: () -> Unit,
) {
    val context = LocalContext.current
    var mode by remember(name) { mutableIntStateOf(0) }
    var seconds by remember(name) { mutableIntStateOf(60) }
    val gratitude = stringResource(R.string.artist_candle_message, name)
    val displayedDate = remember(memorial.deathDate) {
        runCatching {
            LocalDate.parse(memorial.deathDate).format(
                DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)
                    .withLocale(Locale.getDefault()),
            )
        }.getOrDefault(memorial.deathDate)
    }

    LaunchedEffect(mode) {
        if (mode == 1) {
            val endsAt = SystemClock.elapsedRealtime() + 60_000L
            while (seconds > 0) {
                delay(1_000L)
                seconds = ((endsAt - SystemClock.elapsedRealtime() + 999L) / 1_000L)
                    .toInt().coerceIn(0, 60)
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .padding(horizontal = 22.dp)
                .widthIn(max = 420.dp)
                .fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            color = StandardChrome.panel,
            border = BorderStroke(1.dp, StandardChrome.muted.copy(alpha = 0.20f)),
            shadowElevation = 18.dp,
        ) {
            Column(
                modifier = Modifier
                    .animateContentSize(
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMedium,
                        ),
                    )
                    .padding(horizontal = 22.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .background(CandleLight.copy(alpha = 0.12f), RoundedCornerShape(16.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.capsule_candle),
                            contentDescription = null,
                            tint = CandleLight,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.artist_candle_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = StandardChrome.text,
                        )
                        Text(
                            text = name,
                            color = StandardChrome.muted,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.close),
                            contentDescription = stringResource(R.string.artist_candle_close),
                            tint = StandardChrome.muted,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }

                HorizontalDivider(
                    color = StandardChrome.muted.copy(alpha = 0.14f),
                    thickness = 1.dp,
                )
                Text(
                    text = stringResource(R.string.artist_candle_source, displayedDate),
                    color = StandardChrome.muted,
                    style = MaterialTheme.typography.bodySmall,
                )

                Crossfade(
                    targetState = mode,
                    animationSpec = tween(durationMillis = 180),
                    label = "artist-candle-content",
                ) { activeMode ->
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        when (activeMode) {
                            0 -> {
                                MemorialActionRow(
                                    icon = R.drawable.capsule_candle,
                                    title = stringResource(R.string.artist_candle_minute),
                                    subtitle = stringResource(R.string.artist_candle_minute_desc),
                                    highlight = true,
                                ) {
                                    onMinuteBegin()
                                    seconds = 60
                                    mode = 1
                                }
                                MemorialActionRow(
                                    icon = R.drawable.favorite_border,
                                    title = stringResource(R.string.artist_candle_thanks),
                                    subtitle = stringResource(R.string.artist_candle_thanks_desc),
                                ) {
                                    mode = 2
                                }
                            }
                            1 -> {
                                Box(
                                    modifier = Modifier.fillMaxWidth().height(182.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator(
                                        progress = { seconds / 60f },
                                        modifier = Modifier.size(156.dp),
                                        color = CandleLight,
                                        trackColor = StandardChrome.muted.copy(alpha = 0.18f),
                                        strokeWidth = 3.dp,
                                        strokeCap = StrokeCap.Round,
                                    )
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(
                                            painter = painterResource(R.drawable.capsule_candle),
                                            contentDescription = null,
                                            tint = CandleLight,
                                            modifier = Modifier.size(22.dp),
                                        )
                                        Spacer(Modifier.height(8.dp))
                                        Text(
                                            text = String.format(
                                                Locale.ROOT, "%d:%02d",
                                                seconds / 60, seconds % 60,
                                            ),
                                            color = StandardChrome.text,
                                            style = MaterialTheme.typography.headlineLarge,
                                            fontWeight = FontWeight.Light,
                                            textAlign = TextAlign.Center,
                                        )
                                    }
                                }
                                Text(
                                    text = stringResource(
                                        if (seconds == 0) R.string.artist_candle_timer_done
                                        else R.string.artist_candle_paused,
                                    ),
                                    modifier = Modifier.fillMaxWidth(),
                                    color = StandardChrome.muted,
                                    style = MaterialTheme.typography.bodySmall,
                                    textAlign = TextAlign.Center,
                                )
                                MemorialBackAction { mode = 0 }
                            }
                            2 -> {
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(18.dp),
                                    color = StandardChrome.selected,
                                ) {
                                    Text(
                                        text = gratitude,
                                        modifier = Modifier.padding(18.dp),
                                        color = StandardChrome.text,
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                }
                                Text(
                                    text = stringResource(R.string.artist_candle_local),
                                    color = StandardChrome.muted,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                MemorialActionRow(
                                    icon = R.drawable.share,
                                    title = stringResource(R.string.artist_candle_share),
                                    subtitle = stringResource(R.string.artist_candle_share_desc),
                                    highlight = true,
                                ) {
                                    val intent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, gratitude)
                                    }
                                    context.startActivity(Intent.createChooser(intent, null))
                                }
                                MemorialBackAction { mode = 0 }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MemorialBackAction(onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = stringResource(R.string.artist_candle_back),
            color = StandardChrome.muted,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun MemorialActionRow(
    @DrawableRes icon: Int,
    title: String,
    subtitle: String,
    highlight: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
        shape = RoundedCornerShape(18.dp),
        color = StandardChrome.selected,
        border = BorderStroke(
            1.dp,
            if (highlight) CandleLight.copy(alpha = 0.25f)
            else StandardChrome.muted.copy(alpha = 0.16f),
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = if (highlight) CandleLight else StandardChrome.text,
                modifier = Modifier.size(22.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = StandardChrome.text,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = subtitle,
                    color = StandardChrome.muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Icon(
                painter = painterResource(R.drawable.arrow_forward),
                contentDescription = null,
                tint = StandardChrome.muted,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
