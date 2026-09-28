package com.nikhil.yt.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nikhil.yt.R
import com.nikhil.yt.lyrics.PaxsenixLyricsProvider
import com.nikhil.yt.lyrics.PaxsenixProviderStats
import com.nikhil.yt.lyrics.PaxsenixStats
import com.nikhil.yt.ui.component.DefaultDialog

private sealed interface PaxsenixStatsUiState {
    data object Loading : PaxsenixStatsUiState
    data class Success(val stats: PaxsenixStats) : PaxsenixStatsUiState
    data object Error : PaxsenixStatsUiState
}

private enum class PaxsenixHealth {
    OPERATIONAL,
    DEGRADED,
    DOWN,
}

@Composable
internal fun PaxsenixStatsDialog(onDismiss: () -> Unit) {
    var refreshKey by remember { mutableIntStateOf(0) }
    val state by
        produceState<PaxsenixStatsUiState>(
            initialValue = PaxsenixStatsUiState.Loading,
            key1 = refreshKey,
        ) {
            value = PaxsenixStatsUiState.Loading
            value =
                PaxsenixLyricsProvider
                    .getStats()
                    .fold(
                        onSuccess = { PaxsenixStatsUiState.Success(it) },
                        onFailure = { PaxsenixStatsUiState.Error },
                    )
        }

    DefaultDialog(
        onDismiss = onDismiss,
        title = { Text(stringResource(R.string.paxsenix_status_title)) },
        contentScrollable = false,
        buttons = {
            TextButton(onClick = { refreshKey++ }) {
                Text(stringResource(R.string.paxsenix_refresh))
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.ok))
            }
        },
    ) {
        when (val current = state) {
            PaxsenixStatsUiState.Loading -> {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            PaxsenixStatsUiState.Error -> {
                Text(
                    text = stringResource(R.string.paxsenix_stats_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }

            is PaxsenixStatsUiState.Success -> {
                PaxsenixStatsContent(current.stats)
            }
        }
    }
}

@Composable
private fun PaxsenixStatsContent(stats: PaxsenixStats) {
    val overallRate = parseRate(stats.overall_success_rate)
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PaxsenixHealthBar(
            rate = overallRate,
            label =
                when (healthFor(overallRate)) {
                    PaxsenixHealth.OPERATIONAL -> stringResource(R.string.paxsenix_status_operational)
                    PaxsenixHealth.DEGRADED -> stringResource(R.string.paxsenix_status_degraded)
                    PaxsenixHealth.DOWN -> stringResource(R.string.paxsenix_status_down)
                },
        )

        StatRow(stringResource(R.string.paxsenix_uptime), formatUptime(stats.uptime_seconds))
        StatRow(stringResource(R.string.paxsenix_total_requests), stats.total_requests.toString())
        StatRow(stringResource(R.string.paxsenix_success_rate), stats.overall_success_rate)

        if (stats.providers.isNotEmpty()) {
            HorizontalDivider()
            Text(
                text = stringResource(R.string.paxsenix_providers),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                stats.providers.forEach { (name, providerStats) ->
                    PaxsenixProviderRow(name, providerStats)
                }
            }
        }

        if (stats.request_log.isNotEmpty()) {
            HorizontalDivider()
            Text(
                text = stringResource(R.string.paxsenix_recent_requests),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                stats.request_log.take(5).forEach { entry ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = entry.provider.ifBlank { entry.endpoint },
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = entry.endpoint,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            text = if (entry.success) "OK" else "ERR",
                            style = MaterialTheme.typography.labelMedium,
                            color =
                                if (entry.success) {
                                    Color(0xFF4CAF50)
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PaxsenixHealthBar(
    rate: Float,
    label: String,
) {
    val color = healthColor(healthFor(rate))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(color),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
            )
        }
        Text(
            text = "${rate.toInt()}%",
            color = color,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.titleSmall,
        )
    }
}

@Composable
private fun PaxsenixProviderRow(
    name: String,
    stats: PaxsenixProviderStats,
) {
    val rate = parseRate(stats.success_rate)
    val color = healthColor(healthFor(rate))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(color),
            )
            Text(
                text = name,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = stats.success_rate,
            style = MaterialTheme.typography.labelMedium,
            color = color,
        )
    }
}

@Composable
private fun StatRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
        )
    }
}

private fun parseRate(value: String): Float =
    value.trim().removeSuffix("%").toFloatOrNull()?.coerceIn(0f, 100f) ?: 0f

private fun healthFor(rate: Float): PaxsenixHealth =
    when {
        rate >= 90f -> PaxsenixHealth.OPERATIONAL
        rate >= 70f -> PaxsenixHealth.DEGRADED
        else -> PaxsenixHealth.DOWN
    }

@Composable
private fun healthColor(health: PaxsenixHealth): Color =
    when (health) {
        PaxsenixHealth.OPERATIONAL -> Color(0xFF4CAF50)
        PaxsenixHealth.DEGRADED -> Color(0xFFFF9800)
        PaxsenixHealth.DOWN -> MaterialTheme.colorScheme.error
    }

private fun formatUptime(seconds: Double): String {
    val total = seconds.toLong().coerceAtLeast(0L)
    val days = total / 86_400L
    val hours = (total % 86_400L) / 3_600L
    val minutes = (total % 3_600L) / 60L
    return when {
        days > 0L -> "${days}d ${hours}h ${minutes}m"
        hours > 0L -> "${hours}h ${minutes}m"
        else -> "${minutes}m"
    }
}
