package com.nikhil.yt.ui.screens.settings

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nikhil.yt.constants.DeezerCookieKey
import com.nikhil.yt.lyrics.deezerArlFromInput
import com.nikhil.yt.utils.rememberPreference

private const val DEEZER_LOGIN_URL = "https://www.deezer.com/login"
private const val DEEZER_LOGIN_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/135.0.0.0 Mobile Safari/537.36"

private val DeezerCookieUrls =
    listOf(
        "https://www.deezer.com",
        "https://deezer.com",
        "https://auth.deezer.com",
        "https://connect.deezer.com",
    )

private val DeezerCookieCaptureDelaysMs =
    listOf(0L, 250L, 750L, 1_500L, 3_000L, 5_000L)

@Composable
@SuppressLint("SetJavaScriptEnabled")
internal fun DeezerLyricsLoginDialog(onDismiss: () -> Unit) {
    var deezerCookie by rememberPreference(DeezerCookieKey, "")
    var webView by remember { mutableStateOf<WebView?>(null) }
    var saved by remember { mutableStateOf(deezerArlFromInput(deezerCookie) != null) }
    val handler = remember { Handler(Looper.getMainLooper()) }

    fun captureNow() {
        val merged =
            DeezerCookieUrls
                .mapNotNull { CookieManager.getInstance().getCookie(it) }
                .distinct()
                .joinToString("; ")
                .takeIf { deezerArlFromInput(it) != null }
                ?: return
        deezerCookie = merged
        saved = true
    }

    fun finish() {
        captureNow()
        onDismiss()
    }

    BackHandler(onBack = ::finish)

    DisposableEffect(Unit) {
        onDispose {
            handler.removeCallbacksAndMessages(null)
            runCatching {
                webView?.stopLoading()
                webView?.destroy()
            }
        }
    }

    Dialog(
        onDismissRequest = ::finish,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        webView = this
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.userAgentString = DEEZER_LOGIN_USER_AGENT
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient =
                            object : WebViewClient() {
                                override fun onPageFinished(
                                    view: WebView,
                                    url: String?,
                                ) {
                                    super.onPageFinished(view, url)
                                    CookieManager.getInstance().flush()
                                    DeezerCookieCaptureDelaysMs.forEach { delay ->
                                        handler.postDelayed(::captureNow, delay)
                                    }
                                }
                            }
                        loadUrl(DEEZER_LOGIN_URL)
                    }
                },
                update = {},
            )

            Surface(
                modifier =
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth(),
                tonalElevation = 6.dp,
            ) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 12.dp),
                ) {
                    Text(
                        text = "Deezer login",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.align(Alignment.CenterStart),
                    )
                    TextButton(
                        onClick = ::finish,
                        modifier = Modifier.align(Alignment.CenterEnd),
                    ) {
                        Text("Done")
                    }
                }
            }

            AnimatedVisibility(
                visible = saved,
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 28.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    tonalElevation = 6.dp,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Text(
                        text = "Deezer connected",
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }
    }
}
