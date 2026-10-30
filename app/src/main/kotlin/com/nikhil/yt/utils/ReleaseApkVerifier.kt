package com.nikhil.yt.utils

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Defense-in-depth for APKs obtained from GitHub release links or elsewhere.
 * GitHub account ownership alone is not a developer identity proof: the
 * official Capsule release signing certificate is pinned independently.
 *
 * This intentionally DOES NOT install an APK or execute any of its code.
 * The Android package installer performs the authoritative complete APK
 * signature verification during installation.
 */
internal object ReleaseApkVerifier {
    const val OFFICIAL_CERT_SHA256 =
        "c044579fd886ee7f6ae919d705fb3345f7417957d8cd49fd110d596f864ddd7d"
    const val OFFICIAL_PACKAGE = "com.nikhil.yt"
    private const val MAX_APK_BYTES = 256L * 1024 * 1024

    data class Report(val packageName: String, val version: String, val sha256: String)

    suspend fun inspect(context: Context, uri: Uri): Result<Report> =
        withContext(Dispatchers.IO) {
            runCatching {
                val file = File.createTempFile("capsule-release-check-", ".apk", context.cacheDir)
                try {
                    val digest = MessageDigest.getInstance("SHA-256")
                    val input = context.contentResolver.openInputStream(uri)
                        ?: throw IOException("Could not open selected APK")
                    input.use { source ->
                        file.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var total = 0L
                            while (true) {
                                val count = source.read(buffer)
                                if (count < 0) break
                                if (count == 0) continue
                                if (count.toLong() > MAX_APK_BYTES - total) {
                                    throw IOException("Selected APK exceeds safety size limit")
                                }
                                digest.update(buffer, 0, count)
                                output.write(buffer, 0, count)
                                total += count
                            }
                        }
                    }
                    val pm = context.packageManager
                    @Suppress("DEPRECATION")
                    val info = if (Build.VERSION.SDK_INT >= 33) {
                        pm.getPackageArchiveInfo(
                            file.absolutePath,
                            PackageManager.PackageInfoFlags.of(
                                PackageManager.GET_SIGNING_CERTIFICATES.toLong()
                            )
                        )
                    } else {
                        pm.getPackageArchiveInfo(
                            file.absolutePath,
                            if (Build.VERSION.SDK_INT >= 28) {
                                PackageManager.GET_SIGNING_CERTIFICATES
                            } else PackageManager.GET_SIGNATURES
                        )
                    } ?: throw IOException("Selected file is not a readable Android APK")
                    if (info.packageName != OFFICIAL_PACKAGE) {
                        throw SecurityException("Not the official Capsule package identifier")
                    }
                    @Suppress("DEPRECATION")
                    val signers = if (Build.VERSION.SDK_INT >= 28) {
                        info.signingInfo?.apkContentsSigners
                    } else info.signatures
                    if (signers.isNullOrEmpty()) {
                        throw SecurityException("APK has no readable signing certificate")
                    }
                    val fingerprints = signers.map { signature ->
                        MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                            .joinToString("") { "%02x".format(it.toInt() and 255) }
                    }.toSet()
                    if (fingerprints != setOf(OFFICIAL_CERT_SHA256)) {
                        throw SecurityException("The APK signing certificate does not belong to Capsule")
                    }
                    val apkSha256 = digest.digest().joinToString("") {
                        "%02x".format(it.toInt() and 255)
                    }
                    Report(
                        packageName = info.packageName,
                        version = info.versionName.orEmpty(),
                        sha256 = apkSha256,
                    )
                } finally {
                    file.delete()
                }
            }
        }
}
