package com.nikhil.yt.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.nikhil.yt.R
import com.nikhil.yt.discovery.ExperimentalArtistRoute
import com.nikhil.yt.discovery.ExperimentalCatalog
import com.nikhil.yt.discovery.ExperimentalTrackQueue
import com.nikhil.yt.discovery.HomeFeedSource
import com.nikhil.yt.playback.PlayerConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import timber.log.Timber

/** Compose state for independent source-driven Home sections (not a nested feed). */
internal class CatalogHomeState {
    var lanes by mutableStateOf<List<ExperimentalCatalog.HomeLane>>(emptyList())
    var failed by mutableStateOf<Set<HomeFeedSource>>(emptySet())
    var loading by mutableStateOf(false)
    var loadedKey by mutableStateOf("")
    var finished by mutableStateOf(false)
    var revision by mutableIntStateOf(0)
    fun refresh() { revision++ }
}

@Composable
internal fun rememberCatalogHomeState(
    sources: Set<HomeFeedSource>,
    tasteArtists: List<String>,
): CatalogHomeState {
    val state = remember { CatalogHomeState() }
    val key = HomeFeedSource.encode(sources)
    val tasteKey = tasteArtists.take(2).joinToString("|")
    LaunchedEffect(key, tasteKey, state.revision) {
        // Refresh without flashing away functioning cards. Switching a provider
        // invalidates its old lanes; retry does not.
        if (state.loadedKey != key) {
            state.lanes = emptyList()
            state.loadedKey = key
        }
        state.failed = emptySet()
        state.finished = false
        state.loading = sources.isNotEmpty()
        if (sources.isEmpty()) return@LaunchedEffect
        try {
            supervisorScope {
                // All network and parsing stays off the UI thread. Each selected
                // provider has its own error boundary so partial Home still works.
                HomeFeedSource.entries.filter { it in sources && it != HomeFeedSource.YOUTUBE }
                    .forEach { source ->
                        launch(Dispatchers.IO) {
                            val lanes = try {
                                ExperimentalCatalog.homeLanes(source, tasteArtists) { lane ->
                                    withContext(Dispatchers.Main) {
                                        state.lanes = (state.lanes.filterNot {
                                            it.source == lane.source && it.kind == lane.kind
                                        } + lane).sortedWith(
                                            compareBy<ExperimentalCatalog.HomeLane> {
                                                HomeFeedSource.entries.indexOf(it.source)
                                            }.thenBy { it.kind.ordinal },
                                        )
                                    }
                                }
                            } catch (cancel: CancellationException) {
                                throw cancel
                            } catch (error: Exception) {
                                Timber.tag("CapsuleHome").w(error, "Home source failed: %s", source.id)
                                emptyList()
                            }
                            withContext(Dispatchers.Main) {
                                if (lanes.isEmpty()) state.failed = state.failed + source
                            }
                        }
                    }
            }
        } finally {
            state.loading = false
            state.finished = true
        }
    }
    return state
}

@Composable
internal fun catalogLaneTitle(kind: ExperimentalCatalog.HomeLaneKind): String =
    stringResource(when (kind) {
        ExperimentalCatalog.HomeLaneKind.TRENDING -> R.string.capsule_home_catalog_trending
        ExperimentalCatalog.HomeLaneKind.NEW -> R.string.capsule_home_catalog_new
        ExperimentalCatalog.HomeLaneKind.DISCOVER -> R.string.capsule_home_catalog_discover
        ExperimentalCatalog.HomeLaneKind.FOR_YOU -> R.string.capsule_home_catalog_for_you
    })

@Composable
internal fun CatalogHomeError(onRetry: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text(
            text = stringResource(R.string.capsule_discovery_unavailable),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onRetry) { Text(stringResource(R.string.capsule_discovery_retry)) }
    }
}

/** A genuinely separate Home row. Media queues only contain direct HTTPS tracks;
 * licensed index-only records open the original publisher's release instead. */
@Composable
internal fun CatalogHomeRow(
    title: String,
    source: HomeFeedSource?,
    tracks: List<ExperimentalCatalog.Track>,
    userScrollEnabled: Boolean,
    playerConnection: PlayerConnection,
    navController: NavController,
) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp, top = 4.dp),
        ) {
            if (source != null) Box(
                modifier = Modifier.size(10.dp).clip(CircleShape).background(source.accent),
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 20.dp),
            userScrollEnabled = userScrollEnabled,
        ) {
            items(tracks, key = { it.mediaId }) { track ->
                val cardSource = HomeFeedSource.entries.firstOrNull {
                    it.id == track.catalogProvider
                } ?: if (track.source == ExperimentalCatalog.Source.AUDIUS)
                    HomeFeedSource.AUDIUS else source ?: HomeFeedSource.JAMENDO
                Card(
                    modifier = Modifier.width(156.dp).clickable {
                        if (track.playable) {
                            ExperimentalTrackQueue.create(title, tracks, track)
                                ?.let(playerConnection::playQueue)
                        } else {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(track.sourcePage)),
                                )
                            }
                        }
                    },
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Column {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(156.dp)
                                .background(cardSource.accent.copy(alpha = 0.22f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            AsyncImage(
                                model = track.artwork,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                            if (track.artwork.isNullOrBlank()) Icon(
                                painter = painterResource(cardSource.icon),
                                contentDescription = null,
                                tint = cardSource.accent,
                                modifier = Modifier.size(42.dp),
                            )
                        }
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = track.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = track.artist,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.clickable {
                                    navController.navigate(ExperimentalArtistRoute.forTrack(track))
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                text = cardSource.label + if (track.playable) "" else " ↗",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelSmall,
                                color = cardSource.accent,
                            )
                        }
                    }
                }
            }
        }
    }
}
