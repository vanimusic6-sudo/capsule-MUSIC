from pathlib import Path


ROOT = Path('.')


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise RuntimeError(f'missing patch anchor: {label}')
    return text.replace(old, new, 1)


# ---- Artist screen -------------------------------------------------------
artist_path = ROOT / 'app/src/main/kotlin/com/nikhil/yt/ui/screens/artist/ArtistScreen.kt'
artist = artist_path.read_text()

artist = replace_once(
    artist,
    'import androidx.compose.foundation.background\nimport androidx.compose.foundation.combinedClickable',
    'import androidx.compose.foundation.background\nimport androidx.compose.foundation.border\nimport androidx.compose.foundation.clickable\nimport androidx.compose.foundation.combinedClickable',
    'artist foundation imports',
)
artist = replace_once(
    artist,
    'import androidx.compose.foundation.layout.Arrangement\nimport androidx.compose.foundation.layout.Box',
    'import androidx.compose.foundation.layout.Arrangement\nimport androidx.compose.foundation.layout.aspectRatio\nimport androidx.compose.foundation.layout.Box',
    'artist aspect ratio import',
)
artist = replace_once(
    artist,
    'import androidx.compose.foundation.layout.WindowInsets\nimport androidx.compose.foundation.layout.asPaddingValues',
    'import androidx.compose.foundation.layout.WindowInsets\nimport androidx.compose.foundation.layout.WindowInsetsSides\nimport androidx.compose.foundation.layout.asPaddingValues',
    'artist window inset side import',
)
artist = replace_once(
    artist,
    'import androidx.compose.foundation.layout.height\nimport androidx.compose.foundation.layout.padding',
    'import androidx.compose.foundation.layout.height\nimport androidx.compose.foundation.layout.matchParentSize\nimport androidx.compose.foundation.layout.only\nimport androidx.compose.foundation.layout.padding',
    'artist layout imports',
)
artist = replace_once(
    artist,
    'import androidx.compose.ui.unit.dp\n',
    'import androidx.compose.ui.unit.dp\nimport androidx.compose.ui.unit.sp\n',
    'artist sp import',
)
artist = replace_once(
    artist,
    'import com.nikhil.yt.ui.component.AlbumGridItem\n',
    'import com.nikhil.yt.ui.component.AlbumGridItem\nimport com.nikhil.yt.ui.component.ArtworkGradientBackdrop\n',
    'artist gradient component import',
)

mesh_start = artist.index('        // Mesh gradient background layer')
mesh_end = artist.index('        LazyColumn(', mesh_start)
artist = (
    artist[:mesh_start]
    + '''        // Capsule artwork glow: one restrained palette fade, shared with albums.\n        ArtworkGradientBackdrop(\n            colors = gradientColors,\n            surfaceColor = surfaceColor,\n            alpha = gradientAlpha,\n            modifier = Modifier.fillMaxSize().zIndex(-1f),\n        )\n\n'''
    + artist[mesh_end:]
)

artist = replace_once(
    artist,
    '            contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues(),',
    '''            contentPadding =\n                LocalPlayerAwareWindowInsets.current\n                    .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)\n                    .asPaddingValues(),''',
    'artist edge-to-edge list insets',
)

hero_start = artist.index('                // Hero Header\n                item(key = "header") {')
hero_end = artist.index('\n\n                // Content sections', hero_start)
new_hero = '''                // Hero Header: edge-to-edge square artwork with Capsule controls.\n                item(key = "header") {\n                    val artistName = artistPage?.artist?.title ?: libraryArtist?.artist?.name\n                    val heroAccent =\n                        gradientColors.firstOrNull() ?: MaterialTheme.colorScheme.surfaceVariant\n                    val isSubscribed = libraryArtist?.artist?.bookmarkedAt != null\n\n                    Column(\n                        modifier = Modifier.fillMaxWidth(),\n                        horizontalAlignment = Alignment.Start,\n                    ) {\n                        Box(\n                            modifier =\n                                Modifier\n                                    .fillMaxWidth()\n                                    .aspectRatio(1f),\n                        ) {\n                            if (thumbnail != null) {\n                                AsyncImage(\n                                    model = thumbnail.resize(1200, 1200),\n                                    contentDescription = null,\n                                    contentScale = ContentScale.Crop,\n                                    modifier = Modifier.fillMaxSize(),\n                                )\n                            } else {\n                                Box(\n                                    modifier =\n                                        Modifier\n                                            .fillMaxSize()\n                                            .background(MaterialTheme.colorScheme.surfaceVariant),\n                                    contentAlignment = Alignment.Center,\n                                ) {\n                                    Icon(\n                                        painter = painterResource(R.drawable.person),\n                                        contentDescription = null,\n                                        modifier = Modifier.size(96.dp),\n                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,\n                                    )\n                                }\n                            }\n\n                            // The artwork melts into the page instead of ending as a hard card edge.\n                            Box(\n                                modifier =\n                                    Modifier\n                                        .matchParentSize()\n                                        .background(\n                                            Brush.verticalGradient(\n                                                colorStops =\n                                                    arrayOf(\n                                                        0.00f to Color.Transparent,\n                                                        0.54f to Color.Transparent,\n                                                        0.72f to heroAccent.copy(alpha = 0.10f),\n                                                        0.86f to surfaceColor.copy(alpha = 0.64f),\n                                                        1.00f to surfaceColor,\n                                                    ),\n                                            ),\n                                        ),\n                            )\n                        }\n\n                        Text(\n                            text = artistName ?: stringResource(R.string.unknown_artist),\n                            style = MaterialTheme.typography.headlineLarge.copy(fontSize = 38.sp),\n                            fontWeight = FontWeight.Bold,\n                            textAlign = TextAlign.Start,\n                            maxLines = 2,\n                            overflow = TextOverflow.Ellipsis,\n                            modifier =\n                                Modifier\n                                    .fillMaxWidth()\n                                    .padding(horizontal = 24.dp),\n                        )\n\n                        Spacer(Modifier.height(18.dp))\n\n                        Row(\n                            modifier =\n                                Modifier\n                                    .fillMaxWidth()\n                                    .padding(horizontal = 24.dp),\n                            horizontalArrangement = Arrangement.spacedBy(10.dp),\n                        ) {\n                            CapsuleArtistActionButton(\n                                icon = if (isSubscribed) R.drawable.done else R.drawable.add,\n                                label =\n                                    stringResource(\n                                        if (isSubscribed) R.string.subscribed else R.string.subscribe,\n                                    ),\n                                onClick = {\n                                    database.transaction {\n                                        val artist = libraryArtist?.artist\n                                        if (artist != null) {\n                                            update(artist.toggleLike())\n                                        } else {\n                                            artistPage?.artist?.let { remoteArtist ->\n                                                insert(\n                                                    ArtistEntity(\n                                                        id = remoteArtist.id,\n                                                        name = remoteArtist.title,\n                                                        channelId = remoteArtist.channelId,\n                                                        thumbnailUrl = remoteArtist.thumbnail,\n                                                    ).toggleLike(),\n                                                )\n                                            }\n                                        }\n                                    }\n                                },\n                                modifier = Modifier.weight(1f).height(52.dp),\n                            )\n\n                            CapsuleArtistActionButton(\n                                icon = R.drawable.shuffle,\n                                label = stringResource(R.string.shuffle),\n                                enabled =\n                                    if (showLocal) {\n                                        librarySongs.isNotEmpty()\n                                    } else {\n                                        artistPage?.artist?.shuffleEndpoint != null\n                                    },\n                                onClick = {\n                                    if (!showLocal) {\n                                        artistPage?.artist?.shuffleEndpoint?.let { shuffleEndpoint ->\n                                            playerConnection.playQueue(YouTubeQueue(shuffleEndpoint))\n                                        }\n                                    } else if (librarySongs.isNotEmpty()) {\n                                        playerConnection.playQueue(\n                                            ListQueue(\n                                                title =\n                                                    libraryArtist?.artist?.name\n                                                        ?: "Unknown Artist",\n                                                items = librarySongs.shuffled().map { it.toMediaItem() },\n                                            ),\n                                        )\n                                    }\n                                },\n                                modifier = Modifier.weight(1f).height(52.dp),\n                            )\n                        }\n\n                        if (!showLocal) {\n                            artistPage?.artist?.radioEndpoint?.let { radioEndpoint ->\n                                Spacer(Modifier.height(10.dp))\n                                CapsuleArtistActionButton(\n                                    icon = R.drawable.radio,\n                                    label = stringResource(R.string.radio),\n                                    onClick = {\n                                        playerConnection.playQueue(YouTubeQueue(radioEndpoint))\n                                    },\n                                    modifier =\n                                        Modifier\n                                            .fillMaxWidth()\n                                            .padding(horizontal = 24.dp)\n                                            .height(50.dp),\n                                )\n                            }\n                        }\n\n                        Spacer(Modifier.height(22.dp))\n                    }\n                }'''
artist = artist[:hero_start] + new_hero + artist[hero_end:]

artist = replace_once(
    artist,
    '            TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)',
    '''            TopAppBarDefaults.topAppBarColors(\n                containerColor = Color.Transparent,\n                scrolledContainerColor = Color.Transparent,\n                navigationIconContentColor = Color.White,\n                actionIconContentColor = Color.White,\n                titleContentColor = Color.White,\n            )''',
    'artist transparent top bar chrome',
)

helper_anchor = '/**\n * Stat item component for displaying statistics like subscriber count, songs, albums\n */'
helper = '''@Composable\nprivate fun CapsuleArtistActionButton(\n    icon: Int,\n    label: String,\n    onClick: () -> Unit,\n    modifier: Modifier = Modifier,\n    enabled: Boolean = true,\n) {\n    val textColor = MaterialTheme.colorScheme.onSurface\n    val shape = RoundedCornerShape(18.dp)\n    val panelBrush =\n        Brush.verticalGradient(\n            listOf(\n                textColor.copy(alpha = 0.035f),\n                textColor.copy(alpha = 0.012f),\n            ),\n        )\n\n    Row(\n        modifier =\n            modifier\n                .clip(shape)\n                .background(panelBrush)\n                .border(1.dp, textColor.copy(alpha = 0.14f), shape)\n                .clickable(enabled = enabled, onClick = onClick)\n                .alpha(if (enabled) 1f else 0.34f)\n                .padding(horizontal = 14.dp),\n        horizontalArrangement = Arrangement.Center,\n        verticalAlignment = Alignment.CenterVertically,\n    ) {\n        Icon(\n            painter = painterResource(icon),\n            contentDescription = null,\n            tint = textColor.copy(alpha = 0.96f),\n            modifier = Modifier.size(21.dp),\n        )\n        Spacer(Modifier.width(9.dp))\n        Text(\n            text = label,\n            style = MaterialTheme.typography.labelLarge,\n            fontWeight = FontWeight.Medium,\n            color = textColor.copy(alpha = 0.96f),\n            maxLines = 1,\n            overflow = TextOverflow.Ellipsis,\n        )\n    }\n}\n\n'''
artist = replace_once(artist, helper_anchor, helper + helper_anchor, 'artist capsule action helper')
artist_path.write_text(artist)


# ---- Album layout -------------------------------------------------------
layout_path = ROOT / 'app/src/main/kotlin/com/nikhil/yt/ui/component/AlbumLayout.kt'
layout = layout_path.read_text()
layout = replace_once(
    layout,
    '''internal fun AlbumScreenLayout(\n    background: Color,\n    state: LazyListState,''',
    '''internal fun AlbumScreenLayout(\n    background: Color,\n    gradientColors: List<Color> = emptyList(),\n    state: LazyListState,''',
    'album layout gradient argument',
)
layout = replace_once(
    layout,
    '''    Box(modifier.fillMaxSize().background(background)) {\n        LazyColumn(''',
    '''    Box(modifier.fillMaxSize().background(background)) {\n        ArtworkGradientBackdrop(\n            colors = gradientColors,\n            surfaceColor = background,\n            modifier = Modifier.fillMaxSize(),\n        )\n        LazyColumn(''',
    'album gradient backdrop',
)
layout_path.write_text(layout)


# ---- Album artwork ------------------------------------------------------
artwork_path = ROOT / 'app/src/main/kotlin/com/nikhil/yt/ui/component/AlbumArtwork.kt'
artwork = artwork_path.read_text()
artwork = replace_once(
    artwork,
    'fun AlbumArtwork(thumbnailUrl: String?, background: Color, modifier: Modifier = Modifier) {',
    '''fun AlbumArtwork(\n    thumbnailUrl: String?,\n    background: Color,\n    modifier: Modifier = Modifier,\n    fadeColor: Color = background,\n) {''',
    'album artwork fade argument',
)
artwork = replace_once(
    artwork,
    '    AlbumArtworkLayers(painter, blurred, background, modifier, imageModifier = Modifier.then(sizeResolver))',
    '''    AlbumArtworkLayers(\n        painter = painter,\n        blurred = blurred,\n        background = background,\n        modifier = modifier,\n        imageModifier = Modifier.then(sizeResolver),\n        fadeColor = fadeColor,\n    )''',
    'album artwork fade forwarding',
)
artwork = replace_once(
    artwork,
    '''    modifier: Modifier = Modifier,\n    imageModifier: Modifier = Modifier,\n) {''',
    '''    modifier: Modifier = Modifier,\n    imageModifier: Modifier = Modifier,\n    fadeColor: Color = background,\n) {''',
    'album artwork layers fade argument',
)
artwork = replace_once(
    artwork,
    '''                Brush.verticalGradient(\n                    0f to background, 0.32f to Color.Transparent,\n                    0.68f to Color.Transparent, 1f to background,\n                ),''',
    '''                Brush.verticalGradient(\n                    0f to background,\n                    0.30f to Color.Transparent,\n                    0.64f to Color.Transparent,\n                    0.82f to fadeColor.copy(alpha = 0.34f),\n                    1f to background,\n                ),''',
    'album artwork gradient fade',
)
artwork_path.write_text(artwork)


# ---- Album screen -------------------------------------------------------
album_path = ROOT / 'app/src/main/kotlin/com/nikhil/yt/ui/screens/AlbumScreen.kt'
album = album_path.read_text()
album = replace_once(
    album,
    'import com.nikhil.yt.ui.component.AlbumArtwork\n',
    'import com.nikhil.yt.ui.component.AlbumArtwork\nimport com.nikhil.yt.ui.component.rememberArtworkGradientColors\n',
    'album gradient helper import',
)
album = replace_once(
    album,
    '    val surfaceColor = if (StandardChrome.isDark) Color(0xFF090909) else StandardChrome.background\n',
    '''    val surfaceColor = if (StandardChrome.isDark) Color(0xFF090909) else StandardChrome.background\n    val albumGradientColors =\n        rememberArtworkGradientColors(\n            thumbnailUrl = albumWithSongs?.album?.thumbnailUrl,\n            fallbackColor = surfaceColor,\n        )\n''',
    'album palette state',
)
album = replace_once(
    album,
    '''    AlbumScreenLayout(\n        background = surfaceColor,\n        state = lazyListState,''',
    '''    AlbumScreenLayout(\n        background = surfaceColor,\n        gradientColors = albumGradientColors,\n        state = lazyListState,''',
    'album layout palette',
)
album = replace_once(
    album,
    '''                            AlbumArtwork(\n                                thumbnailUrl = albumWithSongs.album.thumbnailUrl,\n                                background = surfaceColor,\n                            )''',
    '''                            AlbumArtwork(\n                                thumbnailUrl = albumWithSongs.album.thumbnailUrl,\n                                background = surfaceColor,\n                                fadeColor = albumGradientColors.firstOrNull() ?: surfaceColor,\n                            )''',
    'album artwork palette fade',
)
album_path.write_text(album)

print('artist/album Capsule redesign applied')
