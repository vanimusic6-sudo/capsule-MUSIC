/* Capsule MUSIC — anonymous, cancellable VIDEO-only PipePipe transport. GPL-3.0. */
package com.nikhil.yt.innertube.video

import dev.maxrave.pipepipe.extractor.downloader.Downloader
import dev.maxrave.pipepipe.extractor.downloader.CancellableCall
import dev.maxrave.pipepipe.extractor.downloader.Request as PipeRequest
import dev.maxrave.pipepipe.extractor.downloader.Response as PipeResponse
import dev.maxrave.pipepipe.extractor.exceptions.ReCaptchaException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

internal class CapsulePipePipeDownloader(
    private val client: OkHttpClient = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS)
        .connectTimeout(4, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false).build(),
) : Downloader() {

    private fun build(request: PipeRequest): okhttp3.Request = okhttp3.Request.Builder()
        .url(request.url()).method(request.httpMethod(), request.dataToSend()?.toRequestBody(null))
        .header("User-Agent", USER_AGENT).apply {
            anonymousVideoHeaders(request.headers()).forEach { (name, values) ->
                removeHeader(name)
                values.forEach { addHeader(name, it) }
            }
        }.build()

    override fun execute(request: PipeRequest): PipeResponse {
        val call = client.newCall(build(request))
        return VideoExtractionRequests.execute(call) { call.execute().use(::convert) }
    }

    override fun executeAsync(request: PipeRequest, callback: Downloader.AsyncCallback): CancellableCall {
        val call = client.newCall(build(request))
        val handle = CancellableCall(call)
        val completed = VideoExtractionRequests.registerAsync(call)
        try {
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    try { callback.onError(e) } finally { completed(); handle.setFinished() }
                }
                override fun onResponse(call: Call, response: Response) {
                    try { response.use { callback.onSuccess(convert(it)) } }
                    catch (e: Exception) { callback.onError(e) }
                    finally { completed(); handle.setFinished() }
                }
            })
        } catch (e: Exception) {
            completed(); handle.setFinished(); call.cancel(); throw e
        }
        return handle
    }

    private fun convert(response: Response): PipeResponse {
        if (response.code == 429) {
            if (response.request.url.host == "api.pipepipe.dev") throw IOException("VIDEO fallback decoder temporarily unavailable")
            throw ReCaptchaException("VIDEO HTTP 429", response.request.url.toString())
        }
        val body = response.body
        val charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
        val bytes = body.bytes()
        return PipeResponse(response.code, response.message, response.headers.toMultimap(),
            bytes.toString(charset), bytes, response.request.url.toString())
    }

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"
    }
}

/** No signed-in state is accepted even if an upstream adapter accidentally supplies it. */
internal fun anonymousVideoHeaders(headers: Map<String, List<String>>): Map<String, List<String>> =
    headers.filterKeys { it.lowercase() !in setOf("authorization", "x-goog-authuser", "x-goog-pageid", "accept-encoding") }
        .mapValues { (name, values) ->
            if (!name.equals("cookie", true)) values else values.map { raw ->
                raw.split(';').map(String::trim).filter { cookie ->
                    val key = cookie.substringBefore('=').uppercase()
                    key !in setOf("SID", "HSID", "SSID", "APISID", "SAPISID", "SIDCC", "LOGIN_INFO") &&
                        !key.startsWith("__SECURE-")
                }.joinToString("; ")
            }.filter(String::isNotBlank)
        }
