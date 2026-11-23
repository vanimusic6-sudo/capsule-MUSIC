/*
 * Capsule MUSIC — compact lyrics lookup and source selection.
 * GPL-3.0. Reuses the application's existing animated modal and colour theme.
 */
package com.nikhil.yt.ui.menu

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nikhil.yt.R
import com.nikhil.yt.constants.LyricsProviderOrder
import com.nikhil.yt.ui.component.DefaultDialog

internal fun lyricsSearchSourceTitle(id: String?): String = when (id) {
    null -> "Все источники"
    LyricsProviderOrder.BETTER_LYRICS_PORTATO -> "BL Portato · QQ"
    LyricsProviderOrder.BETTER_LYRICS -> "Better Lyrics"
    LyricsProviderOrder.LRCLIB -> "LRCLIB"
    LyricsProviderOrder.YOUTUBE_SYNCED -> "YouTube Sync"
    LyricsProviderOrder.KUGOU -> "KuGou"
    LyricsProviderOrder.BINIMUM -> "BiniLyrics"
    LyricsProviderOrder.YOUTUBE -> "YouTube Plain"
    else -> "Все источники"
}

internal fun lyricsSearchSourceDescription(id: String?): String = when (id) {
    null -> "Искать параллельно во всех включённых каталогах"
    LyricsProviderOrder.BETTER_LYRICS_PORTATO -> "QQ · пословная разметка"
    LyricsProviderOrder.BETTER_LYRICS -> "TTML · слова и слоги"
    LyricsProviderOrder.LRCLIB -> "Открытая база · LRC"
    LyricsProviderOrder.YOUTUBE_SYNCED -> "YouTube Music · временные метки"
    LyricsProviderOrder.KUGOU -> "Отдельная база · построчный LRC"
    LyricsProviderOrder.BINIMUM -> "Дополнительные TTML-тексты"
    LyricsProviderOrder.YOUTUBE -> "Обычный текст и субтитры"
    else -> ""
}

@Composable
internal fun CapsuleLyricsSearchDialog(
    titleField: TextFieldValue,
    onTitleChange: (TextFieldValue) -> Unit,
    artistField: TextFieldValue,
    onArtistChange: (TextFieldValue) -> Unit,
    sourceId: String?,
    onSourceChange: (String?) -> Unit,
    syncedOnly: Boolean,
    onSyncedOnlyChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onSearch: () -> Unit,
    onWebSearch: () -> Unit,
) {
    var showSources by remember { mutableStateOf(false) }
    val surfaceShape = RoundedCornerShape(20.dp)
    val fieldColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.36f)
    if (showSources) {
        CapsuleLyricsSourcePicker(
            selected = sourceId,
            onSelected = {
                onSourceChange(it)
                showSources = false
            },
            onDismiss = { showSources = false },
        )
    }
    DefaultDialog(
        onDismiss = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(R.drawable.search),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(25.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text("Поиск текста песни", style = MaterialTheme.typography.titleLarge)
            }
        },
        contentScrollable = true,
        horizontalAlignment = Alignment.Start,
        buttons = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
            Spacer(Modifier.width(8.dp))
            Button(
                enabled = titleField.text.isNotBlank() && artistField.text.isNotBlank(),
                shape = RoundedCornerShape(18.dp),
                onClick = onSearch,
            ) {
                Icon(painterResource(R.drawable.search), null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Найти")
            }
        },
    ) {
        Text(
            "Уточни название и исполнителя. Выбор источника повлияет на поиск, а не на воспроизведение.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(18.dp))
        OutlinedTextField(
            value = titleField,
            onValueChange = onTitleChange,
            singleLine = true,
            label = { Text("Название песни") },
            modifier = Modifier.fillMaxWidth(),
            shape = surfaceShape,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = fieldColor,
                unfocusedContainerColor = fieldColor,
            ),
            trailingIcon = {
                if (titleField.text.isNotEmpty()) {
                    IconButton(onClick = { onTitleChange(TextFieldValue("")) }) {
                        Icon(painterResource(R.drawable.close), "Очистить название")
                    }
                }
            },
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = artistField,
            onValueChange = onArtistChange,
            singleLine = true,
            label = { Text("Исполнитель") },
            modifier = Modifier.fillMaxWidth(),
            shape = surfaceShape,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = fieldColor,
                unfocusedContainerColor = fieldColor,
            ),
            trailingIcon = {
                if (artistField.text.isNotEmpty()) {
                    IconButton(onClick = { onArtistChange(TextFieldValue("")) }) {
                        Icon(painterResource(R.drawable.close), "Очистить исполнителя")
                    }
                }
            },
        )
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.66f), surfaceShape)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.54f), surfaceShape)
                .clickable { showSources = true }
                .padding(horizontal = 15.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painterResource(R.drawable.capsule_soft_queue_music),
                null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(lyricsSearchSourceTitle(sourceId), fontWeight = FontWeight.SemiBold)
                Text(
                    lyricsSearchSourceDescription(sourceId),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
            Icon(painterResource(R.drawable.expand_more), "Выбрать источник", Modifier.size(22.dp))
        }
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.44f), surfaceShape)
                .clickable { onSyncedOnlyChange(!syncedOnly) }
                .padding(horizontal = 15.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Только с синхронизацией", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Скрыть результаты без временных меток",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = syncedOnly, onCheckedChange = onSyncedOnlyChange)
        }
        TextButton(onClick = onWebSearch) {
            Text("Найти в браузере", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun CapsuleLyricsSourcePicker(
    selected: String?,
    onSelected: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember(selected) { mutableStateOf(selected) }
    DefaultDialog(
        onDismiss = onDismiss,
        title = { Text("Выбрать источник", style = MaterialTheme.typography.titleLarge) },
        horizontalAlignment = Alignment.Start,
        contentScrollable = true,
        buttons = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { onSelected(draft) }, shape = RoundedCornerShape(18.dp)) {
                Text("Применить")
            }
        },
    ) {
        Text(
            "Один источник или поиск по всем доступным провайдерам.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))
        (listOf<String?>(null) + LyricsProviderOrder.supportedProviders).forEach { id ->
            val chosen = id == draft
            val shape = RoundedCornerShape(18.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 7.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceContainer.copy(alpha = if (chosen) 0.90f else 0.47f),
                        shape,
                    )
                    .border(
                        1.dp,
                        if (chosen) MaterialTheme.colorScheme.primary.copy(alpha = 0.40f)
                        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f),
                        shape,
                    )
                    .clickable { draft = id }
                    .padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        lyricsSearchSourceTitle(id),
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        lyricsSearchSourceDescription(id),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
                RadioButton(selected = chosen, onClick = { draft = id })
            }
        }
    }
}
