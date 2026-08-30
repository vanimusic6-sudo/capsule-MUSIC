/*
 * Capsule MUSIC
 * Credential-free downloader used only by NewPipeExtractor in :capsule_video.
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
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(18, TimeUnit.SECONDS)
            .writeTimeout(18, TimeUnit.SECONDS)
            .callTimeout(24, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()

    override fun execute(request: NewPipeRequest): NewPipeResponse {
        val method = request.httpMethod().uppercase()
        val rawBody = request.dataToSend()
        val body =
            when (method) {
                "POST", "PUT", "PATCH" -> (rawBody ?: ByteArray(0)).toRequestBody(null)
                else -> rawBody?.toRequestBody(null)
            }
        val builder =
            okhttp3.Request.Builder()
                .url(request.url())
                .method(method, body)
                .header("User-Agent", DEFAULT_USER_AGENT)
                .header("Accept-Encoding", "gzip")

        request.headers().forEach { (name, values) ->
            if (name.lowercase() in CREDENTIAL_HEADERS) return@forEach
            builder.removeHeader(name)
            values.forEach { value -> builder.addHeader(name, value) }
        }

        try {
            client.newCall(builder.build()).execute().use { response ->
                if (response.code == 429) {
                    throw ReCaptchaException(
                        "YouTube rate limited the isolated VIDEO extractor (HTTP 429)",
                        request.url(),
                    )
                }

                val responseBody =
                    if (method == "HEAD") "" else response.body?.string().orEmpty()

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

    private companion object {
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

        val CREDENTIAL_HEADERS =
            setOf(
                "authorization",
                "cookie",
                "x-goog-authuser",
                "x-goog-pageid",
            )
    }
}
