package com.nikhil.yt.playback.video

import android.content.ContentResolver
import android.net.Uri
import android.os.Bundle
import android.os.RemoteException

/** Never acquire a stable provider reference: its death must not kill MUSIC. */
internal fun callVideoExtractor(
    resolver: ContentResolver, uri: Uri, method: String, arg: String?, extras: Bundle?,
): Bundle {
    val client = resolver.acquireUnstableContentProviderClient(uri)
        ?: throw RemoteException("VIDEO extractor process unavailable")
    return client.use {
        it.call(method, arg, extras)
            ?: throw RemoteException("VIDEO extractor returned no response")
    }
}
