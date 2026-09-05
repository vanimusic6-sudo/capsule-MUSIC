package com.nikhil.yt.playback.audio.potoken

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.MainThread
import com.nikhil.yt.innertube.YouTube
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Small sandboxed WebView used only to execute YouTube's current BotGuard
 * program and mint first-party proof-of-origin tokens. Network loading inside
 * the WebView is disabled; BotGuard RPCs are made explicitly with OkHttp.
 */
internal class PoTokenWebView private constructor(
    context: Context,
    private val initContinuation: Continuation<PoTokenWebView>,
) {
    private val webView = WebView(context)
    private val scope = MainScope()
    private val initCompleted = AtomicBoolean(false)
    private val requestCounter = AtomicLong()
    private val pending =
        Collections.synchronizedMap(mutableMapOf<String, Continuation<String>>())

    @Volatile
    private var closed = false

    @Volatile
    var isDead: Boolean = false
        private set

    private lateinit var expiresAt: Instant

    private val exceptionHandler = CoroutineExceptionHandler { _, error ->
        failInitialization(error)
    }

    init {
        webView.settings.apply {
            javaScriptEnabled = true
            userAgentString = USER_AGENT
            blockNetworkLoads = true
        }
        webView.addJavascriptInterface(this, JS_INTERFACE)
        webView.webChromeClient =
            object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                    val message = consoleMessage.message()
                    when (consoleMessage.messageLevel()) {
                        ConsoleMessage.MessageLevel.ERROR -> Timber.tag(TAG).e("JS: %s", message)
                        ConsoleMessage.MessageLevel.WARNING -> Timber.tag(TAG).w("JS: %s", message)
                        else -> Timber.tag(TAG).d("JS: %s", message)
                    }
                    if (message.contains("Uncaught", ignoreCase = true)) {
                        val error =
                            if (initCompleted.get()) {
                                PoTokenException(message)
                            } else {
                                BadWebViewException(message)
                            }
                        isDead = true
                        if (initCompleted.get()) {
                            close()
                            drainPending(error)
                        } else {
                            failInitialization(error)
                        }
                    }
                    return true
                }
            }
        webView.webViewClient =
            object : WebViewClient() {
                override fun onRenderProcessGone(
                    view: WebView,
                    detail: RenderProcessGoneDetail,
                ): Boolean {
                    isDead = true
                    val error = PoTokenException("PoToken WebView renderer stopped")
                    if (initCompleted.get()) {
                        close()
                        drainPending(error)
                    } else {
                        failInitialization(error)
                    }
                    return true
                }
            }
    }

    private fun start() {
        scope.launch(exceptionHandler) {
            val html =
                withContext(Dispatchers.IO) {
                    webView.context.assets.open("po_token.html").bufferedReader().use { it.readText() }
                }
            val instrumented =
                html.replaceFirst(
                    "</script>",
                    "\n$JS_INTERFACE.downloadAndRunBotguard()</script>",
                )
            webView.loadDataWithBaseURL(
                "https://www.youtube.com",
                instrumented,
                "text/html",
                "utf-8",
                null,
            )
        }
    }

    @JavascriptInterface
    fun downloadAndRunBotguard() {
        botguardRequest(
            url = "https://www.youtube.com/api/jnn/v1/Create",
            body = "[ \"$REQUEST_KEY\" ]",
        ) { response ->
            val challenge = parseChallengeData(response)
            webView.evaluateJavascript(
                """try {
                    var challengeData = $challenge;
                    runBotGuard(challengeData).then(function(result) {
                        window.webPoSignalOutput = result.webPoSignalOutput;
                        $JS_INTERFACE.onRunBotguardResult(result.botguardResponse);
                    }).catch(function(error) {
                        $JS_INTERFACE.onJsInitializationError(String(error) + "\\n" + (error.stack || ""));
                    });
                } catch (error) {
                    $JS_INTERFACE.onJsInitializationError(String(error) + "\\n" + (error.stack || ""));
                }""".trimIndent(),
                null,
            )
        }
    }

    @JavascriptInterface
    fun onRunBotguardResult(botguardResponse: String) {
        botguardRequest(
            url = "https://www.youtube.com/api/jnn/v1/GenerateIT",
            body = "[ \"$REQUEST_KEY\", \"$botguardResponse\" ]",
        ) { response ->
            try {
                val (integrityToken, expiresInSeconds) = parseIntegrityTokenData(response)
                expiresAt =
                    Instant.now()
                        .plusSeconds(expiresInSeconds)
                        .minus(10, ChronoUnit.MINUTES)
                webView.evaluateJavascript(
                    """try {
                        window.integrityToken = $integrityToken;
                        createPoTokenMinter(window.webPoSignalOutput, window.integrityToken)
                            .then(function() { $JS_INTERFACE.onMinterReady(); })
                            .catch(function(error) {
                                $JS_INTERFACE.onJsInitializationError(String(error) + "\\n" + (error.stack || ""));
                            });
                    } catch (error) {
                        $JS_INTERFACE.onJsInitializationError(String(error) + "\\n" + (error.stack || ""));
                    }""".trimIndent(),
                    null,
                )
            } catch (error: Throwable) {
                failInitialization(PoTokenException("BotGuard integrity response could not be parsed", error))
            }
        }
    }

    @JavascriptInterface
    fun onJsInitializationError(error: String) {
        failInitialization(buildExceptionForJsError(error))
    }

    @JavascriptInterface
    fun onMinterReady() {
        if (initCompleted.compareAndSet(false, true)) {
            initContinuation.resume(this)
        }
    }

    suspend fun generatePoToken(identifier: String): String {
        if (closed || isDead) throw PoTokenException("PoToken WebView is unavailable")
        val requestKey = "$identifier#${requestCounter.incrementAndGet()}"
        return try {
            withTimeout(GENERATE_TIMEOUT_MS) {
                withContext(Dispatchers.Main) {
                    suspendCancellableCoroutine { continuation ->
                        pending[requestKey] = continuation
                        continuation.invokeOnCancellation { pending.remove(requestKey) }
                        webView.evaluateJavascript(
                            """(function() {
                                var requestKey = ${jsQuote(requestKey)};
                                try {
                                    var identifier = ${stringToJavascriptBytes(identifier)};
                                    obtainPoToken(identifier).then(function(bytes) {
                                        $JS_INTERFACE.onToken(requestKey, bytes.join(","));
                                    }).catch(function(error) {
                                        $JS_INTERFACE.onTokenError(requestKey, String(error) + "\\n" + (error.stack || ""));
                                    });
                                } catch (error) {
                                    $JS_INTERFACE.onTokenError(requestKey, String(error) + "\\n" + (error.stack || ""));
                                }
                            })();""".trimIndent(),
                            null,
                        )
                    }
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            isDead = true
            pending.remove(requestKey)
            close()
            throw PoTokenException("PoToken generation timed out", timeout)
        }
    }

    @JavascriptInterface
    fun onToken(requestKey: String, bytes: String) {
        val continuation = pending.remove(requestKey) ?: return
        runCatching { byteCsvToWebSafeBase64(bytes) }
            .onSuccess(continuation::resume)
            .onFailure(continuation::resumeWithException)
    }

    @JavascriptInterface
    fun onTokenError(requestKey: String, error: String) {
        pending.remove(requestKey)?.resumeWithException(PoTokenException(error))
    }

    val isExpired: Boolean
        get() = !::expiresAt.isInitialized || Instant.now().isAfter(expiresAt)

    private fun botguardRequest(
        url: String,
        body: String,
        onSuccess: (String) -> Unit,
    ) {
        scope.launch(exceptionHandler) {
            val request =
                okhttp3.Request.Builder()
                    .url(url)
                    .post(body.toRequestBody())
                    .headers(
                        mapOf(
                            "User-Agent" to USER_AGENT,
                            "Accept" to "application/json",
                            "Content-Type" to "application/json+protobuf",
                            "x-goog-api-key" to GOOGLE_API_KEY,
                            "x-user-agent" to "grpc-web-javascript/0.1",
                        ).toHeaders(),
                    )
                    .build()
            val (status, responseBody) =
                withContext(Dispatchers.IO) {
                    createHttpClient().newCall(request).execute().use { response ->
                        response.code to response.body?.string()
                    }
                }
            if (status != 200 || responseBody.isNullOrBlank()) {
                throw PoTokenException("BotGuard request failed (HTTP $status)")
            }
            onSuccess(responseBody)
        }
    }

    private fun failInitialization(error: Throwable) {
        close()
        if (initCompleted.compareAndSet(false, true)) {
            runCatching { initContinuation.resumeWithException(error) }
        }
    }

    private fun drainPending(error: Throwable) {
        val copy = synchronized(pending) { pending.toMap().also { pending.clear() } }
        copy.values.forEach { continuation ->
            runCatching { continuation.resumeWithException(error) }
        }
    }

    fun close() {
        if (closed) return
        closed = true
        scope.cancel()
        if (Looper.myLooper() == Looper.getMainLooper()) {
            destroyWebView()
        } else {
            Handler(Looper.getMainLooper()).post(::destroyWebView)
        }
    }

    @MainThread
    private fun destroyWebView() {
        runCatching {
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.removeJavascriptInterface(JS_INTERFACE)
            webView.removeAllViews()
            webView.destroy()
        }.onFailure { Timber.tag(TAG).w(it, "PoToken WebView teardown failed") }
    }

    companion object {
        private const val TAG = "CapsulePoToken"
        private const val GOOGLE_API_KEY = "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw"
        private const val REQUEST_KEY = "O43z0dpjhgX20SCx4KAo"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        private const val JS_INTERFACE = "CapsulePoTokenBridge"
        private const val INIT_TIMEOUT_MS = 45_000L
        private const val GENERATE_TIMEOUT_MS = 15_000L

        suspend fun create(context: Context): PoTokenWebView {
            var created: PoTokenWebView? = null
            try {
                return withTimeout(INIT_TIMEOUT_MS) {
                    withContext(Dispatchers.Main) {
                        suspendCancellableCoroutine { continuation ->
                            val instance = PoTokenWebView(context.applicationContext, continuation)
                            created = instance
                            continuation.invokeOnCancellation { instance.close() }
                            instance.start()
                        }
                    }
                }
            } catch (timeout: TimeoutCancellationException) {
                closeQuietly(created)
                throw PoTokenException("PoToken WebView initialization timed out", timeout)
            } catch (cancelled: CancellationException) {
                closeQuietly(created)
                throw cancelled
            }
        }

        private suspend fun closeQuietly(instance: PoTokenWebView?) {
            if (instance == null) return
            withContext(NonCancellable + Dispatchers.Main) {
                instance.initCompleted.set(true)
                instance.close()
            }
        }

        private fun createHttpClient(): OkHttpClient =
            OkHttpClient.Builder()
                .proxy(YouTube.proxy)
                .build()

        private fun jsQuote(value: String): String =
            buildString {
                append('"')
                value.forEach { ch ->
                    when (ch) {
                        '\\' -> append("\\\\")
                        '"' -> append("\\\"")
                        '\n' -> append("\\n")
                        '\r' -> append("\\r")
                        else -> append(ch)
                    }
                }
                append('"')
            }
    }
}
