package com.nikhil.yt.playback.video

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.RemoteException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CapsuleVideoRemoteClientTest {
    private class Provider : ContentProvider() {
        var response: Bundle? = null
        override fun onCreate() = true
        override fun call(method: String, arg: String?, extras: Bundle?) = response
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }
    @Test fun missingResponseIsAnIpcFailureAndNeverARequestTimeout() {
        val context = RuntimeEnvironment.getApplication()
        val authority = "capsule.test.video"
        val provider = Provider()
        provider.attachInfo(context, ProviderInfo().apply { this.authority = authority })
        ShadowContentResolver.registerProviderInternal(authority, provider)
        val uri = Uri.parse("content://$authority")
        val failure = runCatching { callVideoExtractor(context.contentResolver, uri, "resolve", null, null) }.exceptionOrNull()
        assertTrue(failure is RemoteException)
        assertEquals("VIDEO extractor returned no response", failure?.message)
        provider.response = Bundle().apply { putBoolean("ok", true) }
        assertTrue(callVideoExtractor(context.contentResolver, uri, "resolve", null, null).getBoolean("ok"))
    }
}
