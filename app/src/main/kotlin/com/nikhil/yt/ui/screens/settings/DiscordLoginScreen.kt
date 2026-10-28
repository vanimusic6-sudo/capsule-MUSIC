package com.nikhil.yt.ui.screens.settings

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import com.nikhil.yt.BuildConfig
import com.nikhil.yt.LocalPlayerAwareWindowInsets
import com.nikhil.yt.R
import com.nikhil.yt.constants.DiscordTokenKey
import com.nikhil.yt.ui.component.IconButton
import com.nikhil.yt.ui.utils.backToMain
import com.nikhil.yt.utils.rememberPreference
import org.json.JSONTokener
import java.net.URI

/**
 * Discord's OAuth code/PKCE flow should replace the legacy Discord client token
 * integration in a future release. For now keep legacy login without exposing
 * a JS->native @JavascriptInterface or allowing foreign-origin navigation.
 */
internal fun isTrustedDiscordLoginUrl(raw: String?): Boolean {
    val uri = runCatching { URI(raw ?: return false) }.getOrNull() ?: return false
    return uri.scheme.equals("https", ignoreCase = true) &&
        (uri.host.equals("discord.com", ignoreCase = true) ||
         uri.host.equals("www.discord.com", ignoreCase = true)) &&
        uri.userInfo == null && (uri.port == -1 || uri.port == 443)
}

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscordLoginScreen(navController: NavController) {
    var discordToken by rememberPreference(DiscordTokenKey, "")
    var webView by remember { mutableStateOf<WebView?>(null) }
    var isCompleting by remember { mutableStateOf(false) }

    fun completeLogin(token: String) {
        if (isCompleting) return
        val trimmed = token.trim()
        if (trimmed.isBlank() || trimmed == "null" || trimmed == "error") return
        isCompleting = true
        discordToken = trimmed
        webView?.stopLoading()
        webView?.loadUrl("about:blank")
        navController.navigateUp()
    }

    AndroidView(
        modifier = Modifier.windowInsetsPadding(
            LocalPlayerAwareWindowInsets.current.only(
                WindowInsetsSides.Horizontal + WindowInsetsSides.Top
            )
        ).fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                CookieManager.getInstance().setAcceptCookie(true)
                // Never clear the shared global CookieManager or WebStorage.
                // Both are also used by the YouTube Music login.

                webViewClient = object : WebViewClient() {
                    /**
                     * evaluateJavascript callback observes the *top-level* document
                     * without exposing Java native methods to all remote frames.
                     * Never accept page alerts or arbitrary navigation as tokens.
                     */
                    fun capture(view: WebView, pageUrl: String, retries: Int) {
                        if (isCompleting || !isTrustedDiscordLoginUrl(pageUrl) ||
                            view.url != pageUrl) return
                        val js = """
                            (function() {
                                function clean(t) {
                                    if (!t) return '';
                                    try {
                                        var s = String(t);
                                        if (s.length >= 2 && s[0] === '"' && s[s.length - 1] === '"') {
                                            s = s.slice(1, -1);
                                        }
                                        return (s === 'null' || s === 'error') ? '' : s;
                                    } catch(e) { return ''; }
                                }
                                try {
                                    var s = clean(window.localStorage.getItem('token'));
                                    if (s) return s;
                                } catch(e) {}
                                try {
                                    var w = window.webpackChunkdiscord_app;
                                    if (w && w.push) {
                                        var value = '';
                                        w.push([[Math.random()], {}, function(req) {
                                            try {
                                                for (var k in req.c) {
                                                    var m = req.c[k];
                                                    var exp = m && m.exports && m.exports.default;
                                                    if (exp && typeof exp.getToken === 'function') {
                                                        value = clean(exp.getToken());
                                                        if (value) break;
                                                    }
                                                }
                                            } catch(e) {}
                                        }]);
                                        if (value) return value;
                                    }
                                } catch(e) {}
                                return '';
                            })();
                        """.trimIndent()
                        view.evaluateJavascript(js) { raw ->
                            if (isCompleting || view.url != pageUrl ||
                                !isTrustedDiscordLoginUrl(view.url)) return@evaluateJavascript
                            val token = runCatching {
                                JSONTokener(raw.orEmpty()).nextValue() as? String
                            }.getOrNull().orEmpty()
                            if (token.isNotBlank()) completeLogin(token)
                            else if (retries > 0) {
                                view.postDelayed({ capture(view, pageUrl, retries - 1) }, 1200L)
                            }
                        }
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        if (!isTrustedDiscordLoginUrl(url) || isCompleting) return
                        val path = runCatching { URI(url).path }.getOrNull().orEmpty()
                        if (path.startsWith("/login")) return
                        capture(view, url, 3)
                    }

                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        // A legitimate login must remain on the official HTTPS origin.
                        // Reject links/redirects to lookalike or cleartext origins.
                        return isCompleting || !isTrustedDiscordLoginUrl(request.url.toString())
                    }
                }

                webView = this
                loadUrl("https://discord.com/login")
            }
        }
    )

    TopAppBar(
        title = { Text(stringResource(R.string.action_login)) },
        navigationIcon = {
            IconButton(onClick = navController::navigateUp,
                onLongClick = navController::backToMain) {
                Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
            }
        }
    )

    BackHandler(enabled = webView?.canGoBack() == true) {
        webView?.goBack()
    }
}
