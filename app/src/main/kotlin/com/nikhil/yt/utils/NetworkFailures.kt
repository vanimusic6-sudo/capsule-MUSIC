package com.nikhil.yt.utils

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ResponseException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

internal enum class NetworkFailureKind { CONNECTION, TIMEOUT }

/** Media3 and Ktor can wrap transport failures more than once. Never inspect arbitrary text. */
internal fun Throwable.failureChain(): List<Throwable> {
    val chain = mutableListOf<Throwable>()
    var next: Throwable? = this
    while (next != null && chain.size < 16 && chain.none { it === next }) {
        chain += next
        next = next.cause
    }
    return chain
}

internal fun Throwable.httpFailureStatus(): Int? =
    failureChain().firstNotNullOfOrNull {
        when (it) {
            is HttpDataSource.InvalidResponseCodeException -> it.responseCode
            is ResponseException -> it.response.status.value
            else -> null
        }
    }

internal fun Throwable.networkFailureKind(): NetworkFailureKind? {
    if (this is CancellationException) return null
    // An HTTP/API rejection is a response from a reachable server, not a transport outage.
    if (httpFailureStatus() != null) return null
    val causes = failureChain()
    if (causes.any {
            it is SocketTimeoutException || it is ConnectTimeoutException ||
                it is TimeoutCancellationException ||
                it is HttpRequestTimeoutException ||
                (it is PlaybackException &&
                    it.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)
        }) return NetworkFailureKind.TIMEOUT
    if (causes.any {
            it is UnknownHostException || it is SocketException ||
                (it is PlaybackException &&
                    it.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        }) return NetworkFailureKind.CONNECTION
    return null
}
