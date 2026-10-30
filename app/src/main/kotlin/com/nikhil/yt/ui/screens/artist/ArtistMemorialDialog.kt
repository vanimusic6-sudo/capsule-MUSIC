package com.nikhil.yt.ui.screens.artist

import android.content.Context
import android.os.SystemClock
import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nikhil.yt.R
import com.nikhil.yt.ui.component.CapsuleCandleIcon
import com.nikhil.yt.ui.component.CapsuleFlame
import com.nikhil.yt.ui.component.CapsuleFlameCore
import com.nikhil.yt.ui.component.StandardChrome
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Compact memorial with two actions and no external posting. The user may
 * save a personal tribute locally; submission is never represented as a
 * network send. Dismissal outside the dialog remains disabled.
 *
 * No Crossfade or animateContentSize: mode changes cannot paint two layouts
 * simultaneously, and the minute of silence remains on a single timer.
 */
@Composable
internal fun ArtistMemorialDialog(
    name: String,
    memorial: ArtistMemorialInfo,
    onDismiss: () -> Unit,
    onMinuteBegin: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember(context) {
        context.getSharedPreferences("capsule_artist_memorial_notes", Context.MODE_PRIVATE)
    }
    val noteKey = remember(memorial.sourceUrl) { "artist:" + memorial.sourceUrl }
    var note by rememberSaveable(noteKey) {
        mutableStateOf(prefs.getString(noteKey, "").orEmpty())
    }
    // "Send" deliberately completes a local candle tribute rather than
    // pretending to post text to a server that Capsule does not operate.
    var sentNote by rememberSaveable(noteKey) {
        mutableStateOf(prefs.getString("submitted:$noteKey", "").orEmpty())
    }
    val isSubmitted = note.isNotBlank() && sentNote == note.trim()
    val latestNote by rememberUpdatedState(note)
    // Persist after a short idle period, and flush the final input on dismissal.
    LaunchedEffect(noteKey, note) {
        delay(500L)
        prefs.edit().putString(noteKey, note).apply()
    }
    DisposableEffect(noteKey, prefs) {
        onDispose { prefs.edit().putString(noteKey, latestNote).apply() }
    }

    var mode by rememberSaveable(name) { mutableIntStateOf(0) }
    var seconds by rememberSaveable(name) { mutableIntStateOf(60) }
    var endsAt by rememberSaveable(name) { mutableLongStateOf(0L) }
    val displayedDate = remember(memorial.deathDate) {
        runCatching {
            LocalDate.parse(memorial.deathDate).format(
                DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)
                    .withLocale(Locale.getDefault()),
            )
        }.getOrDefault(memorial.deathDate)
    }

    LaunchedEffect(mode, endsAt) {
        if (mode == 1 && endsAt > 0L) {
            while (true) {
                seconds = ((endsAt - SystemClock.elapsedRealtime() + 999L) / 1_000L)
                    .toInt().coerceIn(0, 60)
                if (seconds == 0) break
                delay(1_000L)
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
        ),
    ) {
        Surface(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .widthIn(max = 420.dp)
                .fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = StandardChrome.panel,
            border = BorderStroke(1.dp, StandardChrome.muted.copy(alpha = 0.20f)),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Surface(
                        modifier = Modifier.size(46.dp),
                        shape = RoundedCornerShape(16.dp),
                        color = StandardChrome.panel,
                        border = BorderStroke(1.dp, StandardChrome.muted.copy(alpha = 0.16f)),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            CapsuleCandleIcon(size = 22.dp)
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.artist_candle_title),
                            color = StandardChrome.text,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = name,
                            color = StandardChrome.muted,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                        )
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
                        Icon(
                            painter = painterResource(R.drawable.close),
                            contentDescription = stringResource(R.string.artist_candle_close),
                            tint = StandardChrome.muted,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                HorizontalDivider(color = StandardChrome.muted.copy(alpha = 0.12f))
                Text(
                    text = stringResource(R.string.artist_candle_source, displayedDate),
                    style = MaterialTheme.typography.bodySmall,
                    color = StandardChrome.muted,
                )

                // Size each mode for its actual contents instead of leaving a
                // giant empty well beneath the two actions. No crossfade or
                // animateContentSize: menu changes remain deterministic.
                val contentHeight = when (mode) {
                    0 -> if (LocalDensity.current.fontScale > 1.3f) 176.dp else 132.dp
                    1 -> 164.dp
                    else -> if (LocalDensity.current.fontScale > 1.3f) 240.dp else 190.dp
                }
                Box(
                    modifier = Modifier.fillMaxWidth().height(contentHeight),
                    contentAlignment = Alignment.Center,
                ) {
                    when (mode) {
                        0 -> {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                MemorialAction(
                                    icon = R.drawable.capsule_candle,
                                    text = stringResource(R.string.artist_candle_minute),
                                ) {
                                    onMinuteBegin()
                                    seconds = 60
                                    endsAt = SystemClock.elapsedRealtime() + 60_000L
                                    mode = 1
                                }
                                MemorialAction(
                                    icon = R.drawable.favorite_border,
                                    text = stringResource(R.string.artist_candle_thanks),
                                ) {
                                    mode = 2
                                }
                            }
                        }
                        1 -> {
                            // The warm circular sweep is drawn only on second
                            // ticks. No ambient shimmer, CPU polling or glow.
                            Box(
                                modifier = Modifier.fillMaxWidth().height(160.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                val remaining = (seconds / 60f).coerceIn(0f, 1f)
                                Canvas(Modifier.size(150.dp)) {
                                    val stroke = 3.5.dp.toPx()
                                    drawCircle(
                                        color = CapsuleFlame.copy(alpha = 0.15f),
                                        style = Stroke(width = stroke),
                                    )
                                    if (remaining > 0f) {
                                        drawArc(
                                            brush = Brush.sweepGradient(
                                                0f to CapsuleFlameCore,
                                                0.35f to CapsuleFlame,
                                                0.75f to androidx.compose.ui.graphics.Color(0xFFE78843),
                                                1f to CapsuleFlameCore,
                                            ),
                                            startAngle = -90f,
                                            sweepAngle = remaining * 360f,
                                            useCenter = false,
                                            style = Stroke(width = stroke, cap = StrokeCap.Round),
                                        )
                                    }
                                }
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CapsuleCandleIcon(size = 20.dp)
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
                        }
                        2 -> {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                // The entire card is an editable canvas. Nothing
                                // is sent or shared. The draft survives a revisit.
                                Surface(
                                    modifier = Modifier.fillMaxWidth().weight(1f),
                                    shape = RoundedCornerShape(16.dp),
                                    color = StandardChrome.selected,
                                ) {
                                    BasicTextField(
                                        value = note,
                                        onValueChange = { note = it.take(800) },
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(16.dp)
                                            .testTag("memorial-wish-canvas"),
                                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                                            color = StandardChrome.text,
                                        ),
                                        cursorBrush = SolidColor(StandardChrome.text),
                                        keyboardOptions = KeyboardOptions(
                                            capitalization = KeyboardCapitalization.Sentences,
                                        ),
                                        decorationBox = { innerTextField ->
                                            Box(Modifier.fillMaxSize()) {
                                                if (note.isEmpty()) {
                                                    Text(
                                                        text = stringResource(R.string.artist_candle_note_hint),
                                                        color = StandardChrome.muted,
                                                        style = MaterialTheme.typography.bodyMedium,
                                                    )
                                                }
                                                innerTextField()
                                            }
                                        },
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    TextButton(onClick = { mode = 0 }) {
                                        Text(
                                            text = stringResource(R.string.artist_candle_back),
                                            color = StandardChrome.muted,
                                        )
                                    }
                                    Button(
                                        onClick = {
                                            val clean = note.trim()
                                            if (clean.isNotEmpty()) {
                                                note = clean
                                                sentNote = clean
                                                prefs.edit()
                                                    .putString(noteKey, clean)
                                                    .putString("submitted:$noteKey", clean)
                                                    .apply()
                                            }
                                        },
                                        enabled = note.isNotBlank() && !isSubmitted,
                                        shape = RoundedCornerShape(14.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = StandardChrome.text,
                                            contentColor = StandardChrome.background,
                                            disabledContainerColor = StandardChrome.selected,
                                            disabledContentColor = StandardChrome.muted,
                                        ),
                                    ) {
                                        Text(stringResource(
                                            if (isSubmitted) R.string.artist_candle_sent
                                            else R.string.artist_candle_send,
                                        ))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MemorialAction(
    @DrawableRes icon: Int,
    text: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        shape = RoundedCornerShape(16.dp),
        color = StandardChrome.selected,
        // No strokes on action buttons: match Capsule's quiet filled surfaces.
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (icon == R.drawable.capsule_candle) {
                CapsuleCandleIcon(size = 19.dp)
            } else {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = null,
                    tint = StandardChrome.text,
                    modifier = Modifier.size(19.dp),
                )
            }
            Text(
                text = text,
                color = StandardChrome.text,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
