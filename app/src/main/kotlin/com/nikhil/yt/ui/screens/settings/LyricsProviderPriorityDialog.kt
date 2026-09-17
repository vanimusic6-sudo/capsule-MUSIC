package com.nikhil.yt.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.nikhil.yt.R
import com.nikhil.yt.constants.LyricsProviderOrder

/**
 * The lyrics provider ranking.
 *
 * Which source is tried second matters as much as which is first: they disagree about timing, about
 * which songs they have at all, and about whether what comes back is synced.
 */
@Composable
internal fun LyricsProviderPriorityDialog(
    currentOrder: List<String>,
    onDismiss: () -> Unit,
    onOrderChange: (List<String>) -> Unit,
) {
    PriorityOrderDialog(
        title = stringResource(R.string.lyrics_provider_priority_title),
        description = stringResource(R.string.lyrics_provider_priority_description),
        note = null,
        currentOrder = currentOrder,
        onReset = { LyricsProviderOrder.supportedProviders },
        onDismiss = onDismiss,
        onOrderChange = onOrderChange,
        label = { lyricsProviderTitle(it) },
        sublabel = { lyricsProviderDescription(it) },
        showRawId = false,
    )
}

@Composable
private fun lyricsProviderTitle(providerId: String): String =
    when (providerId) {
        LyricsProviderOrder.LRCLIB -> "LrcLib"
        LyricsProviderOrder.BETTER_LYRICS -> "BetterLyrics"
        LyricsProviderOrder.YOUTUBE_SUBTITLE -> stringResource(R.string.lyrics_provider_youtube_subtitle)
        LyricsProviderOrder.YOUTUBE -> stringResource(R.string.lyrics_provider_youtube)
        else -> providerId
    }

@Composable
private fun lyricsProviderDescription(providerId: String): String =
    when (providerId) {
        LyricsProviderOrder.LRCLIB -> stringResource(R.string.lyrics_provider_lrclib_description)
        LyricsProviderOrder.BETTER_LYRICS -> stringResource(R.string.lyrics_provider_betterlyrics_description)
        LyricsProviderOrder.YOUTUBE_SUBTITLE -> stringResource(R.string.lyrics_provider_youtube_subtitle_description)
        LyricsProviderOrder.YOUTUBE -> stringResource(R.string.lyrics_provider_youtube_description)
        else -> providerId
    }
