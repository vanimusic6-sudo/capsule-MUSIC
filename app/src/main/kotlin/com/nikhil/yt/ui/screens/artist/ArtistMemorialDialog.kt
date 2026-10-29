package com.nikhil.yt.ui.screens.artist

import android.content.Intent
import android.os.SystemClock
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.nikhil.yt.R
import com.nikhil.yt.ui.component.StandardChrome
import kotlinx.coroutines.delay
import java.util.Locale
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * No remote memorial service and no public posting on the user's behalf.
 * The user can observe sixty seconds of silence or share their own thanks.
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
                    .withLocale(Locale.getDefault())
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

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = StandardChrome.panel,
            border = BorderStroke(1.dp, StandardChrome.muted.copy(alpha = 0.22f)),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 22.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.capsule_candle),
                        contentDescription = null,
                        tint = StandardChrome.text,
                        modifier = Modifier.size(24.dp),
                    )
                    Text(
                        text = stringResource(R.string.artist_candle_title),
                        color = StandardChrome.text,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    text = stringResource(R.string.artist_candle_for, name),
                    color = StandardChrome.text,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.artist_candle_source, displayedDate),
                    color = StandardChrome.muted,
                    style = MaterialTheme.typography.bodySmall,
                )

                Spacer(Modifier.height(2.dp))
                when (mode) {
                    0 -> {
                        MemorialActionButton(stringResource(R.string.artist_candle_minute)) {
                            onMinuteBegin()
                            seconds = 60
                            mode = 1
                        }
                        MemorialActionButton(stringResource(R.string.artist_candle_thanks)) {
                            mode = 2
                        }
                    }
                    1 -> {
                        Text(
                            text = String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60),
                            modifier = Modifier.fillMaxWidth(),
                            color = StandardChrome.text,
                            style = MaterialTheme.typography.displayMedium,
                            fontWeight = FontWeight.Light,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            text = stringResource(
                                if (seconds == 0) R.string.artist_candle_timer_done
                                else R.string.artist_candle_paused
                            ),
                            color = StandardChrome.muted,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    2 -> {
                        Text(
                            text = gratitude,
                            color = StandardChrome.text,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = stringResource(R.string.artist_candle_local),
                            color = StandardChrome.muted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        MemorialActionButton(stringResource(R.string.artist_candle_share)) {
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, gratitude)
                            }
                            context.startActivity(Intent.createChooser(intent, null))
                        }
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text(
                        text = stringResource(R.string.artist_candle_close),
                        color = StandardChrome.text,
                    )
                }
            }
        }
    }
}

@Composable
private fun MemorialActionButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, StandardChrome.muted.copy(alpha = 0.20f)),
        colors = ButtonDefaults.buttonColors(
            containerColor = StandardChrome.selected,
            contentColor = StandardChrome.text,
        ),
    ) {
        Text(text = label, style = MaterialTheme.typography.labelLarge)
    }
}
