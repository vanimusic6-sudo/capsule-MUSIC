/**
 * Velune / Capsule MUSIC
 * Capsule Mini Player visual layer
 * Licensed under GPL-3.0
 */

package com.nikhil.yt.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import coil3.compose.AsyncImage
import com.nikhil.yt.LocalDatabase
import com.nikhil.yt.LocalPlayerConnection
import com.nikhil.yt.R
import com.nikhil.yt.constants.SwipeSensitivityKey
import com.nikhil.yt.constants.SwipeThumbnailKey
import com.nikhil.yt.db.entities.ArtistEntity
import com.nikhil.yt.extensions.togglePlayPause
import com.nikhil.yt.utils.rememberPreference
import kotlinx.coroutines.flow.flowOf
import kotlin.math.roundToInt

private val CapsuleMiniBackground =
    Color(0xFF171717)

private val CapsuleMiniOutline =
    Color(0xFF363636)

private val CapsuleMiniText =
    Color(0xFFF1F1F1)

private val CapsuleMiniSecondaryText =
    Color(0xFF969696)

@Composable
fun CapsuleMiniPlayer(
    position: Long,
    duration: Long,
    modifier: Modifier = Modifier,
    pureBlack: Boolean,
) {
    val playerConnection =
        LocalPlayerConnection.current ?: return

    val layoutDirection =
        LocalLayoutDirection.current

    val coroutineScope =
        rememberCoroutineScope()

    val swipeSensitivity by
        rememberPreference(
            SwipeSensitivityKey,
            defaultValue = 0.73f,
        )

    val swipeThumbnail by
        rememberPreference(
            SwipeThumbnailKey,
            defaultValue = true,
        )

    /*
     * IMPORTANT:
     * We deliberately reuse Velune's existing SwipeableMiniPlayerBox.
     *
     * That means:
     * - the same swipe threshold
     * - the same previous/next logic
     * - the same sensitivity setting
     * - the same Discord-presence restart behaviour
     *
     * Capsule only supplies different content.
     */
    SwipeableMiniPlayerBox(
        modifier = modifier,
        swipeSensitivity = swipeSensitivity,
        swipeThumbnail = swipeThumbnail,
        playerConnection = playerConnection,
        layoutDirection = layoutDirection,
        coroutineScope = coroutineScope,
        pureBlack = pureBlack,
        useLegacyBackground = false,
    ) { offsetX ->

        val shape =
            RoundedCornerShape(24.dp)

        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .offset {
                        IntOffset(
                            offsetX.roundToInt(),
                            0,
                        )
                    }
                    .clip(shape)
                    .background(
                        if (pureBlack) {
                            Color.Black
                        } else {
                            CapsuleMiniBackground
                        },
                    )
                    .border(
                        width = 1.dp,
                        color = CapsuleMiniOutline,
                        shape = shape,
                    ),
        ) {
            CapsuleMiniPlayerContent(
                position = position,
                duration = duration,
            )
        }
    }
}

@Composable
private fun CapsuleMiniPlayerContent(
    position: Long,
    duration: Long,
) {
    val playerConnection =
        LocalPlayerConnection.current ?: return

    val database =
        LocalDatabase.current

    val mediaMetadata by
        playerConnection.mediaMetadata.collectAsState()

    val isPlaying by
        playerConnection.isPlaying.collectAsState()

    val playbackState by
        playerConnection.playbackState.collectAsState()

    val currentSong by
        playerConnection.currentSong.collectAsState(
            initial = null,
        )

    val isLiked =
        currentSong?.song?.liked == true

    val firstArtist =
        mediaMetadata?.artists?.firstOrNull()

    val libraryArtist by
        remember(firstArtist?.id) {
            firstArtist
                ?.id
                ?.let {
                    database.artist(it)
                }
                ?: flowOf(null)
        }.collectAsState(initial = null)

    val isSubscribed =
        libraryArtist?.artist?.bookmarkedAt != null

    val isLoading =
        playbackState == Player.STATE_BUFFERING

    Row(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(horizontal = 9.dp),
        verticalAlignment =
            Alignment.CenterVertically,
    ) {
        /*
         * Artwork + circular playback progress.
         */
        Box(
            modifier =
                Modifier.size(50.dp),
            contentAlignment =
                Alignment.Center,
        ) {
            CircularProgressIndicator(
                progress = {
                    (
                        position.toFloat() /
                            duration
                                .coerceAtLeast(1L)
                                .toFloat()
                    ).coerceIn(
                        0f,
                        1f,
                    )
                },
                modifier =
                    Modifier.fillMaxSize(),
                color =
                    CapsuleMiniText,
                trackColor =
                    CapsuleMiniText.copy(
                        alpha = 0.15f,
                    ),
                strokeWidth = 2.dp,
            )

            Box(
                modifier =
                    Modifier
                        .size(43.dp)
                        .clip(CircleShape)
                        .clickable {
                            if (
                                playbackState ==
                                Player.STATE_ENDED
                            ) {
                                playerConnection
                                    .player
                                    .seekTo(
                                        0,
                                        0,
                                    )

                                playerConnection
                                    .player
                                    .playWhenReady =
                                    true
                            } else {
                                playerConnection
                                    .player
                                    .togglePlayPause()
                            }
                        },
                contentAlignment =
                    Alignment.Center,
            ) {
                AsyncImage(
                    model =
                        mediaMetadata
                            ?.thumbnailUrl,
                    contentDescription = null,
                    contentScale =
                        ContentScale.Crop,
                    modifier =
                        Modifier.fillMaxSize(),
                )

                if (
                    !isPlaying ||
                    isLoading ||
                    playbackState ==
                    Player.STATE_ENDED
                ) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .background(
                                    Color.Black.copy(
                                        alpha = 0.48f,
                                    ),
                                ),
                        contentAlignment =
                            Alignment.Center,
                    ) {
                        if (isLoading) {
                            com.nikhil.yt.ui.component
                                .VeluneLoader(
                                    size = 18.dp,
                                )
                        } else {
                            Icon(
                                painter =
                                    painterResource(
                                        if (
                                            playbackState ==
                                            Player.STATE_ENDED
                                        ) {
                                            R.drawable.replay
                                        } else {
                                            R.drawable.play
                                        },
                                    ),
                                contentDescription =
                                    null,
                                tint =
                                    Color.White,
                                modifier =
                                    Modifier.size(
                                        20.dp,
                                    ),
                            )
                        }
                    }
                }
            }
        }

        /*
         * Title + artist.
         */
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .padding(
                        start = 12.dp,
                        end = 7.dp,
                    ),
            verticalArrangement =
                Arrangement.Center,
        ) {
            Text(
                text =
                    mediaMetadata?.title
                        ?: "Unknown Song",
                color =
                    CapsuleMiniText,
                fontSize = 14.sp,
                fontWeight =
                    FontWeight.SemiBold,
                maxLines = 1,
                overflow =
                    TextOverflow.Clip,
                modifier =
                    Modifier.basicMarquee(),
            )

            Text(
                text =
                    mediaMetadata
                        ?.artists
                        ?.joinToString {
                            it.name
                        }
                        ?: "Unknown Artist",
                color =
                    CapsuleMiniSecondaryText,
                fontSize = 12.sp,
                maxLines = 1,
                overflow =
                    TextOverflow.Clip,
                modifier =
                    Modifier.basicMarquee(),
            )
        }

        /*
         * Artist/subscription action.
         */
        CapsuleMiniActionButton(
            selected = isSubscribed,
            selectedIcon =
                R.drawable.subscribed,
            normalIcon =
                R.drawable.person,
            onClick = {
                firstArtist?.let { artistInfo ->
                    artistInfo.id?.let { artistId ->
                        database.transaction {
                            val artist =
                                libraryArtist
                                    ?.artist

                            if (artist != null) {
                                update(
                                    artist.toggleLike(),
                                )
                            } else {
                                insert(
                                    ArtistEntity(
                                        id = artistId,
                                        name =
                                            artistInfo.name,
                                        channelId = null,
                                        thumbnailUrl =
                                            null,
                                    ).toggleLike(),
                                )
                            }
                        }
                    }
                }
            },
        )

        /*
         * Favourite.
         */
        CapsuleMiniActionButton(
            selected = isLiked,
            selectedIcon =
                R.drawable.favorite,
            normalIcon =
                R.drawable.favorite_border,
            onClick =
                playerConnection::toggleLike,
        )
    }
}

@Composable
private fun CapsuleMiniActionButton(
    selected: Boolean,
    selectedIcon: Int,
    normalIcon: Int,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .padding(start = 5.dp)
                .size(36.dp)
                .clip(CircleShape)
                .border(
                    width = 1.dp,
                    color =
                        CapsuleMiniOutline,
                    shape = CircleShape,
                )
                .clickable(
                    onClick = onClick,
                ),
        contentAlignment =
            Alignment.Center,
    ) {
        Icon(
            painter =
                painterResource(
                    if (selected) {
                        selectedIcon
                    } else {
                        normalIcon
                    },
                ),
            contentDescription = null,
            tint =
                if (selected) {
                    CapsuleMiniText
                } else {
                    CapsuleMiniSecondaryText
                },
            modifier =
                Modifier.size(18.dp),
        )
    }
}
