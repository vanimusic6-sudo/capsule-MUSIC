/**
 * Capsule MUSIC
 * The welcome flow: sign in or don't, then pick artists to follow.
 * GPL-3.0
 */

package com.nikhil.yt.ui.screens.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import coil3.compose.AsyncImage
import com.nikhil.yt.R
import com.nikhil.yt.innertube.models.ArtistItem
import com.nikhil.yt.ui.screens.LoginScreen
import com.nikhil.yt.viewmodels.WelcomeViewModel
import kotlinx.coroutines.launch

private const val ROUTE_ACCOUNT = "welcome/account"
private const val ROUTE_LOGIN = "welcome/login"
private const val ROUTE_ARTISTS = "welcome/artists"

/**
 * Shown once, over everything, on a genuinely first launch.
 *
 * It carries its own tiny NavHost rather than joining the app's. The app's graph is a flat set of
 * destinations the bottom bar navigates between, and a first-run flow is not one of those: putting
 * it there would make it a place the user can arrive at again, and would make the bottom bar's
 * "pop back to the start destination" mean something different. Two screens and a sign-in page do
 * not need to be reachable from anywhere else, so they are not.
 */
@Composable
fun CapsuleWelcome(onFinished: () -> Unit) {
    val navController = rememberNavController()
    val viewModel: WelcomeViewModel = hiltViewModel()

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface),
    ) {
        NavHost(
            navController = navController,
            startDestination = ROUTE_ACCOUNT,
            enterTransition = { androidx.compose.animation.EnterTransition.None },
            exitTransition = { androidx.compose.animation.ExitTransition.None },
            popEnterTransition = { androidx.compose.animation.EnterTransition.None },
            popExitTransition = { androidx.compose.animation.ExitTransition.None },
        ) {
            composable(ROUTE_ACCOUNT) {
                WelcomeAccountPage(
                    onSignIn = { navController.navigate(ROUTE_LOGIN) },
                    onContinue = { navController.navigate(ROUTE_ARTISTS) },
                )
            }
            composable(ROUTE_LOGIN) {
                // The same sign-in page the settings use. One flow, one place it can go wrong.
                LoginScreen(navController)
            }
            composable(ROUTE_ARTISTS) {
                WelcomeArtistsPage(viewModel = viewModel, onFinished = onFinished)
            }
        }
    }
}

/**
 * Sign in, or don't.
 *
 * Both ways out are on screen at the same time and neither is dressed as the wrong answer: the app
 * works signed out, so skipping is a real choice, not a way of postponing something.
 */
@Composable
private fun WelcomeAccountPage(
    onSignIn: () -> Unit,
    onContinue: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(72.dp))
        CapsuleMark(Modifier.size(220.dp))
        Spacer(Modifier.weight(1f))

        Text(
            text = stringResource(R.string.welcome_account_title),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 22.sp,
            lineHeight = 28.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.welcome_account_description),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            fontSize = 14.sp,
            lineHeight = 19.sp,
        )

        Spacer(Modifier.height(24.dp))
        WelcomeButton(
            text = stringResource(R.string.welcome_sign_in),
            filled = false,
            onClick = onSignIn,
        )
        Spacer(Modifier.height(12.dp))
        WelcomeContinue(
            label = stringResource(R.string.welcome_continue),
            onClick = onContinue,
        )
        Spacer(Modifier.height(24.dp))
    }
}

/** Pick artists, and be subscribed to them by the time the grid closes. */
@Composable
private fun WelcomeArtistsPage(
    viewModel: WelcomeViewModel,
    onFinished: () -> Unit,
) {
    val artists by viewModel.artists.collectAsState()
    val selected by viewModel.selected.collectAsState()
    val loading by viewModel.loading.collectAsState()
    var query by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    var finishing by remember { mutableStateOf(false) }

    LaunchedEffect(query) { viewModel.search(query) }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.welcome_artists_title),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 26.sp,
            lineHeight = 31.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(16.dp))

        WelcomeSearchField(query = query, onQueryChange = { query = it })

        Spacer(Modifier.height(16.dp))

        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            modifier = Modifier.weight(1f).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(artists, key = { it.id }) { artist ->
                WelcomeArtist(
                    artist = artist,
                    selected = artist.id in selected,
                    onClick = { viewModel.toggle(artist.id) },
                )
            }
        }

        // An empty grid while a request is in flight is a wait, not a result.
        if (artists.isEmpty()) {
            Text(
                text =
                    stringResource(
                        if (loading) R.string.welcome_artists_loading else R.string.welcome_artists_none,
                    ),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                fontSize = 14.sp,
                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
            )
        }

        Spacer(Modifier.height(12.dp))
        WelcomeContinue(
            label =
                if (selected.isEmpty()) {
                    stringResource(R.string.welcome_skip)
                } else {
                    stringResource(R.string.welcome_follow_count, selected.size)
                },
            enabled = !finishing,
            onClick = {
                if (finishing) return@WelcomeContinue
                finishing = true
                scope.launch {
                    viewModel.subscribeToSelection()
                    onFinished()
                }
            },
        )
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun WelcomeSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(onSurface.copy(alpha = 0.06f))
                .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.search),
            contentDescription = null,
            tint = onSurface.copy(alpha = 0.5f),
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.size(12.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                Text(
                    text = stringResource(R.string.welcome_artists_search_hint),
                    color = onSurface.copy(alpha = 0.4f),
                    fontSize = 16.sp,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(color = onSurface, fontSize = 16.sp),
                cursorBrush = SolidColor(onSurface),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun WelcomeArtist(
    artist: ArtistItem,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(CircleShape)
                    .background(onSurface.copy(alpha = if (selected) 0.22f else 0.08f))
                    .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = artist.thumbnail,
                contentDescription = artist.title,
                modifier =
                    Modifier
                        // The ring is the selection: the portrait shrinks inside it rather than
                        // having anything drawn over the face.
                        .fillMaxSize()
                        .padding(if (selected) 4.dp else 0.dp)
                        .clip(CircleShape),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = artist.title,
            color = onSurface.copy(alpha = if (selected) 0.95f else 0.6f),
            fontSize = 11.sp,
            lineHeight = 14.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun WelcomeButton(
    text: String,
    filled: Boolean,
    onClick: () -> Unit,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 60.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(if (filled) onSurface else onSurface.copy(alpha = 0.08f))
                .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (filled) MaterialTheme.colorScheme.surface else onSurface.copy(alpha = 0.85f),
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun WelcomeContinue(
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 60.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(onSurface.copy(alpha = if (enabled) 0.62f else 0.24f))
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.surface,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Icon(
            painter = painterResource(R.drawable.arrow_forward),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.surface,
            modifier = Modifier.size(24.dp),
        )
    }
}

/**
 * The Capsule mark: a disc, a ring around it and one point on the ring.
 *
 * Drawn rather than animated. The point sits still — this is a logo, not a clock, and a first-run
 * screen someone reads for ten seconds has no business holding the frame rate at the display's
 * refresh rate to move a dot.
 */
@Composable
private fun CapsuleMark(modifier: Modifier = Modifier) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    androidx.compose.foundation.Canvas(modifier) {
        val side = minOf(size.width, size.height)
        val centre = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)

        drawCircle(
            color = onSurface.copy(alpha = 0.10f),
            radius = side * 0.5f,
            center = centre,
        )
        drawCircle(
            color = onSurface.copy(alpha = 0.34f),
            radius = side * 0.16f,
            center = centre,
        )

        val ringWidth = side * 0.74f
        val ringHeight = side * 0.40f
        rotate(-24f, centre) {
            drawOval(
                color = onSurface.copy(alpha = 0.34f),
                topLeft =
                    androidx.compose.ui.geometry.Offset(
                        centre.x - ringWidth / 2f,
                        centre.y - ringHeight / 2f,
                    ),
                size = androidx.compose.ui.geometry.Size(ringWidth, ringHeight),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = side * 0.012f),
            )
            drawCircle(
                color = onSurface.copy(alpha = 0.42f),
                radius = side * 0.035f,
                center =
                    androidx.compose.ui.geometry.Offset(
                        centre.x + ringWidth / 2f,
                        centre.y,
                    ),
            )
        }
    }
}
