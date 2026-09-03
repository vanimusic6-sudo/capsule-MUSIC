/*
 * Capsule MUSIC
 * Credential-free NewPipeExtractor downloader for the isolated :capsule_video process.
 *
 * Important:
 * - Capsule account cookies/tokens never enter this process.
 * - NewPipe's own anonymous/service headers are preserved.
 * - Do NOT set Accept-Encoding manually: OkHttp handles transparent gzip itself.
 *
 * GPL-3.0
 */
package com.nikhil.yt.innertube.video

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request as NewPipeRequest
import org.schabi.newpipe.extractor.downloader.Response as NewPipeResponse
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException
import java.util.concurrent.TimeUnit

internal class CapsuleNewPipeDownloader : Downloader() {

    private val client =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()

    @Throws(IOException::class, ReCaptchaException::class)
    override fun execute(request: NewPipeRequest): NewPipeResponse {
        val method = request.httpMethod()
        val requestBody = request.dataToSend()?.toRequestBody(null)

        val builder =
            okhttp3.Request.Builder()
                .url(request.url())
                .method(method, requestBody)
                .header("User-Agent", DEFAULT_USER_AGENT)

        /*
         * The extractor itself may provide headers required by a particular
         * YouTube client. Preserve them. The :capsule_video process never
         * receives Capsule's logged-in Google state, so these cannot contain
         * Capsule account credentials through the normal architecture.
         *
         * As defense in depth, explicitly reject account-identity headers and
         * strip well-known signed-in Google cookie names while preserving
         * anonymous/service cookies such as CONSENT/PREF/SOCS/visitor cookies.
         */
        request.headers().forEach { (name, values) ->
            when {
                name.equals("Authorization", ignoreCase = true) -> Unit
                name.equals("X-Goog-AuthUser", ignoreCase = true) -> Unit
                name.equals("X-Goog-PageId", ignoreCase = true) -> Unit

                name.equals("Cookie", ignoreCase = true) -> {
                    builder.removeHeader(name)
                    values
                        .map(::sanitizeCookieHeader)
                        .filter { it.isNotBlank() }
                        .forEach { builder.addHeader(name, it) }
                }

                else -> {
                    builder.removeHeader(name)
                    values.forEach { value -> builder.addHeader(name, value) }
                }
            }
        }

        try {
            client.newCall(builder.build()).execute().use { response ->
                if (response.code == 429) {
                    throw ReCaptchaException(
                        "YouTube rate limited the isolated VIDEO extractor (HTTP 429)",
                        request.url(),
                    )
                }

                val responseBody = response.body.string()

                return NewPipeResponse(
                    response.code,
                    response.message,
                    response.headers.toMultimap(),
                    responseBody,
                    response.request.url.toString(),
                )
            }
        } catch (captcha: ReCaptchaException) {
            throw captcha
        } catch (io: IOException) {
            throw io
        } catch (throwable: Throwable) {
            throw IOException("VIDEO extractor request failed", throwable)
        }
    }

    private fun sanitizeCookieHeader(raw: String): String =
        raw.split(';')
            .map { it.trim() }
            .filter { cookie ->
                val name = cookie.substringBefore('=').trim().uppercase()
                name.isNotBlank() && name !in SENSITIVE_GOOGLE_COOKIES
            }
            .joinToString("; ")

    private companion object {
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

        val SENSITIVE_GOOGLE_COOKIES =
            setOf(
                "SID",
                "HSID",
                "SSID",
                "APISID",
                "SAPISID",
                "SIDCC",
                "LOGIN_INFO",
                "__SECURE-1PAPISID",
                "__SECURE-3PAPISID",
                "__SECURE-1PSID",
                "__SECURE-3PSID",
                "__SECURE-1PSIDCC",
                "__SECURE-3PSIDCC",
            )
    }
}
