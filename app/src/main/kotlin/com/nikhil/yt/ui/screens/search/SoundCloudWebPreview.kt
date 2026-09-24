package com.nikhil.yt.ui.screens.search

import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import com.nikhil.yt.LocalPlayerAwareWindowInsets
import com.nikhil.yt.R
import androidx.compose.foundation.layout.windowInsetsPadding

/**
 * Isolated experimental SoundCloud website view, not a Capsule audio resolver.
 *
 * SoundCloud owns search results, stream permissions and the playback UI here.
 * No SoundCloud content is relabeled as YouTube or added to Capsule's offline queue.
 */
@Composable
fun SoundCloudWebPreview(query: String, navController: NavController) {
    val context = LocalContext.current
    val searchUrl = remember(query) {
        Uri.Builder()
            .scheme("https")
            .authority("soundcloud.com")
            .appendPath("search")
            .appendPath("sounds")
            .appendQueryParameter("q", query)
            .build()
            .toString()
    }
    val webView = remember(context, searchUrl) {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val target = request.url
                    val host = target.host?.lowercase().orEmpty()
                    if (target.scheme == "https" && (host == "soundcloud.com" || host.endsWith(".soundcloud.com"))) {
                        return false
                    }
                    if (target.scheme == "http" || target.scheme == "https") {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, target)) }
                    }
                    return true
                }
            }
            loadUrl(searchUrl)
        }
    }
    DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.destroy()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(onClick = { navController.navigateUp() }) {
                Text(stringResource(R.string.capsule_soundcloud_close))
            }
            TextButton(onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(searchUrl))) }
            }) {
                Text(stringResource(R.string.capsule_soundcloud_open_browser))
            }
        }
        Text(
            text = stringResource(R.string.capsule_soundcloud_web_notice),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        )
        AndroidView(
            factory = { webView },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
