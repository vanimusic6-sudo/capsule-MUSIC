package com.nikhil.yt.playback.audio

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.nikhil.yt.innertube.YouTube
import com.nikhil.yt.utils.StreamClientUtils
import java.io.IOException
import java.net.Proxy
import okhttp3.OkHttpClient

/** Re-evaluate the stream route on every HTTP open, including reused data sources. */
internal class StreamDataSourceFactory(private val audio: Boolean = true) : DataSource.Factory {
    private var proxy: Proxy? = null
    private var client: OkHttpClient? = null

    override fun createDataSource(): DataSource =
        OkHttpDataSource.Factory { request -> currentClient().newCall(request) }.createDataSource()

    @Synchronized
    private fun currentClient(): OkHttpClient {
        val currentProxy = YouTube.streamProxy
        if (client == null || currentProxy != proxy) {
            proxy = currentProxy
            client = OkHttpClient.Builder()
                .proxy(currentProxy)
                .followRedirects(true)
                .followSslRedirects(true)
                .addInterceptor { chain ->
                    if (audio) CapsuleAudioEngine.playbackBlockedExceptionOrNull()?.let {
                        throw IOException(it.message, it)
                    }
                    chain.proceed(StreamClientUtils.withFallbackHeaders(chain.request())).also {
                        if (audio && it.code == 429) CapsuleAudioEngine.markRateLimitedFailure()
                    }
                }
                .build()
        }
        return requireNotNull(client)
    }
}
