package com.nikhil.yt.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import coil3.imageLoader
import coil3.intercept.Interceptor
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.security.MessageDigest
import timber.log.Timber

/** Downloaded covers live with user files, not in Coil's evictable/optional image cache. */
internal object DownloadedArtwork {
    private val writes = Mutex()
    internal fun key(url: String): String {
        val canonical = if (url.startsWith("https://lh") && url.contains(".googleusercontent.com/") ||
            url.startsWith("https://yt3.ggpht.com/") || url.startsWith("https://yt3.googleusercontent.com/")) {
            url.replace(Regex("=(?:w[0-9]+|s[0-9]+)[^/]*$"), "")
        } else url
        return hash(canonical)
    }
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    private fun directory(context: Context) = File(context.filesDir, "download-artwork")
    internal fun file(context: Context, url: String) = File(directory(context), "${key(url)}.jpg")
    private fun owner(context: Context, mediaId: String) = File(directory(context), "${hash(mediaId)}.ref")

    suspend fun save(context: Context, mediaId: String, url: String?, imageLoader: coil3.ImageLoader = context.imageLoader): Boolean = withContext(Dispatchers.IO) {
        if (url.isNullOrBlank() || !url.startsWith("https://")) return@withContext false
        try {
            withTimeoutOrNull(8_000) {
                val target = file(context, url)
                // Network image loading happens OUTSIDE the global disk-write mutex.
                // Otherwise a slow cover for song A serializes song B's completion.
                val loaded = if (!valid(target)) {
                    imageLoader.execute(ImageRequest.Builder(context)
                        .data(url).size(768, 768).allowHardware(false).build()) as? SuccessResult
                } else null
                writes.withLock {
                    if (!valid(target)) {
                        val result = loaded ?: return@withLock false
                        target.parentFile!!.mkdirs()
                        val temporary = File(target.parentFile, "${target.name}.part")
                        try {
                            val bitmap = result.image.toBitmap()
                            temporary.outputStream().use {
                                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it))
                            }
                            check(valid(temporary))
                            check(temporary.renameTo(target))
                        } finally { temporary.delete() }
                    }
                    val reference = owner(context, mediaId)
                    val temporary = File(reference.parentFile, "${reference.name}.part")
                    try {
                        temporary.writeText(key(url))
                        check(temporary.renameTo(reference))
                    } finally { temporary.delete() }
                    true
                }
            } ?: false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Do not log artwork URLs, user metadata or signed query parameters.
            Timber.tag("DownloadArtwork").w("Cover save failed type=%s", error.javaClass.simpleName)
            false
        }
    }

    suspend fun remove(context: Context, mediaId: String) = withContext(Dispatchers.IO) {
        try {
            writes.withLock {
                val reference = owner(context, mediaId)
                val key = reference.takeIf { it.isFile }?.readText()
                reference.delete()
                if (key != null && key.matches(Regex("[0-9a-f]{64}")) &&
                    directory(context).listFiles().orEmpty().none { it.extension == "ref" && it.readText() == key }) {
                    File(directory(context), "$key.jpg").delete()
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: java.io.IOException) {
            Timber.tag("DownloadArtwork").w("Cover cleanup failed type=%s", error.javaClass.simpleName)
        }
    }


    internal fun valid(file: File): Boolean {
        if (!file.isFile || file.length() == 0L) return false
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        return bounds.outWidth > 0 && bounds.outHeight > 0
    }
}

/** One image-loader integration covers lists, player, notification and resized artwork requests. */
internal class DownloadedArtworkInterceptor(private val context: Context) : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val data = chain.request.data
        val url = if (data is String || data is android.net.Uri || data is coil3.Uri) data.toString() else null
        val local = if (url?.startsWith("https://") == true) withContext(Dispatchers.IO) {
            DownloadedArtwork.file(context, url).takeIf(DownloadedArtwork::valid)
        } else null
        if (local == null) return chain.proceed()
        val result = chain.withRequest(chain.request.newBuilder().data(local).build()).proceed()
        if (result is coil3.request.ErrorResult) {
            // Bounds can survive damage to the compressed body. Fall back once, without looping.
            withContext(Dispatchers.IO) { local.delete() }
            return chain.proceed()
        }
        return result
    }
}
