/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */

package com.nikhil.yt

import android.app.Application
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import android.widget.Toast.LENGTH_SHORT
import androidx.datastore.preferences.core.edit
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.request.CachePolicy
import coil3.request.allowHardware
import coil3.request.crossfade
import com.nikhil.yt.constants.*
import com.nikhil.yt.extensions.*
import com.nikhil.yt.ui.screens.settings.ThemePalettes
import com.nikhil.yt.ui.theme.ThemeSeedPalette
import com.nikhil.yt.ui.theme.ThemeSeedPaletteCodec
import com.nikhil.yt.utils.dataStore
import com.nikhil.yt.utils.PreferenceStore
import com.nikhil.yt.utils.DebugLoggingController
import com.nikhil.yt.utils.get
import com.nikhil.yt.utils.reportException
import com.nikhil.yt.innertube.CapsuleAnonymousSession
import com.nikhil.yt.innertube.YouTube
import com.nikhil.yt.innertube.models.YouTubeLocale
import com.nikhil.yt.kugou.KuGou
import com.nikhil.yt.lastfm.LastFM
import com.nikhil.yt.playback.audio.potoken.PoTokenGenerator
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.content.Intent
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.system.exitProcess
import timber.log.Timber
import java.net.Proxy
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

@HiltAndroidApp
class App : Application(), SingletonImageLoader.Factory {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val startupPoTokenGenerator by lazy { PoTokenGenerator(this) }
    @Volatile private var isInitialized = false
    private val didRunImageCacheTrim = AtomicBoolean(false)

    private fun currentProcessName(): String? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            val pid = android.os.Process.myPid()
            val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            activityManager?.runningAppProcesses
                ?.firstOrNull { it.pid == pid }
                ?.processName
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        val processName = currentProcessName()
        if (processName?.endsWith(":crash") == true) {
            return
        }

        /*
         * The VIDEO extractor is intentionally a minimal process. Do not start
         * account DataStore observers, YouTube auth, Last.fm, image cache,
         * presence or any other normal-app subsystem here. NewPipe gets only a
         * credential-free downloader from CapsuleVideoExtractorProvider.
         */
        if (processName?.endsWith(":capsule_video") == true) {
            return
        }

        PreferenceStore.start(this)
        applicationScope.launch(Dispatchers.IO) {
            dataStore.data
                .map { it[DebugLoggingEnabledKey] ?: false }
                .distinctUntilChanged()
                .collect(DebugLoggingController::setEnabled)
        }

        initializeCriticalSync()
        initializeDeferredAsync()
    }

    private fun initializeCriticalSync() {
        val locale = Locale.getDefault()
        val languageTag = locale.toLanguageTag().replace("-Hant", "")
        YouTube.locale = YouTubeLocale(
            gl = locale.country.takeIf { it in CountryCodeToName } ?: "US",
            hl = locale.language.takeIf { it in LanguageCodeToName }
                ?: languageTag.takeIf { it in LanguageCodeToName }
                ?: "en"
        )
        if (languageTag == "zh-TW") {
            KuGou.useTraditionalChinese = true
        }
        LastFM.initialize(
            apiKey = BuildConfig.LASTFM_API_KEY,
            secret = BuildConfig.LASTFM_SECRET
        )
    }

    private fun initializeDeferredAsync() {
        applicationScope.launch(Dispatchers.IO) {
            try {
                val prefs = dataStore.data.first()

                prefs[ContentCountryKey]?.takeIf { it != SYSTEM_DEFAULT }?.let { country ->
                    YouTube.locale = YouTube.locale.copy(gl = country)
                }
                prefs[ContentLanguageKey]?.takeIf { it != SYSTEM_DEFAULT }?.let { lang ->
                    YouTube.locale = YouTube.locale.copy(hl = lang)
                }

                LastFM.sessionKey = prefs[LastFMSessionKey]

                /*
                 * Read the PoToken mode in the first startup snapshot instead
                 * of waiting for a separate Flow collector. That removes the
                 * race where the first AUDIO resolve could reach WEB_REMIX
                 * before startup knew it should build the BotGuard session.
                 */
                YouTube.webClientPoTokenEnabled = prefs[WebClientPoTokenEnabledKey] ?: false

                /*
                 * Hardware audio offload lets supported devices keep decoding
                 * and playback out of the main CPU path while the screen is
                 * off. Respect an explicit user choice, but make the efficient
                 * path the default for installs that never chose a value.
                 */
                if (prefs[AudioOffload] == null) {
                    dataStore.edit { settings ->
                        settings[AudioOffload] = true
                    }
                }

                if (prefs[ProxyEnabledKey] == true) {
                    try {
                        YouTube.proxy = Proxy(
                            prefs[ProxyTypeKey].toEnum(defaultValue = Proxy.Type.HTTP),
                            prefs[ProxyUrlKey]!!.toInetSocketAddress()
                        )
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@App, "Failed to parse proxy url.", LENGTH_SHORT).show()
                        }
                        reportException(e)
                    }
                    YouTube.streamBypassProxy = prefs[StreamBypassProxyKey] == true
                }

                if (prefs[UseLoginForBrowse] != false) {
                    YouTube.useLoginForBrowse = true
                }

                if (prefs[RandomThemeOnStartupKey] == true) {
                    val randomPalette = ThemePalettes.generateRandomPalette()
                    val seedPalette = ThemeSeedPalette(
                        primary = randomPalette.primary,
                        secondary = randomPalette.secondary,
                        tertiary = randomPalette.tertiary,
                        neutral = randomPalette.neutral
                    )
                    val encodedPalette = ThemeSeedPaletteCodec.encodeForPreference(seedPalette, "Random")
                    dataStore.edit { settings ->
                        settings[CustomThemeColorKey] = encodedPalette
                    }
                }

                isInitialized = true
            } catch (e: Exception) {
                Timber.e(e, "Error during deferred initialization")
                reportException(e)
            }
        }

        applicationScope.launch(Dispatchers.IO) {
            dataStore.data
                .map { it[VisitorDataKey] }
                .distinctUntilChanged()
                .collect { visitorData ->
                    val resolvedVisitorData =
                        visitorData
                            ?.trim()
                            ?.takeIf { it.isNotBlank() && it != "null" }
                            ?: YouTube.visitorData().onFailure {
                                reportException(it)
                            }.getOrNull()?.also { newVisitorData ->
                                dataStore.edit { settings ->
                                    settings[VisitorDataKey] = newVisitorData
                                }
                            }

                    YouTube.visitorData = resolvedVisitorData

                    /*
                     * Prewarm immediately in the same visitor-data pipeline.
                     * On a fresh install this starts as soon as visitorData is
                     * fetched; on an existing install it starts from the first
                     * DataStore emission. PoTokenGenerator shares state across
                     * instances, so playback joins this exact initialization.
                     */
                    if (YouTube.webClientPoTokenEnabled && resolvedVisitorData != null) {
                        startupPoTokenGenerator.prewarm(resolvedVisitorData)
                    }
                }
        }

        try {
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                try {
                    val sw = StringWriter()
                    val pw = PrintWriter(sw)
                    throwable.printStackTrace(pw)
                    val stack = sw.toString()

                    val intent = Intent(this@App, DebugActivity::class.java).apply {
                        putExtra(DebugActivity.EXTRA_STACK_TRACE, stack)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    }
                    startActivity(intent)
                    try { Thread.sleep(100) } catch (_: InterruptedException) {}
                } catch (e: Exception) {
                    reportException(e)
                } finally {
                    android.os.Process.killProcess(android.os.Process.myPid())
                    exitProcess(2)
                }
            }
        } catch (e: Exception) {
            reportException(e)
        }
        applicationScope.launch(Dispatchers.IO) {
            dataStore.data
                .map { it[DataSyncIdKey] }
                .distinctUntilChanged()
                .collect { dataSyncId ->
                    YouTube.dataSyncId = dataSyncId?.let {
                        it.takeIf { !it.contains("||") }
                            ?: it.takeIf { it.endsWith("||") }?.substringBefore("||")
                            ?: it.substringAfter("||")
                    }
                }
        }
        applicationScope.launch(Dispatchers.IO) {
            dataStore.data
                .map { it[InnerTubeCookieKey] }
                .distinctUntilChanged()
                .collect { cookie ->
                    try {
                        YouTube.cookie = cookie
                    } catch (e: Exception) {
                        Timber.e("Could not parse cookie. Clearing existing cookie. %s", e.message)
                        forgetAccount(this@App)
                    }
                }
        }
        applicationScope.launch(Dispatchers.IO) {
            dataStore.data
                .map { it[PoTokenKey] }
                .distinctUntilChanged()
                .collect { token ->
                    YouTube.poToken = token?.takeIf { it.isNotBlank() }
                }
        }
        applicationScope.launch(Dispatchers.IO) {
            dataStore.data
                .map { it[PoTokenGvsKey] }
                .distinctUntilChanged()
                .collect { token ->
                    YouTube.poTokenGvs = token?.takeIf { it.isNotBlank() }
                }
        }
        applicationScope.launch(Dispatchers.IO) {
            dataStore.data
                .map { it[PoTokenPlayerKey] }
                .distinctUntilChanged()
                .collect { token ->
                    YouTube.poTokenPlayer = token?.takeIf { it.isNotBlank() }
                }
        }
        applicationScope.launch(Dispatchers.IO) {
            dataStore.data
                .map { it[WebClientPoTokenEnabledKey] ?: false }
                .distinctUntilChanged()
                .collect { enabled ->
                    YouTube.webClientPoTokenEnabled = enabled
                    CapsuleAnonymousSession.reset()

                    /*
                     * If the enable flag wins the startup race after visitorData
                     * has already arrived, start the same shared prewarm here.
                     * This makes both ordering possibilities deterministic.
                     */
                    if (enabled) {
                        YouTube.visitorData
                            ?.trim()
                            ?.takeIf { it.isNotBlank() && it != "null" }
                            ?.let { startupPoTokenGenerator.prewarm(it) }
                    }
                }
        }

        applicationScope.launch(Dispatchers.IO) {
            dataStore.data
                .map { it[LastFMSessionKey] }
                .distinctUntilChanged()
                .collect { sessionKey ->
                    LastFM.sessionKey = sessionKey
                }
        }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        val smartTrimmer = dataStore[SmartTrimmerKey] ?: false
        val imageCacheConfig = resolveImageDiskCacheConfig(dataStore[MaxImageCacheSizeKey])

        val diskCache = DiskCache.Builder()
            .directory(cacheDir.resolve("coil"))
            .maxSizeBytes(imageCacheConfig.maxSizeBytes)
            .build()

        if (smartTrimmer && imageCacheConfig.policy == CachePolicy.ENABLED && didRunImageCacheTrim.compareAndSet(false, true)) {
            applicationScope.launch(Dispatchers.IO) { trimImageDiskCache(diskCache) }
        }

        return ImageLoader.Builder(this)
            .crossfade(true)
            .allowHardware(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            .diskCache(diskCache)
            .diskCachePolicy(imageCacheConfig.policy)
            .build()
    }

    private fun trimImageDiskCache(diskCache: DiskCache) {
        try {
            val limitBytes = diskCache.maxSize
            if (limitBytes <= 0L || limitBytes == Long.MAX_VALUE) return

            val dir = java.io.File(diskCache.directory.toString())
            if (!dir.exists()) return

            val files = dir.walkTopDown().filter { it.isFile }.sortedBy { it.lastModified() }.toList()
            var currentSize = files.sumOf { it.length() }
            if (currentSize <= limitBytes) return

            for (file in files) {
                if (currentSize <= limitBytes) break
                val size = file.length()
                if (runCatching { file.delete() }.getOrDefault(false)) currentSize -= size
            }
        } catch (_: Exception) {
        }
    }

    companion object {
        lateinit var instance: App
            private set

        fun forgetAccount(context: Context) {
            CoroutineScope(Dispatchers.IO).launch {
                context.dataStore.edit { settings ->
                    settings.remove(InnerTubeCookieKey)
                    settings.remove(PoTokenKey)
                    settings.remove(VisitorDataKey)
                    settings.remove(DataSyncIdKey)
                    settings.remove(AccountNameKey)
                    settings.remove(AccountEmailKey)
                    settings.remove(AccountChannelHandleKey)
                }
            }
        }
    }
}

internal data class ImageDiskCacheConfig(
    val policy: CachePolicy,
    val maxSizeBytes: Long,
)

internal fun resolveImageDiskCacheConfig(maxImageCacheSizeMb: Int?): ImageDiskCacheConfig {
    val sizeMb = maxImageCacheSizeMb ?: 512
    if (sizeMb == 0) return ImageDiskCacheConfig(policy = CachePolicy.DISABLED, maxSizeBytes = 1L)
    if (sizeMb < 0) return ImageDiskCacheConfig(policy = CachePolicy.ENABLED, maxSizeBytes = Long.MAX_VALUE)
    val bytesPerMb = 1024L * 1024L
    val safeSizeMb = sizeMb.toLong().coerceAtMost(Long.MAX_VALUE / bytesPerMb)
    return ImageDiskCacheConfig(policy = CachePolicy.ENABLED, maxSizeBytes = safeSizeMb * bytesPerMb)
}
