/*
 * Capsule MUSIC
 * Safe NewPipeExtractor update checker.
 *
 * This does NOT download or execute JAR/DEX code at runtime.
 * It checks the official NewPipeExtractor release feed and, when Capsule CI
 * has produced a signed VIDEO-engine APK for that exact release, exposes the
 * download URL for the full signed Capsule APK.
 *
 * GPL-3.0
 */
package com.nikhil.yt.playback.video

import android.util.Xml
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.bodyAsText
import java.io.StringReader
import org.xmlpull.v1.XmlPullParser

data class CapsuleVideoEngineStatus(
    val bundledVersion: String,
    val upstreamVersion: String,
    val updateAvailable: Boolean,
    val readyBuildVersion: String?,
) {
    val readyToInstall: Boolean
        get() = updateAvailable && readyBuildVersion == upstreamVersion
}

object CapsuleVideoEngineUpdater {
    /*
     * IMPORTANT:
     * .github/workflows/newpipe-extractor-update.yml updates this constant
     * together with gradle/libs.versions.toml before producing an engine build.
     */
    const val BUNDLED_EXTRACTOR_VERSION = "v0.26.5"

    private const val CapsuleRepository =
        "https://github.com/vanimusic6-sudo/capsule-MUSIC"
    private const val NewPipeRepository =
        "https://github.com/TeamNewPipe/NewPipeExtractor"

    private const val CapsuleReleasesFeed = "$CapsuleRepository/releases.atom"
    private const val NewPipeReleasesFeed = "$NewPipeRepository/releases.atom"

    private const val CacheMs = 15L * 60L * 1000L

    private val client = HttpClient()

    @Volatile
    private var cachedStatus: CapsuleVideoEngineStatus? = null

    @Volatile
    private var lastFetchAtMs: Long = 0L

    private data class FeedEntry(
        val title: String,
        val link: String,
    )

    suspend fun check(
        forceRefresh: Boolean = false,
    ): Result<CapsuleVideoEngineStatus> =
        runCatching {
            val now = System.currentTimeMillis()
            cachedStatus?.let { cached ->
                if (!forceRefresh && now - lastFetchAtMs < CacheMs) {
                    return@runCatching cached
                }
            }

            val upstreamEntries = fetchEntries(NewPipeReleasesFeed)
            val upstream =
                upstreamEntries
                    .asSequence()
                    .mapNotNull { releaseTagFromLink(it.link).ifBlank { it.title }.takeIf(String::isNotBlank) }
                    .map(::normalizeExtractorVersion)
                    .firstOrNull { it.isNotBlank() }
                    ?: throw IllegalStateException(
                        "Could not determine the latest NewPipeExtractor release",
                    )

            val bundled = normalizeExtractorVersion(BUNDLED_EXTRACTOR_VERSION)
            val updateAvailable = compareVersions(upstream, bundled) > 0

            val readyBuild =
                if (updateAvailable) {
                    fetchEntries(CapsuleReleasesFeed)
                        .asSequence()
                        .mapNotNull { entry ->
                            val tag = releaseTagFromLink(entry.link)
                            extractorVersionFromEngineTag(tag)
                        }
                        .firstOrNull { compareVersions(it, upstream) == 0 }
                } else {
                    null
                }

            CapsuleVideoEngineStatus(
                bundledVersion = bundled,
                upstreamVersion = upstream,
                updateAvailable = updateAvailable,
                readyBuildVersion = readyBuild,
            ).also {
                cachedStatus = it
                lastFetchAtMs = now
            }
        }

    fun apkDownloadUrl(version: String): String {
        val normalized = normalizeExtractorVersion(version)
        val tag = engineTag(normalized)
        val asset = "Capsule-MUSIC-VIDEO-Engine-$normalized.apk"
        return "$CapsuleRepository/releases/download/$tag/$asset"
    }

    fun engineReleasePage(version: String): String {
        val normalized = normalizeExtractorVersion(version)
        return "$CapsuleRepository/releases/tag/${engineTag(normalized)}"
    }

    fun buildStatusUrl(): String =
        "$CapsuleRepository/actions/workflows/newpipe-extractor-update.yml"

    fun upstreamReleasePage(): String =
        "$NewPipeRepository/releases"

    fun engineTag(version: String): String =
        "video-engine-${normalizeExtractorVersion(version)}"

    fun extractorVersionFromEngineTag(tag: String): String? {
        val normalizedTag = tag.trim()
        if (!normalizedTag.startsWith("video-engine-", ignoreCase = true)) return null
        return normalizeExtractorVersion(
            normalizedTag.substringAfter("video-engine-"),
        ).takeIf { it.isNotBlank() }
    }

    fun isVideoEngineReleaseTag(tag: String): Boolean =
        extractorVersionFromEngineTag(tag) != null

    fun normalizeExtractorVersion(raw: String): String {
        val cleaned =
            raw
                .trim()
                .substringAfterLast('/')
                .removePrefix("NewPipeExtractor ")
                .removePrefix("newpipe-extractor ")
                .trim()

        if (cleaned.isBlank()) return ""
        return if (cleaned.startsWith("v", ignoreCase = true)) {
            "v${cleaned.drop(1)}"
        } else {
            "v$cleaned"
        }
    }

    private fun compareVersions(left: String, right: String): Int {
        val a = numericVersion(left)
        val b = numericVersion(right)
        val max = maxOf(a.size, b.size)

        for (index in 0 until max) {
            val av = a.getOrElse(index) { 0 }
            val bv = b.getOrElse(index) { 0 }
            if (av != bv) return av.compareTo(bv)
        }

        return 0
    }

    private fun numericVersion(value: String): List<Int> =
        normalizeExtractorVersion(value)
            .removePrefix("v")
            .split('.', '-', '_')
            .mapNotNull { token ->
                token.takeWhile(Char::isDigit)
                    .takeIf { it.isNotBlank() }
                    ?.toIntOrNull()
            }

    private suspend fun fetchEntries(url: String): List<FeedEntry> {
        val response =
            client.get(url) {
                headers {
                    append(
                        "Accept",
                        "application/atom+xml, application/xml;q=0.9, text/xml;q=0.8, */*;q=0.5",
                    )
                    append("User-Agent", "capsule-video-engine-updater")
                    append("Cache-Control", "no-cache")
                }
            }

        if (response.status.value !in 200..299) {
            throw IllegalStateException(
                "VIDEO engine update service is temporarily unavailable",
            )
        }

        return parseEntries(response.bodyAsText())
    }

    private fun parseEntries(xml: String): List<FeedEntry> {
        if (xml.isBlank()) return emptyList()

        val parser =
            Xml.newPullParser().apply {
                setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                setInput(StringReader(xml))
            }

        val result = mutableListOf<FeedEntry>()
        var inEntry = false
        var title = ""
        var link = ""

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "entry" -> {
                            inEntry = true
                            title = ""
                            link = ""
                        }

                        "title" -> if (inEntry) {
                            title = parser.nextText().trim()
                        }

                        "link" -> if (inEntry) {
                            val href = parser.getAttributeValue(null, "href").orEmpty()
                            val rel = parser.getAttributeValue(null, "rel").orEmpty()
                            if (href.isNotBlank() && (link.isBlank() || rel == "alternate")) {
                                link = href
                            }
                        }
                    }
                }

                XmlPullParser.END_TAG -> {
                    if (parser.name == "entry" && inEntry) {
                        result += FeedEntry(title = title, link = link)
                        inEntry = false
                    }
                }
            }

            event = parser.next()
        }

        return result
    }

    private fun releaseTagFromLink(link: String): String =
        link
            .substringAfter("/releases/tag/", "")
            .substringBefore('?')
            .substringBefore('#')
            .trim('/')
}
