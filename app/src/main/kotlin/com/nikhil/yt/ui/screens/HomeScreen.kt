package com.nikhil.yt.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults.Indicator
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import com.nikhil.yt.LocalPlayerAwareWindowInsets
import com.nikhil.yt.LocalPlayerConnection
import com.nikhil.yt.R
import com.nikhil.yt.ui.component.LocalMenuState
import com.nikhil.yt.ui.component.NavigationTitle
import com.nikhil.yt.ui.component.StandardChrome
import com.nikhil.yt.ui.utils.liveSavedStateHandle
import com.nikhil.yt.viewmodels.HomeViewModel

/**
 * The main Home is deliberately short and stable: Quick Picks, Continue Listening
 * and one Collections button. The full streaming discovery feed lives on a separate
 * route, so loading more YouTube carousels cannot reposition the Home canvas while
 * the Activity goes to the background or resumes at the bottom.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    navController: NavController,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val menuState = LocalMenuState.current
    val haptic = LocalHapticFeedback.current
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()
    val isPlaying by playerConnection.isPlaying.collectAsState()
    val quickPicks by viewModel.quickPicks.collectAsState()
    val keepListening by viewModel.keepListening.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()

    val listState = rememberLazyListState()
    val horizontalShelvesEnabled = remember(listState) {
        derivedStateOf { !listState.isScrollInProgress }
    }
    val refreshState = rememberPullToRefreshState()
    val entry = LocalNavBackStackEntry.current
    val scrollToTop = entry?.liveSavedStateHandle()
        ?.getStateFlow("scrollToTop", false)?.collectAsState()
    LaunchedEffect(scrollToTop?.value) {
        if (scrollToTop?.value == true) {
            listState.animateScrollToItem(0)
            entry?.liveSavedStateHandle()?.set("scrollToTop", false)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(StandardChrome.background)
            .pullToRefresh(
                state = refreshState,
                isRefreshing = isRefreshing,
                onRefresh = viewModel::refresh,
            ),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues(),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            // Use Compose's native spline/velocity. The previous 1.20x fling
            // wrapper created an aggressive take-off without removing jank.
            overscrollEffect = null,
        ) {
            item(key = "home:quick-hero") {
                quickPicks?.takeIf { it.isNotEmpty() }?.let { picks ->
                    QuickPicksSection(
                        quickPicks = picks,
                        userScrollEnabled = horizontalShelvesEnabled.value,
                        mediaMetadata = mediaMetadata,
                        isPlaying = isPlaying,
                        navController = navController,
                        playerConnection = playerConnection,
                        menuState = menuState,
                        haptic = haptic,
                    )
                } ?: Spacer(Modifier.height(218.dp))
            }
            keepListening?.takeIf { it.isNotEmpty() }?.let { songs ->
                item(key = "home:keep-listening") {
                    Column {
                        NavigationTitle(title = stringResource(R.string.keep_listening))
                        KeepListeningSection(
                            keepListening = songs,
                            userScrollEnabled = horizontalShelvesEnabled.value,
                            mediaMetadata = mediaMetadata,
                            isPlaying = isPlaying,
                            navController = navController,
                            playerConnection = playerConnection,
                            menuState = menuState,
                            haptic = haptic,
                        )
                    }
                }
            }
            item(key = "home:collections-button") {
                FilledTonalButton(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    onClick = { navController.navigate("home_collections") },
                ) {
                    Text(stringResource(R.string.home_collections))
                    Icon(
                        painter = painterResource(R.drawable.arrow_forward),
                        contentDescription = null,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
        Indicator(
            isRefreshing = isRefreshing,
            state = refreshState,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(LocalPlayerAwareWindowInsets.current.asPaddingValues()),
        )
    }
}
