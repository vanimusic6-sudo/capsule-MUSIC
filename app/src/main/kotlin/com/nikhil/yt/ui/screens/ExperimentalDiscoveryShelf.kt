package com.nikhil.yt.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.nikhil.yt.LocalPlayerConnection
import com.nikhil.yt.R
import com.nikhil.yt.discovery.ExperimentalCatalog
import com.nikhil.yt.discovery.ExperimentalRanking
import com.nikhil.yt.discovery.ExperimentalArtistRoute
import com.nikhil.yt.discovery.ExperimentalTrackQueue
import kotlinx.coroutines.CancellationException

/**
 * Dormant unless Content -> Experimental sources is enabled.
 * Remote discovery never runs while this shelf is absent from composition.
 */
@Composable
internal fun ExperimentalDiscoveryShelf(tasteArtists: List<String>, navController: NavController) {
    val context = LocalContext.current
    val playerConnection = LocalPlayerConnection.current ?: return
    var candidates by remember { mutableStateOf<List<ExperimentalCatalog.Track>>(emptyList()) }
    val tasteKey = remember(tasteArtists) { tasteArtists.take(2).joinToString("|") }

    LaunchedEffect(tasteKey) {
        candidates = emptyList()
        // Show Audius promptly; Openverse is an independent async lane.
        // Its latency cannot hide or block the Audius shelf.
        supervisorScope {
            val discovered = mutableListOf<ExperimentalCatalog.Track>()
            for (source in ExperimentalCatalog.Source.entries) {
                launch {
                    val lane = try {
                        ExperimentalCatalog.discoveryFrom(source, tasteArtists)
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (_: Exception) {
                        emptyList()
                    }
                    if (lane.isNotEmpty()) {
                        discovered.addAll(lane)
                        candidates = ExperimentalRanking.select(
                            discovered.toList(),
                            java.time.LocalDate.now(java.time.ZoneOffset.UTC).toEpochDay(),
                        )
                    }
                }
            }
        }
    }

    if (candidates.isEmpty()) return
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.capsule_discovery_home_title),
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 12.dp),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 20.dp),
        ) {
            items(candidates, key = { it.mediaId }) { track ->
                Card(
                    modifier = Modifier.width(152.dp).clickable {
                        if (track.playable) {
                            ExperimentalTrackQueue.create(
                                title = "Capsule Discovery",
                                tracks = candidates,
                                start = track,
                            )?.let(playerConnection::playQueue)
                        } else {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(track.sourcePage)),
                                )
                            }
                        }
                    },
                ) {
                    Column {
                        AsyncImage(
                            model = track.artwork,
                            contentDescription = null,
                            modifier = Modifier.fillMaxWidth().height(152.dp)
                                .clip(RoundedCornerShape(12.dp)),
                        )
                        Column(Modifier.padding(10.dp)) {
                            Text(
                                track.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                track.artist,
                                modifier = Modifier.clickable {
                                    navController.navigate(ExperimentalArtistRoute.forTrack(track))
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                track.source.title +
                                    (track.catalogProvider?.let { " · $it" } ?: "") +
                                    if (track.playable) "" else " ↗",
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The forgotten shelf runs independently of the Audius/Jamendo shelf and
 * stays dormant while the experimental Content preference is disabled.
 * It only uses Openverse's Wikimedia Commons and Freesound audio index.
 */
@Composable
internal fun ExperimentalForgottenShelf(navController: NavController) {
    val context = LocalContext.current
    val playerConnection = LocalPlayerConnection.current ?: return
    var tracks by remember { mutableStateOf<List<ExperimentalCatalog.Track>>(emptyList()) }

    LaunchedEffect(Unit) {
        tracks = try {
            ExperimentalCatalog.forgotten()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            emptyList()
        }
    }
    if (tracks.isEmpty()) return

    Column(Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.capsule_discovery_forgotten_title),
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(R.string.capsule_discovery_forgotten_description),
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 20.dp),
        ) {
            items(tracks, key = { "forgotten-${it.mediaId}" }) { track ->
                Card(
                    modifier = Modifier.width(152.dp).clickable {
                        if (track.playable) {
                            ExperimentalTrackQueue.create(
                                title = context.getString(R.string.capsule_discovery_forgotten_title),
                                tracks = tracks,
                                start = track,
                            )?.let(playerConnection::playQueue)
                        } else {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(track.sourcePage)),
                                )
                            }
                        }
                    },
                ) {
                    Column {
                        AsyncImage(
                            model = track.artwork,
                            contentDescription = null,
                            modifier = Modifier.fillMaxWidth().height(152.dp)
                                .clip(RoundedCornerShape(12.dp)),
                        )
                        Column(Modifier.padding(10.dp)) {
                            Text(
                                track.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                track.artist,
                                modifier = Modifier.clickable {
                                    navController.navigate(ExperimentalArtistRoute.forTrack(track))
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                track.catalogProvider?.let {
                                    when (it) {
                                        "wikimedia_audio" -> "Wikimedia Commons"
                                        "freesound" -> "Freesound"
                                        else -> it
                                    }
                                } ?: "Openverse",
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
        }
    }
}
