/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */

package com.nikhil.yt.ui.screens

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.nikhil.yt.R
import com.nikhil.yt.constants.DarkModeKey
import com.nikhil.yt.constants.PureBlackKey
import com.nikhil.yt.ui.component.BottomSheet
import com.nikhil.yt.ui.component.BottomSheetMenu
import com.nikhil.yt.ui.component.LocalMenuState
import com.nikhil.yt.ui.component.rememberBottomSheetState
import com.nikhil.yt.ui.screens.BrowseScreen
import com.nikhil.yt.ui.screens.artist.ArtistAlbumsScreen
import com.nikhil.yt.ui.screens.artist.ArtistItemsScreen
import com.nikhil.yt.ui.screens.artist.ArtistScreen
import com.nikhil.yt.ui.screens.artist.ArtistSongsScreen
import com.nikhil.yt.ui.screens.library.LibraryScreen
import com.nikhil.yt.ui.screens.playlist.AutoPlaylistScreen
import com.nikhil.yt.ui.screens.playlist.LocalPlaylistScreen
import com.nikhil.yt.ui.screens.playlist.OnlinePlaylistScreen
import com.nikhil.yt.ui.screens.playlist.TopPlaylistScreen
import com.nikhil.yt.ui.screens.playlist.CachePlaylistScreen
import com.nikhil.yt.ui.screens.search.OnlineSearchResult
import com.nikhil.yt.ui.screens.settings.AboutScreen
import com.nikhil.yt.ui.screens.settings.AppearanceSettings
import com.nikhil.yt.ui.screens.settings.BackupAndRestore
import com.nikhil.yt.ui.screens.settings.VeluneSettingsScreen
import com.nikhil.yt.ui.screens.settings.VeluneAccountSettingsScreen
import com.nikhil.yt.ui.screens.settings.ChangelogScreen
import com.nikhil.yt.ui.screens.settings.ContentSettings
import com.nikhil.yt.ui.screens.settings.DarkMode
import com.nikhil.yt.ui.screens.settings.DiscordLoginScreen
import com.nikhil.yt.ui.screens.settings.DiscordSettings
import com.nikhil.yt.ui.screens.settings.DebugSettings
import com.nikhil.yt.ui.screens.settings.IntegrationScreen
import com.nikhil.yt.ui.screens.settings.LastFMSettings
import com.nikhil.yt.ui.screens.settings.MusicTogetherScreen
import com.nikhil.yt.ui.screens.settings.PalettePickerScreen
import com.nikhil.yt.ui.screens.settings.PlayerSettings
import com.nikhil.yt.ui.screens.settings.VideoPlaybackSettings
import com.nikhil.yt.ui.screens.settings.PoTokenScreen
import com.nikhil.yt.ui.screens.settings.PrivacySettings
import com.nikhil.yt.ui.screens.settings.SettingsScreen
import com.nikhil.yt.ui.screens.settings.StorageSettings
import com.nikhil.yt.ui.screens.settings.ThemeCreatorScreen
import com.nikhil.yt.ui.utils.ShowMediaInfo
import com.nikhil.yt.utils.rememberEnumPreference
import com.nikhil.yt.utils.rememberPreference

/**
 * Navigation Compose may keep outgoing and incoming destinations composed at the same time while it
 * settles lifecycle state. Every Capsule route therefore owns an opaque full-screen canvas. Any
 * transparent areas in a screen reveal this destination's surface, never pixels from the previous
 * route underneath it.
 */
@Composable
private fun CapsuleRouteSurface(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        content = content,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
fun NavGraphBuilder.navigationBuilder(
    navController: NavHostController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    composable(Screens.Home.route) {
        CapsuleRouteSurface { HomeScreen(navController) }
    }
    composable(Screens.Library.route) {
        CapsuleRouteSurface { LibraryScreen(navController) }
    }
    composable("history") {
        CapsuleRouteSurface { HistoryScreen(navController) }
    }
    composable("stats") {
        CapsuleRouteSurface { StatsScreen(navController) }
    }
    composable("year_in_music") {
        CapsuleRouteSurface { YearInMusicScreen(navController) }
    }
    composable("mood_and_genres") {
        CapsuleRouteSurface { MoodAndGenresScreen(navController, scrollBehavior) }
    }

    composable("new_release") {
        CapsuleRouteSurface { NewReleaseScreen(navController, scrollBehavior) }
    }
    composable("charts_screen") {
        CapsuleRouteSurface { ChartsScreen(navController) }
    }
    composable(
        route = "browse/{browseId}",
        arguments = listOf(
            navArgument("browseId") {
                type = NavType.StringType
            }
        )
    ) {
        CapsuleRouteSurface {
            BrowseScreen(
                navController,
                scrollBehavior,
                it.arguments?.getString("browseId")
            )
        }
    }
    composable(
        route = "search/{query}",
        arguments =
        listOf(
            navArgument("query") {
                type = NavType.StringType
            },
        ),
    ) {
        CapsuleRouteSurface { OnlineSearchResult(navController) }
    }
    composable(
        route = "album/{albumId}",
        arguments =
        listOf(
            navArgument("albumId") {
                type = NavType.StringType
            },
        ),
    ) {
        CapsuleRouteSurface { AlbumScreen(navController, scrollBehavior) }
    }
    composable(
        route = "artist/{artistId}",
        arguments =
        listOf(
            navArgument("artistId") {
                type = NavType.StringType
            },
        ),
    ) {
        CapsuleRouteSurface { ArtistScreen(navController, scrollBehavior) }
    }
    composable(
        route = "artist/{artistId}/songs",
        arguments =
        listOf(
            navArgument("artistId") {
                type = NavType.StringType
            },
        ),
    ) {
        CapsuleRouteSurface { ArtistSongsScreen(navController, scrollBehavior) }
    }
    composable(
        route = "artist/{artistId}/albums",
        arguments = listOf(
            navArgument("artistId") {
                type = NavType.StringType
            }
        )
    ) {
        CapsuleRouteSurface { ArtistAlbumsScreen(navController, scrollBehavior) }
    }
    composable(
        route = "artist/{artistId}/items?browseId={browseId}&params={params}",
        arguments =
        listOf(
            navArgument("artistId") {
                type = NavType.StringType
            },
            navArgument("browseId") {
                type = NavType.StringType
                nullable = true
            },
            navArgument("params") {
                type = NavType.StringType
                nullable = true
            },
        ),
    ) {
        CapsuleRouteSurface { ArtistItemsScreen(navController, scrollBehavior) }
    }
    composable(
        route = "online_playlist/{playlistId}",
        arguments =
        listOf(
            navArgument("playlistId") {
                type = NavType.StringType
            },
        ),
    ) {
        CapsuleRouteSurface { OnlinePlaylistScreen(navController, scrollBehavior) }
    }
    composable(
        route = "local_playlist/{playlistId}",
        arguments =
        listOf(
            navArgument("playlistId") {
                type = NavType.StringType
            },
        ),
    ) {
        CapsuleRouteSurface { LocalPlaylistScreen(navController, scrollBehavior) }
    }
    composable(
        route = "auto_playlist/{playlist}",
        arguments =
        listOf(
            navArgument("playlist") {
                type = NavType.StringType
            },
        ),
    ) {
        CapsuleRouteSurface { AutoPlaylistScreen(navController, scrollBehavior) }
    }
    composable(
        route = "cache_playlist/{playlist}",
        arguments =
            listOf(
                navArgument("playlist") {
                type = NavType.StringType
            },
        ),
    ) {
        CapsuleRouteSurface { CachePlaylistScreen(navController, scrollBehavior) }
    }
    composable(
        route = "top_playlist/{top}",
        arguments =
        listOf(
            navArgument("top") {
                type = NavType.StringType
            },
        ),
    ) {
        CapsuleRouteSurface { TopPlaylistScreen(navController, scrollBehavior) }
    }
    composable(
        route = "youtube_browse/{browseId}?params={params}",
        arguments =
        listOf(
            navArgument("browseId") {
                type = NavType.StringType
                nullable = true
            },
            navArgument("params") {
                type = NavType.StringType
                nullable = true
            },
        ),
    ) {
        CapsuleRouteSurface { YouTubeBrowseScreen(navController) }
    }
    composable("settings") {
        CapsuleRouteSurface { VeluneSettingsScreen(navController) }
    }
    composable("settings/account") {
        CapsuleRouteSurface { VeluneAccountSettingsScreen(navController) }
    }
    composable("settings/appearance") {
        CapsuleRouteSurface { AppearanceSettings(navController, scrollBehavior) }
    }
    composable("settings/appearance/palette_picker") {
        CapsuleRouteSurface { PalettePickerScreen(navController) }
    }
    composable("settings/appearance/theme_creator") {
        CapsuleRouteSurface { ThemeCreatorScreen(navController) }
    }
    composable("settings/content") {
        CapsuleRouteSurface { ContentSettings(navController, scrollBehavior) }
    }
    composable("settings/player") {
        CapsuleRouteSurface { PlayerSettings(navController, scrollBehavior) }
    }
    composable("settings/video_playback") {
        CapsuleRouteSurface { VideoPlaybackSettings(navController) }
    }
    composable("settings/storage") {
        CapsuleRouteSurface { StorageSettings(navController, scrollBehavior) }
    }
    composable("settings/privacy") {
        CapsuleRouteSurface { PrivacySettings(navController, scrollBehavior) }
    }
    composable("settings/backup_restore") {
        CapsuleRouteSurface { BackupAndRestore(navController, scrollBehavior) }
    }
    composable("settings/discord") {
        CapsuleRouteSurface { DiscordSettings(navController, scrollBehavior) }
    }
    composable("settings/integration") {
        CapsuleRouteSurface { IntegrationScreen(navController, scrollBehavior) }
    }
    composable("settings/music_together") {
        CapsuleRouteSurface { MusicTogetherScreen(navController, scrollBehavior) }
    }
    composable("settings/lastfm") {
        CapsuleRouteSurface { LastFMSettings(navController, scrollBehavior) }
    }
    composable("settings/discord/experimental") {
        CapsuleRouteSurface { com.nikhil.yt.ui.screens.settings.DiscordExperimental(navController) }
    }
    composable("settings/misc") {
        CapsuleRouteSurface { DebugSettings(navController) }
    }

    composable("settings/changelog") {
        CapsuleRouteSurface { ChangelogScreen(navController, scrollBehavior) }
    }
    composable("settings/discord/login") {
        CapsuleRouteSurface { DiscordLoginScreen(navController) }
    }
    composable("settings/about") {
        CapsuleRouteSurface { AboutScreen(navController, scrollBehavior) }
    }
    composable("settings/po_token") {
        CapsuleRouteSurface { PoTokenScreen(navController, scrollBehavior) }
    }
    composable("login") {
        CapsuleRouteSurface { LoginScreen(navController) }
    }
}
