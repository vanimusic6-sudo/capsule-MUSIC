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
        LyricsProviderOrder.BETTER_LYRICS_PORTATO -> "BL Portato · QQ"
        LyricsProviderOrder.BETTER_LYRICS -> "BL · Better Lyrics"
        LyricsProviderOrder.LRCLIB -> "LRCLIB"
        LyricsProviderOrder.YOUTUBE_SYNCED -> "YouTube · с синхронизацией"
        LyricsProviderOrder.KUGOU -> "KuGou · LRC"
        LyricsProviderOrder.BINIMUM -> "BiniLyrics · TTML"
        LyricsProviderOrder.YOUTUBE -> "YouTube · без синхронизации"
        else -> providerId
    }

@Composable
private fun lyricsProviderDescription(providerId: String): String =
    when (providerId) {
        LyricsProviderOrder.BETTER_LYRICS_PORTATO -> stringResource(R.string.lyrics_provider_portato_description)
        LyricsProviderOrder.BETTER_LYRICS -> "TTML · послоговая разметка"
        LyricsProviderOrder.LRCLIB -> stringResource(R.string.lyrics_provider_lrclib_description)
        LyricsProviderOrder.YOUTUBE_SYNCED -> "Настоящие временные метки YouTube Music"
        LyricsProviderOrder.KUGOU -> "Независимая база KuGou, синхронизация по строкам"
        LyricsProviderOrder.BINIMUM -> "Дополнительный каталог TTML, проверка длительности"
        LyricsProviderOrder.YOUTUBE -> "Обычный текст YouTube Music и субтитров"
        else -> providerId
    }
