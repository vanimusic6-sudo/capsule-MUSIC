package com.nikhil.yt.playback.audio

import okhttp3.Interceptor
import java.lang.reflect.Proxy

/** Let scripted chains implement only exercised operations; every unexpected API use fails the test. */
internal fun unexpectedChainOperations(): Interceptor.Chain = Proxy.newProxyInstance(
    Interceptor.Chain::class.java.classLoader, arrayOf(Interceptor.Chain::class.java),
) { _, method, _ -> throw AssertionError("Unscripted interceptor operation: ${method.name}") } as Interceptor.Chain
