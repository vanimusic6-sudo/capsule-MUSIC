package com.nikhil.yt.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.nikhil.yt.ui.component.capsuleArtworkUrl
import coil3.imageLoader
import coil3.intercept.Interceptor
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.request.CachePolicy
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
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import timber.log.Timber

/** Downloaded covers live with user files, not in Coil's evictable/optional image cache. */
internal object DownloadedArtwork {
    private val writes = Mutex()
    // 640px retains sharp full-player artwork without downloading 2K/4K covers.
    internal const val COVER_SIZE_PX = 640
    private const val MIN_SHARP_EDGE_PX = 480
    private const val MAX_SAVED_JPEG_BYTES = 320_000
    internal fun optimizedUrl(url: String): String = capsuleArtworkUrl(url, COVER_SIZE_PX) ?: url
    private fun attempted(file: File) = File(file.parentFile, "${file.name}.quality-v2")
    private fun bounds(file: File): Pair<Int, Int>? {
        if (!file.isFile || file.length() == 0L) return null
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)
        return if (options.outWidth > 0 && options.outHeight > 0) options.outWidth to options.outHeight else null
    }
    /** Only public, safely resizable CDN URLs can be upgraded. Never enlarge an unknown host. */
    internal fun needsUpgrade(file: File, originalUrl: String): Boolean =
        optimizedUrl(originalUrl) != originalUrl && !attempted(file).isFile &&
            (bounds(file)?.let { minOf(it.first, it.second) < MIN_SHARP_EDGE_PX } == true)
    /** A 640px upgrade must not be shadowed by our own legacy 120px offline cache. */
    internal fun needsFullResolutionFetch(file: File, requestedUrl: String): Boolean {
        val requestedSize = Regex("""=w(\\d+)-h(\\d+)""").find(requestedUrl)
            ?.groupValues?.drop(1)?.mapNotNull(String::toIntOrNull)?.minOrNull()
            ?: Regex("""=s(\\d+)""").find(requestedUrl)?.groupValues?.get(1)?.toIntOrNull()
            ?: return false
        return requestedSize >= MIN_SHARP_EDGE_PX &&
            capsuleArtworkUrl(requestedUrl, 960) != requestedUrl &&
            !attempted(file).isFile &&
            (bounds(file)?.let { minOf(it.first, it.second) < MIN_SHARP_EDGE_PX } == true)
    }

    private fun savedJpeg(bitmap: Bitmap): ByteArray {
        // Never upsample a source thumbnail; encode once at a bounded physical resolution.
        val scale = minOf(1f, COVER_SIZE_PX.toFloat() / maxOf(bitmap.width, bitmap.height))
        val sized = if (scale == 1f) bitmap else Bitmap.createScaledBitmap(bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1), true)
        try {
            var bytes = ByteArrayOutputStream().use { out ->
                check(sized.compress(Bitmap.CompressFormat.JPEG, 82, out)); out.toByteArray()
            }
            if (bytes.size > MAX_SAVED_JPEG_BYTES) bytes = ByteArrayOutputStream().use { out ->
                check(sized.compress(Bitmap.CompressFormat.JPEG, 72, out)); out.toByteArray()
            }
            if (bytes.size > MAX_SAVED_JPEG_BYTES) bytes = ByteArrayOutputStream().use { out ->
                check(sized.compress(Bitmap.CompressFormat.JPEG, 60, out)); out.toByteArray()
            }
            require(bytes.size in 1..MAX_SAVED_JPEG_BYTES) { "Cover exceeded compact JPEG budget" }
            return bytes
        } finally { if (sized !== bitmap) sized.recycle() }
    }
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
                val sourceUrl = optimizedUrl(url)
                // Coil's .size() only limits decoding: upgrade the upstream Google URL
                // itself, while keeping existing tiny covers if a retry fails.
                val loaded = if (!valid(target) || needsUpgrade(target, url)) {
                    imageLoader.execute(ImageRequest.Builder(context)
                        .data(sourceUrl).size(COVER_SIZE_PX, COVER_SIZE_PX)
                        // A legacy file may have been cached under this exact URI.
                        // The remote-upgrade request must not retrieve that stale copy.
                        .memoryCachePolicy(if (valid(target)) CachePolicy.DISABLED else CachePolicy.ENABLED)
                        .allowHardware(false).build()) as? SuccessResult
                } else null
                writes.withLock {
                    val existing = bounds(target)
                    val upgrade = needsUpgrade(target, url)
                    if (existing == null || upgrade) {
                        val result = loaded
                        if (result == null && existing == null) return@withLock false
                        if (result != null) {
                            val bitmap = result.image.toBitmap()
                            val improve = existing == null ||
                                minOf(bitmap.width, bitmap.height) > minOf(existing.first, existing.second)
                            if (improve) {
                                target.parentFile!!.mkdirs()
                                val temporary = File(target.parentFile, "${target.name}.part")
                                try {
                                    temporary.writeBytes(savedJpeg(bitmap))
                                    check(valid(temporary))
                                    // Preserve a previous good cover if saving an upgrade fails.
                                    check(temporary.renameTo(target))
                                } finally { temporary.delete() }
                            }
                            // A small upstream source cannot be magically upscaled.
                            // Do not re-download the same unavailable resolution per song.
                            if (minOf(bitmap.width, bitmap.height) < MIN_SHARP_EDGE_PX) {
                                attempted(target).writeText("checked")
                            } else attempted(target).delete()
                        }
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
                    File(directory(context), "$key.jpg.quality-v2").delete()
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: java.io.IOException) {
            Timber.tag("DownloadArtwork").w("Cover cleanup failed type=%s", error.javaClass.simpleName)
        }
    }


    internal fun valid(file: File): Boolean {
        return bounds(file) != null
    }
}

/** One image-loader integration covers lists, player, notification and resized artwork requests. */
internal class DownloadedArtworkInterceptor(private val context: Context) : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val data = chain.request.data
        val url = if (data is String || data is android.net.Uri || data is coil3.Uri) data.toString() else null
        val local = if (url?.startsWith("https://") == true) withContext(Dispatchers.IO) {
            DownloadedArtwork.file(context, url).takeIf { target ->
                DownloadedArtwork.valid(target) && !DownloadedArtwork.needsFullResolutionFetch(target, url)
            }
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
