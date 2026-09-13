/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */

package com.nikhil.yt.ui.screens.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.navigation.NavController
import com.nikhil.yt.LocalDatabase
import com.nikhil.yt.R
import com.nikhil.yt.constants.ChipSortTypeKey
import com.nikhil.yt.constants.DisableBlurKey
import com.nikhil.yt.constants.LibraryFilter
import com.nikhil.yt.constants.PlaylistTagsFilterKey
import com.nikhil.yt.constants.ShowTagsInLibraryKey
import com.nikhil.yt.ui.component.ChipsRow
import com.nikhil.yt.ui.component.TagsFilterChips
import com.nikhil.yt.ui.component.capsuleSceneItem
import com.nikhil.yt.ui.component.rememberCapsuleSceneMotionState
import com.nikhil.yt.utils.rememberEnumPreference
import com.nikhil.yt.utils.rememberPreference

@Composable
fun LibraryScreen(navController: NavController) {
    var filterType by rememberEnumPreference(ChipSortTypeKey, LibraryFilter.LIBRARY)
    val (disableBlur) = rememberPreference(DisableBlurKey, true)
    val sceneMotion = rememberCapsuleSceneMotionState(key = filterType)

    val database = LocalDatabase.current
    val (showTagsInLibrary) = rememberPreference(ShowTagsInLibraryKey, true)
    val (selectedTagsFilter, onSelectedTagsFilterChange) = rememberPreference(PlaylistTagsFilterKey, "")
    val selectedTagIds = remember(selectedTagsFilter) {
        selectedTagsFilter.split(",").filter { it.isNotBlank() }.toSet()
    }

    val filterContent = @Composable {
        Column {
            Row(
                modifier = Modifier.capsuleSceneItem(
                    state = sceneMotion,
                    order = 0,
                    lift = 8.dp,
                    depth = 0.004f,
                ),
            ) {
                ChipsRow(
                    chips =
                    listOf(
                        LibraryFilter.PLAYLISTS to stringResource(R.string.filter_playlists),
                        LibraryFilter.SONGS to stringResource(R.string.filter_songs),
                        LibraryFilter.ALBUMS to stringResource(R.string.filter_albums),
                        LibraryFilter.ARTISTS to stringResource(R.string.filter_artists),
                    ),
                    currentValue = filterType,
                    onValueUpdate = {
                        filterType =
                            if (filterType == it) {
                                LibraryFilter.LIBRARY
                            } else {
                                it
                            }
                    },
                    modifier = Modifier.weight(1f),
                )
            }

            if (showTagsInLibrary) {
                TagsFilterChips(
                    database = database,
                    selectedTags = selectedTagIds,
                    onTagToggle = { tag ->
                        val newTags = if (tag.id in selectedTagIds) {
                            selectedTagIds - tag.id
                        } else {
                            selectedTagIds + tag.id
                        }
                        onSelectedTagsFilterChange(newTags.joinToString(","))
                    },
                    modifier = Modifier
                        .capsuleSceneItem(
                            state = sceneMotion,
                            order = 1,
                            lift = 9.dp,
                            depth = 0.004f,
                        )
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }

    // Capture M3 Expressive colors from theme outside drawBehind
    val color1 = MaterialTheme.colorScheme.primary
    val color2 = MaterialTheme.colorScheme.secondary
    val color3 = MaterialTheme.colorScheme.tertiary
    val color4 = MaterialTheme.colorScheme.primaryContainer
    val color5 = MaterialTheme.colorScheme.secondaryContainer
    val surfaceColor = MaterialTheme.colorScheme.surface

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(surfaceColor),
    ) {
        // M3E Mesh gradient background layer at the top
        if (!disableBlur) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxSize(0.7f)
                    .align(Alignment.TopCenter)
                    .zIndex(-1f)
                    .drawBehind {
                        val width = size.width
                        val height = size.height

                        drawRect(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    color1.copy(alpha = 0.38f),
                                    color1.copy(alpha = 0.24f),
                                    color1.copy(alpha = 0.14f),
                                    color1.copy(alpha = 0.06f),
                                    Color.Transparent,
                                ),
                                center = Offset(width * 0.15f, height * 0.1f),
                                radius = width * 0.55f,
                            ),
                        )

                        drawRect(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    color2.copy(alpha = 0.34f),
                                    color2.copy(alpha = 0.2f),
                                    color2.copy(alpha = 0.11f),
                                    color2.copy(alpha = 0.05f),
                                    Color.Transparent,
                                ),
                                center = Offset(width * 0.85f, height * 0.2f),
                                radius = width * 0.65f,
                            ),
                        )

                        drawRect(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    color3.copy(alpha = 0.3f),
                                    color3.copy(alpha = 0.17f),
                                    color3.copy(alpha = 0.09f),
                                    color3.copy(alpha = 0.04f),
                                    Color.Transparent,
                                ),
                                center = Offset(width * 0.3f, height * 0.45f),
                                radius = width * 0.6f,
                            ),
                        )

                        drawRect(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    color4.copy(alpha = 0.26f),
                                    color4.copy(alpha = 0.14f),
                                    color4.copy(alpha = 0.08f),
                                    color4.copy(alpha = 0.03f),
                                    Color.Transparent,
                                ),
                                center = Offset(width * 0.7f, height * 0.5f),
                                radius = width * 0.7f,
                            ),
                        )

                        drawRect(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    color5.copy(alpha = 0.22f),
                                    color5.copy(alpha = 0.12f),
                                    color5.copy(alpha = 0.06f),
                                    color5.copy(alpha = 0.02f),
                                    Color.Transparent,
                                ),
                                center = Offset(width * 0.5f, height * 0.75f),
                                radius = width * 0.8f,
                            ),
                        )

                        drawRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.Transparent,
                                    surfaceColor.copy(alpha = 0.22f),
                                    surfaceColor.copy(alpha = 0.55f),
                                    surfaceColor,
                                ),
                                startY = height * 0.4f,
                                endY = height,
                            ),
                        )
                    },
            ) {}
        }

        // The destination canvas never moves. Only the actual controls above use scene motion.
        // Moving this whole container exposed the previous route underneath and looked like two
        // screens were physically stacked on top of each other.
        Box(
            modifier = Modifier.fillMaxSize(),
        ) {
            when (filterType) {
                LibraryFilter.LIBRARY -> LibraryMixScreen(navController, filterContent)
                LibraryFilter.PLAYLISTS -> LibraryPlaylistsScreen(navController, filterContent)
                LibraryFilter.SONGS -> LibrarySongsScreen(
                    navController,
                    { filterType = LibraryFilter.LIBRARY },
                )

                LibraryFilter.ALBUMS -> LibraryAlbumsScreen(
                    navController,
                    { filterType = LibraryFilter.LIBRARY },
                )

                LibraryFilter.ARTISTS -> LibraryArtistsScreen(
                    navController,
                    { filterType = LibraryFilter.LIBRARY },
                )
            }
        }
    }
}
