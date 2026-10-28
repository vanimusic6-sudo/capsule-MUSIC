/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */



package com.nikhil.yt.ui.screens

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import org.json.JSONTokener
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import androidx.datastore.preferences.core.edit
import com.nikhil.yt.LocalPlayerAwareWindowInsets
import com.nikhil.yt.R
import com.nikhil.yt.constants.AccountChannelHandleKey
import com.nikhil.yt.constants.AccountEmailKey
import com.nikhil.yt.constants.AccountNameKey
import com.nikhil.yt.constants.DataSyncIdKey
import com.nikhil.yt.constants.InnerTubeCookieKey
import com.nikhil.yt.constants.PoTokenKey
import com.nikhil.yt.constants.VisitorDataKey
import com.nikhil.yt.ui.component.IconButton
import com.nikhil.yt.ui.utils.backToMain
import com.nikhil.yt.utils.dataStore
import com.nikhil.yt.utils.rememberPreference
import com.nikhil.yt.utils.reportException
import com.nikhil.yt.innertube.YouTube
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class, DelicateCoroutinesApi::class)
@Composable
fun LoginScreen(
    navController: NavController,
) {
    val coroutineScope = rememberCoroutineScope()
    var visitorData by rememberPreference(VisitorDataKey, "")
    var dataSyncId by rememberPreference(DataSyncIdKey, "")
    var poToken by rememberPreference(PoTokenKey, "")
    var accountName by rememberPreference(AccountNameKey, "")
    var accountEmail by rememberPreference(AccountEmailKey, "")
    var accountChannelHandle by rememberPreference(AccountChannelHandleKey, "")

    var webView: WebView? = null

    AndroidView(
        modifier = Modifier
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current)
            .fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        if (isYouTubeMusicLoginPage(url)) {
                            // Read values from the trusted TOP-LEVEL music.youtube.com
                            // document. Do not expose Java methods to all WebView frames.
                            fun capture(script: String, receiver: (String) -> Unit) {
                                view.evaluateJavascript(script) { raw ->
                                    if (!isYouTubeMusicLoginPage(view.url)) return@evaluateJavascript
                                    val value = runCatching {
                                        JSONTokener(raw.orEmpty()).nextValue() as? String
                                    }.getOrNull().orEmpty()
                                    if (value.isNotBlank()) receiver(value)
                                }
                            }
                            capture("(function(){try{return window.yt.config_.VISITOR_DATA||''}catch(e){return ''}})()") {
                                visitorData = it
                            }
                            capture("(function(){try{return window.yt.config_.DATASYNC_ID||''}catch(e){return ''}})()") {
                                dataSyncId = it.substringBefore("||")
                            }
                            capture("""(function(){try{
                                var c=window.ytcfg;
                                if(c&&c.get){var t=c.get('PO_TOKEN');if(t)return t;}
                                var nodes=document.querySelectorAll('script');
                                for(var i=0;i<nodes.length;i++){
                                    var m=nodes[i].textContent.match(/"PO_TOKEN":"([^"]+)"/);
                                    if(m)return m[1];
                                }
                            }catch(e){} return '';})()""") { poToken = it }

                            val loginCookie = CookieManager.getInstance().getCookie(url).orEmpty()
                            if (loginCookie.isBlank()) return
                            CookieManager.getInstance().flush()

                            coroutineScope.launch {
                                context.dataStore.edit { settings ->
                                    settings[InnerTubeCookieKey] = loginCookie
                                }

                                val published =
                                    withTimeoutOrNull(5_000L) {
                                        YouTube.authStates.first { state ->
                                            state.cookie == loginCookie && state.hasLoginCookie
                                        }
                                    } != null

                                // DataStore is the source of truth. This fallback only covers an
                                // unexpectedly stalled application collector after the edit has
                                // already committed the same cookie to disk.
                                if (!published) {
                                    YouTube.cookie = loginCookie
                                }

                                YouTube.accountInfo().onSuccess {
                                    accountName = it.name
                                    accountEmail = it.email.orEmpty()
                                    accountChannelHandle = it.channelHandle.orEmpty()
                                    if (navController.currentBackStackEntry?.destination?.route == "login") {
                                        navController.navigateUp()
                                    }
                                }.onFailure {
                                    reportException(it)
                                    if (navController.currentBackStackEntry?.destination?.route == "login") {
                                        navController.navigateUp()
                                    }
                                }
                            }
                        }
                    }
                }
                settings.apply {
                    javaScriptEnabled = true
                    allowFileAccess = false
                    allowContentAccess = false
                    setSupportZoom(true)
                    builtInZoomControls = true
                    displayZoomControls = false
                }
                webView = this
                loadUrl("https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com")
            }
        }
    )

    TopAppBar(
        title = { Text(stringResource(R.string.login)) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain
            ) {
                Icon(
                    painterResource(R.drawable.arrow_back),
                    contentDescription = null
                )
            }
        }
    )

    BackHandler(enabled = webView?.canGoBack() == true) {
        webView?.goBack()
    }
}
