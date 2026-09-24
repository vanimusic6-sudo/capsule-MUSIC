/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */



package com.nikhil.yt.ui.screens

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import com.nikhil.yt.ui.screens.search.SoundCloudWebPreview
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

@OptIn(ExperimentalMaterial3Api::class)
fun NavGraphBuilder.navigationBuilder(
    navController: NavHostController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    routeComposable(Screens.Home.route) {
        HomeScreen(navController)
    }
    routeComposable(
        Screens.Library.route,
    ) {
        LibraryScreen(navController)
    }
    routeComposable("history") {
        HistoryScreen(navController)
    }
    routeComposable("stats") {
        StatsScreen(navController)
    }
    routeComposable("year_in_music") {
        YearInMusicScreen(navController)
    }
    routeComposable("mood_and_genres") {
        MoodAndGenresScreen(navController, scrollBehavior)
    }

    routeComposable("new_release") {
        NewReleaseScreen(navController, scrollBehavior)
    }
    routeComposable("charts_screen") {
       ChartsScreen(navController)
    }
    routeComposable(
        route = "browse/{browseId}",
        arguments = listOf(
            navArgument("browseId") {
                type = NavType.StringType
            }
        )
    ) {
        BrowseScreen(
            navController,
            scrollBehavior,
            it.arguments?.getString("browseId")
        )
    }
    routeComposable(
        route = "search/{query}",
        arguments =
        listOf(
            navArgument("query") {
                type = NavType.StringType
            },
        ),
    ) {
        OnlineSearchResult(navController)
    }
    routeComposable(
        route = "soundcloud_preview/{query}",
        arguments = listOf(navArgument("query") { type = NavType.StringType }),
    ) { entry ->
        SoundCloudWebPreview(
            query = entry.arguments?.getString("query").orEmpty(),
            navController = navController,
        )
    }
    routeComposable(
        route = "album/{albumId}",
        arguments =
        listOf(
            navArgument("albumId") {
                type = NavType.StringType
            },
        ),
    ) {
        AlbumScreen(navController, scrollBehavior)
    }
    routeComposable(
        route = "artist/{artistId}",
        arguments =
        listOf(
            navArgument("artistId") {
                type = NavType.StringType
            },
        ),
    ) {
        ArtistScreen(navController, scrollBehavior)
    }
    routeComposable(
        route = "artist/{artistId}/songs",
        arguments =
        listOf(
            navArgument("artistId") {
                type = NavType.StringType
            },
        ),
    ) {
        ArtistSongsScreen(navController, scrollBehavior)
    }
    routeComposable(
        route = "artist/{artistId}/albums",
        arguments = listOf(
            navArgument("artistId") {
                type = NavType.StringType
            }
        )
    ) {
        ArtistAlbumsScreen(navController, scrollBehavior)
    }
    routeComposable(
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
        ArtistItemsScreen(navController, scrollBehavior)
    }
    routeComposable(
        route = "online_playlist/{playlistId}",
        arguments =
        listOf(
            navArgument("playlistId") {
                type = NavType.StringType
            },
        ),
    ) {
        OnlinePlaylistScreen(navController, scrollBehavior)
    }
    routeComposable(
        route = "local_playlist/{playlistId}",
        arguments =
        listOf(
            navArgument("playlistId") {
                type = NavType.StringType
            },
        ),
    ) {
        LocalPlaylistScreen(navController, scrollBehavior)
    }
    routeComposable(
        route = "auto_playlist/{playlist}",
        arguments =
        listOf(
            navArgument("playlist") {
                type = NavType.StringType
            },
        ),
    ) {
        AutoPlaylistScreen(navController, scrollBehavior)
    }
    routeComposable(
        route = "cache_playlist/{playlist}",
        arguments =
            listOf(
                navArgument("playlist") {
                    type = NavType.StringType
            },
        ),
    ) {
        CachePlaylistScreen(navController, scrollBehavior)
    }
    routeComposable(
        route = "top_playlist/{top}",
        arguments =
        listOf(
            navArgument("top") {
                type = NavType.StringType
            },
        ),
    ) {
        TopPlaylistScreen(navController, scrollBehavior)
    }
    routeComposable(
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
        YouTubeBrowseScreen(navController)
    }
    routeComposable("settings") {
        VeluneSettingsScreen(navController)
    }
    routeComposable("settings/account") {
        VeluneAccountSettingsScreen(navController)
    }
    routeComposable("settings/appearance") {
        AppearanceSettings(navController, scrollBehavior)
    }
    routeComposable("settings/appearance/palette_picker") {
        PalettePickerScreen(navController)
    }
    routeComposable("settings/appearance/theme_creator") {
        ThemeCreatorScreen(navController)
    }
    routeComposable("settings/content") {
        ContentSettings(navController, scrollBehavior)
    }
    routeComposable("settings/player") {
        PlayerSettings(navController, scrollBehavior)
    }
    routeComposable("settings/video_playback") {
        VideoPlaybackSettings(navController)
    }
    routeComposable("settings/storage") {
        StorageSettings(navController, scrollBehavior)
    }
    routeComposable("settings/privacy") {
        PrivacySettings(navController, scrollBehavior)
    }
    routeComposable("settings/backup_restore") {
        BackupAndRestore(navController, scrollBehavior)
    }
    routeComposable("settings/discord") {
        DiscordSettings(navController, scrollBehavior)
    }
    routeComposable("settings/integration") {
        IntegrationScreen(navController, scrollBehavior)
    }
    routeComposable("settings/music_together") {
        MusicTogetherScreen(navController, scrollBehavior)
    }
    routeComposable("settings/lastfm") {
        LastFMSettings(navController, scrollBehavior)
    }
    routeComposable("settings/discord/experimental") {
        com.nikhil.yt.ui.screens.settings.DiscordExperimental(navController)
    }
    routeComposable("settings/misc") {
        DebugSettings(navController)
    }

    routeComposable("settings/changelog") {
        ChangelogScreen(navController, scrollBehavior)
    }
    routeComposable("settings/discord/login") {
        DiscordLoginScreen(navController)
    }
    routeComposable("settings/about") {
        AboutScreen(navController, scrollBehavior)
    }
    routeComposable("settings/po_token") {
        PoTokenScreen(navController, scrollBehavior)
    }
    routeComposable("login") {
        LoginScreen(navController)
    }
}
