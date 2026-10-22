package com.nikhil.yt.ui.screens.discovery

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.nikhil.yt.LocalPlayerAwareWindowInsets
import com.nikhil.yt.LocalPlayerConnection
import com.nikhil.yt.R
import com.nikhil.yt.constants.ExperimentalSourcesEnabledKey
import com.nikhil.yt.discovery.ExperimentalCatalog
import com.nikhil.yt.discovery.ExperimentalTrackQueue
import com.nikhil.yt.utils.rememberPreference
import kotlinx.coroutines.CancellationException

/**
 * An artist's own external catalog. For Archive a creator credit is a metadata
 * search, not an Archive user account. Audius uses the actual /users/{id}/tracks.
 */
@Composable
fun ExperimentalArtistScreen(
    navController: NavController,
    sourceName: String,
    artist: String,
    artistId: String?,
    originalPage: String?,
) {
    val source = remember(sourceName) {
        ExperimentalCatalog.Source.entries.firstOrNull { it.name == sourceName }
    }
    val context = LocalContext.current
    val playerConnection = LocalPlayerConnection.current
    val (enabled, _) = rememberPreference(ExperimentalSourcesEnabledKey, false)
    var tracks by remember(sourceName, artist, artistId) {
        mutableStateOf<List<ExperimentalCatalog.Track>>(emptyList())
    }
    var loading by remember(sourceName, artist, artistId) { mutableStateOf(true) }
    var error by remember(sourceName, artist, artistId) { mutableStateOf(false) }
    var retry by remember(sourceName, artist, artistId) { mutableIntStateOf(0) }

    LaunchedEffect(source, artist, artistId, retry, enabled) {
        if (source == null || !enabled) {
            tracks = emptyList()
            loading = false
            return@LaunchedEffect
        }
        loading = true
        error = false
        try {
            tracks = ExperimentalCatalog.artistTracks(source, artist, artistId)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            tracks = emptyList()
            error = true
        } finally {
            loading = false
        }
    }

    val site = tracks.firstOrNull()?.artistPage ?: originalPage
    val openPage: () -> Unit = {
        site?.takeIf { it.startsWith("https://") }?.let {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) }
        }
    }

    LazyColumn(
        contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues(),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "artist-heading") {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(painterResource(R.drawable.arrow_back), null)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            artist,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            (source?.title ?: "Capsule Discovery") +
                                if (source == ExperimentalCatalog.Source.ARCHIVE) " · archive creator" else "",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
                if (!site.isNullOrBlank()) TextButton(onClick = openPage) {
                    Text(if (source == ExperimentalCatalog.Source.ARCHIVE)
                        stringResource(R.string.capsule_discovery_author_archive) else stringResource(R.string.view_artist))
                }
                Text(
                    "${tracks.size} · ${source?.title.orEmpty()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (!enabled) item { Text(stringResource(R.string.capsule_discovery_unavailable),
            modifier = Modifier.padding(20.dp)) }
        else if (loading) item {
            Box(Modifier.fillMaxWidth().padding(30.dp), Alignment.Center) { CircularProgressIndicator() }
        } else if (error) item {
            Column(Modifier.padding(20.dp)) {
                Text(stringResource(R.string.capsule_discovery_unavailable))
                TextButton(onClick = { retry++ }) { Text(stringResource(R.string.capsule_discovery_retry)) }
            }
        } else if (tracks.isEmpty()) item {
            Text(stringResource(R.string.no_results_found), modifier = Modifier.padding(20.dp))
        } else {
            items(tracks, key = { it.mediaId }) { track ->
                Row(
                    Modifier.fillMaxWidth().clickable {
                        if (track.playable) {
                            ExperimentalTrackQueue.create(artist, tracks, track)
                                ?.let { queue -> playerConnection?.playQueue(queue) }
                        } else {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(track.sourcePage)))
                            }
                        }
                    }.padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AsyncImage(
                        model = track.artwork,
                        contentDescription = null,
                        modifier = Modifier.size(54.dp).clip(RoundedCornerShape(10.dp)),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyLarge)
                        Text(track.artist + " · " + track.source.title, maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(track.sourcePage)))
                        }
                    }) { Text("↗") }
                }
            }
        }
    }
}
