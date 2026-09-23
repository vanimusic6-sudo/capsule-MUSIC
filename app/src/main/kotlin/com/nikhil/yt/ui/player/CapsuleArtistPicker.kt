/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */

package com.nikhil.yt.ui.player

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nikhil.yt.R
import com.nikhil.yt.models.MediaMetadata
import com.nikhil.yt.ui.component.ArtistSelectionItem

/**
 * The artists on a track that actually have a page to open.
 *
 * A credit without an id is a name and nothing else, and a track can list the same artist twice
 * — once as the performer, once inside a feature — so both are dropped before anything counts
 * how many there are. That count is what decides between opening a page and asking.
 */
@Composable
internal fun rememberNavigableArtists(artists: List<MediaMetadata.Artist>): List<MediaMetadata.Artist> =
    remember(artists) {
        artists.filter { !it.id.isNullOrBlank() }.distinctBy { it.id }
    }

/**
 * Asks which artist was meant, when a track credits more than one.
 *
 * Lifted out of the Cosmo player rather than copied into the immersive one. The same dialog had
 * already been duplicated once in this package — two hundred identical lines of slider — and the
 * cost of that is two behaviours that drift until they are different features with one name.
 */
@Composable
internal fun CapsuleArtistPickerDialog(
    artists: List<MediaMetadata.Artist>,
    onDismiss: () -> Unit,
    onArtistSelected: (MediaMetadata.Artist) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.capsule_choose_artist)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(artists, key = { it.id.orEmpty() }) { artist ->
                    ArtistSelectionItem(
                        name = artist.name,
                        artistId = artist.id,
                        thumbnailUrl = artist.thumbnailUrl,
                    ) {
                        onDismiss()
                        onArtistSelected(artist)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel_button)) }
        },
    )
}
