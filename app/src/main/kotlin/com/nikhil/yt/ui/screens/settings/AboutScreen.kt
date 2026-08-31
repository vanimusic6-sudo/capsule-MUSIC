/*
 * Capsule MUSIC
 * Based on Velune by Nikhil
 * Licensed under GPL-3.0.
 */

package com.nikhil.yt.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.nikhil.yt.BuildConfig
import com.nikhil.yt.LocalPlayerAwareWindowInsets
import com.nikhil.yt.R
import com.nikhil.yt.playback.video.CapsuleVideoEngineStatus
import com.nikhil.yt.playback.video.CapsuleVideoEngineUpdater
import com.nikhil.yt.ui.component.IconButton
import com.nikhil.yt.ui.utils.backToMain
import com.nikhil.yt.utils.Updater
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

private fun normalizeAboutVersion(value: String): String =
    value
        .removePrefix("Capsule ")
        .removePrefix("capsule ")
        .removePrefix("v")
        .trim()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val coroutineScope = rememberCoroutineScope()

    val currentVersion =
        remember {
            normalizeAboutVersion(BuildConfig.VERSION_NAME)
        }

    var latestVersion by remember { mutableStateOf<String?>(null) }
    var latestReleaseUrl by remember { mutableStateOf<String?>(null) }
    var updateMessage by remember { mutableStateOf("Checking for updates…") }
    var isCheckingUpdate by remember { mutableStateOf(false) }

    var engineStatus by remember { mutableStateOf<CapsuleVideoEngineStatus?>(null) }
    var engineMessage by remember {
        mutableStateOf(
            "Bundled NewPipeExtractor ${CapsuleVideoEngineUpdater.BUNDLED_EXTRACTOR_VERSION}",
        )
    }
    var isCheckingEngine by remember { mutableStateOf(false) }

    val hasUpdate =
        latestVersion
            ?.takeIf { it.isNotBlank() }
            ?.let { normalizeAboutVersion(it) != currentVersion }
            ?: false

    fun checkForUpdates(forceRefresh: Boolean) {
        coroutineScope.launch {
            isCheckingUpdate = true
            updateMessage = "Checking vanimusic6-sudo/capsule-MUSIC…"

            Updater.getAllReleases(forceRefresh = forceRefresh)
                .onSuccess { releases ->
                    /*
                     * VIDEO-engine builds are intentionally published as
                     * prereleases. Never mistake them for normal Capsule app
                     * releases in the main updater.
                     */
                    val release =
                        releases.firstOrNull { candidate ->
                            !CapsuleVideoEngineUpdater.isVideoEngineReleaseTag(
                                candidate.tagName,
                            )
                        }

                    if (release == null) {
                        latestVersion = null
                        latestReleaseUrl = null
                        updateMessage = "No published capsule release found yet."
                    } else {
                        val resolvedLatest =
                            normalizeAboutVersion(
                                release.name.ifBlank { release.tagName },
                            )

                        latestVersion = resolvedLatest
                        latestReleaseUrl = release.htmlUrl
                        updateMessage =
                            if (resolvedLatest != currentVersion) {
                                "New version available: $resolvedLatest"
                            } else {
                                "You already have the latest version."
                            }
                    }
                }
                .onFailure { error ->
                    updateMessage =
                        error.message ?: "Could not check for updates."
                }

            isCheckingUpdate = false
        }
    }

    fun checkVideoEngine(forceRefresh: Boolean) {
        coroutineScope.launch {
            isCheckingEngine = true
            engineMessage = "Checking official NewPipeExtractor releases…"

            CapsuleVideoEngineUpdater.check(forceRefresh = forceRefresh)
                .onSuccess { status ->
                    engineStatus = status

                    engineMessage =
                        when {
                            !status.updateAvailable ->
                                "NewPipeExtractor ${status.bundledVersion} • latest stable"

                            status.readyToInstall ->
                                "NewPipeExtractor ${status.upstreamVersion} is ready as a signed Capsule VIDEO build"

                            else ->
                                "NewPipeExtractor ${status.upstreamVersion} found • Capsule CI is preparing the signed build"
                        }
                }
                .onFailure { error ->
                    engineMessage =
                        error.message
                            ?: "Could not check the VIDEO engine right now."
                }

            isCheckingEngine = false
        }
    }

    LaunchedEffect(Unit) {
        checkForUpdates(forceRefresh = false)
        checkVideoEngine(forceRefresh = false)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about)) },
                navigationIcon = {
                    IconButton(
                        onClick = navController::navigateUp,
                        onLongClick = navController::backToMain,
                    ) {
                        Icon(
                            painterResource(R.drawable.arrow_back),
                            contentDescription = null,
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp)
                    .windowInsetsPadding(
                        LocalPlayerAwareWindowInsets.current.only(
                            WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
                        ),
                    ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .background(
                                MaterialTheme.colorScheme.surfaceContainerHighest.copy(
                                    alpha = 0.3f,
                                ),
                            )
                            .padding(vertical = 30.dp, horizontal = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier =
                            Modifier
                                .size(78.dp)
                                .clip(CircleShape)
                                .background(
                                    MaterialTheme.colorScheme.surfaceContainerHigh.copy(
                                        alpha = 0.55f,
                                    ),
                                ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_velune_concept),
                            contentDescription = "capsule",
                            modifier = Modifier.size(68.dp),
                            tint = Color.Unspecified,
                        )
                    }

                    Spacer(Modifier.height(14.dp))

                    Text(
                        text = "capsule",
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp,
                    )

                    Spacer(Modifier.height(14.dp))

                    Row(
                        modifier =
                            Modifier
                                .border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                    shape = CircleShape,
                                )
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.info),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text =
                                "v${BuildConfig.VERSION_NAME.trim()} • " +
                                    if (BuildConfig.DEBUG) "DEBUG" else "STABLE",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }

                Spacer(Modifier.height(24.dp))
            }

            item {
                SectionTitle("UPDATES")
                Spacer(Modifier.height(8.dp))

                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerLow)
                            .padding(16.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier =
                                Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .background(
                                        MaterialTheme.colorScheme.primary.copy(
                                            alpha = 0.12f,
                                        ),
                                    ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.update),
                                contentDescription = null,
                                modifier = Modifier.size(22.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }

                        Spacer(Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text =
                                    if (hasUpdate) {
                                        "Update available"
                                    } else {
                                        "capsule updates"
                                    },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = updateMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    Spacer(Modifier.height(14.dp))

                    Button(
                        onClick = { checkForUpdates(forceRefresh = true) },
                        enabled = !isCheckingUpdate,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (isCheckingUpdate) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(10.dp))
                            Text("Checking…")
                        } else {
                            Icon(
                                painter = painterResource(R.drawable.update),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Check for updates")
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = {
                            uriHandler.openUri(
                                latestReleaseUrl ?: Updater.getLatestDownloadUrl(),
                            )
                        },
                        enabled = latestVersion != null && !isCheckingUpdate,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.download),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (hasUpdate) {
                                "Download update"
                            } else {
                                "Open latest release APK"
                            },
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerLow)
                            .padding(16.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier =
                                Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .background(
                                        MaterialTheme.colorScheme.primary.copy(
                                            alpha = 0.12f,
                                        ),
                                    ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.integration),
                                contentDescription = null,
                                modifier = Modifier.size(22.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }

                        Spacer(Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "VIDEO engine",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = engineMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    val status = engineStatus
                    Text(
                        text =
                            "Installed extractor: " +
                                CapsuleVideoEngineUpdater.BUNDLED_EXTRACTOR_VERSION,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    status?.let {
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = "Latest upstream: ${it.upstreamVersion}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Spacer(Modifier.height(14.dp))

                    Button(
                        onClick = { checkVideoEngine(forceRefresh = true) },
                        enabled = !isCheckingEngine,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (isCheckingEngine) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(10.dp))
                            Text("Checking…")
                        } else {
                            Icon(
                                painter = painterResource(R.drawable.update),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Check VIDEO engine")
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    val readyVersion =
                        status
                            ?.takeIf { it.readyToInstall }
                            ?.readyBuildVersion

                    OutlinedButton(
                        onClick = {
                            readyVersion?.let { version ->
                                /*
                                 * This is a full Capsule APK signed by the same
                                 * release key, not a runtime-loaded JAR/DEX.
                                 * Android/browser handles the actual download and
                                 * package-install confirmation.
                                 */
                                uriHandler.openUri(
                                    CapsuleVideoEngineUpdater.apkDownloadUrl(
                                        version,
                                    ),
                                )
                            }
                        },
                        enabled =
                            readyVersion != null &&
                                !isCheckingEngine,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.download),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (readyVersion != null) {
                                "Install VIDEO engine $readyVersion"
                            } else if (status?.updateAvailable == true) {
                                "Signed build is being prepared"
                            } else {
                                "Latest VIDEO engine installed"
                            },
                        )
                    }

                    if (
                        status?.updateAvailable == true &&
                        !status.readyToInstall
                    ) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                uriHandler.openUri(
                                    CapsuleVideoEngineUpdater.buildStatusUrl(),
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Open automatic build status")
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))
            }

            item {
                SectionTitle("MAIN DEVELOPER")
                Spacer(Modifier.height(8.dp))
                AboutItemCard(
                    iconUrl = "https://github.com/vanimusic6-sudo.png",
                    title = "vani",
                    subtitle = "Creator & primary developer of capsule",
                    onClick = {
                        uriHandler.openUri(
                            "https://github.com/vanimusic6-sudo",
                        )
                    },
                )
                Spacer(Modifier.height(24.dp))
            }

            item {
                SectionTitle("CODE BASE & INSPIRATION")
                Spacer(Modifier.height(8.dp))

                AboutItemCard(
                    iconUrl = "https://github.com/nikhilvishwakarma00.png",
                    title = "Nikhil — Velune",
                    subtitle =
                        "Original Velune developer • code base & main inspiration",
                    onClick = {
                        uriHandler.openUri(
                            "https://github.com/nikhilvishwakarma00/Velune",
                        )
                    },
                )

                Spacer(Modifier.height(8.dp))

                AboutItemCard(
                    iconUrl =
                        "https://avatars.githubusercontent.com/u/107134739?v=4",
                    title = "ArchiveTune — koiverse",
                    subtitle = "Base framework inspiration",
                    onClick = {
                        uriHandler.openUri(
                            "https://github.com/koiverse/ArchiveTune",
                        )
                    },
                )

                Spacer(Modifier.height(8.dp))

                AboutItemCard(
                    iconUrl =
                        "https://avatars.githubusercontent.com/u/80542861?v=4",
                    title = "MO AGAMY — MetroList",
                    subtitle = "Upstream project inspiration",
                    onClick = {
                        uriHandler.openUri(
                            "https://github.com/mostafaalagamy",
                        )
                    },
                )

                Spacer(Modifier.height(24.dp))
            }

            item {
                SectionTitle("PROJECT")
                Spacer(Modifier.height(8.dp))
                AboutItemCard(
                    iconRes = R.drawable.github,
                    title = "capsule on GitHub",
                    subtitle = "Source code, releases and updates",
                    onClick = {
                        uriHandler.openUri(
                            "https://github.com/vanimusic6-sudo/capsule-MUSIC",
                        )
                    },
                )
                Spacer(Modifier.height(24.dp))
            }

            item {
                SectionTitle("APP INFO")
                Spacer(Modifier.height(8.dp))

                val installDate =
                    try {
                        val packageInfo =
                            context.packageManager.getPackageInfo(
                                context.packageName,
                                0,
                            )
                        DateFormat
                            .getDateInstance(DateFormat.MEDIUM)
                            .format(Date(packageInfo.firstInstallTime))
                    } catch (_: Exception) {
                        "Unknown"
                    }

                AboutItemCard(
                    iconRes = R.drawable.storage,
                    title = "Installed date",
                    subtitle = installDate,
                    onClick = null,
                )
                Spacer(Modifier.height(8.dp))

                AboutItemCard(
                    iconRes = R.drawable.info,
                    title = "Version code",
                    subtitle = "${BuildConfig.VERSION_CODE}",
                    onClick = null,
                )
                Spacer(Modifier.height(8.dp))

                AboutItemCard(
                    iconRes = R.drawable.security,
                    title = "GNU General Public License v3.0",
                    subtitle = "GPL-3.0 • Free Open Source Software",
                    onClick = {
                        uriHandler.openUri(
                            "https://www.gnu.org/licenses/gpl-3.0.html",
                        )
                    },
                )

                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

@Composable
fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelSmall,
        color = Color(0xFFB0956E),
        fontWeight = FontWeight.Bold,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
        letterSpacing = 1.sp,
    )
}

@Composable
fun AboutItemCard(
    iconUrl: String? = null,
    iconRes: Int? = null,
    title: String,
    subtitle: String,
    onClick: (() -> Unit)?,
) {
    val clickableModifier =
        if (onClick != null) {
            Modifier.clickable(onClick = onClick)
        } else {
            Modifier
        }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .then(clickableModifier)
                .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (iconUrl != null) {
            AsyncImage(
                model = iconUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier =
                    Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(
                            MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
            )
        } else if (iconRes != null) {
            Box(
                modifier =
                    Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(
                            MaterialTheme.colorScheme.surfaceContainerHigh.copy(
                                alpha = 0.5f,
                            ),
                        ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(id = iconRes),
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
