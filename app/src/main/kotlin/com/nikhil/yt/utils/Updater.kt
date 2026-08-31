/*
 * capsule fork
 * Based on Velune by Nikhil
 * Licensed Under GPL-3.0
 */

package com.nikhil.yt.utils

import android.util.Xml
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import java.io.StringReader
import org.xmlpull.v1.XmlPullParser

data class GitCommit(
    val sha: String,
    val message: String,
    val author: String,
    val date: String,
    val url: String,
)

data class ReleaseInfo(
    val tagName: String,
    val name: String,
    val body: String?,
    val publishedAt: String,
    val htmlUrl: String,
)

private data class AtomEntry(
    val id: String,
    val title: String,
    val updated: String,
    val link: String,
    val content: String,
    val author: String,
)

object Updater {
    private const val RepositoryOwner = "vanimusic6-sudo"
    private const val RepositoryName = "capsule-MUSIC"
    private const val RepositoryWeb =
        "https://github.com/$RepositoryOwner/$RepositoryName"
    private const val ReleasesFeed = "$RepositoryWeb/releases.atom"
    private const val ReleaseCacheIntervalMs = 15 * 60 * 1000L

    /*
     * Important: this updater deliberately does NOT use api.github.com.
     * Unauthenticated GitHub REST requests have a small rate limit and can
     * return HTTP 403. Public Atom feeds are enough for release/commit checks
     * and do not consume that REST API quota.
     *
     * Automatic NewPipeExtractor VIDEO-engine builds use tags beginning with
     * "video-engine-". They are intentionally hidden from the NORMAL Capsule
     * app updater and handled separately by CapsuleVideoEngineUpdater.
     */
    private val client = HttpClient()

    @Volatile
    private var cachedReleases: List<ReleaseInfo> = emptyList()

    @Volatile
    private var lastReleaseFetchAt: Long = 0L

    var lastCheckTime = -1L
        private set

    private fun parseAtomFeed(xml: String): List<AtomEntry> {
        if (xml.isBlank()) return emptyList()

        val parser =
            Xml.newPullParser().apply {
                setFeature(
                    XmlPullParser.FEATURE_PROCESS_NAMESPACES,
                    false,
                )
                setInput(StringReader(xml))
            }

        val entries = mutableListOf<AtomEntry>()
        var insideEntry = false
        var insideAuthor = false

        var id = ""
        var title = ""
        var updated = ""
        var link = ""
        var content = ""
        var author = ""

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "entry" -> {
                            insideEntry = true
                            insideAuthor = false
                            id = ""
                            title = ""
                            updated = ""
                            link = ""
                            content = ""
                            author = ""
                        }

                        "author" -> if (insideEntry) {
                            insideAuthor = true
                        }

                        "id" -> if (insideEntry) {
                            id = parser.nextText().trim()
                        }

                        "title" -> if (insideEntry) {
                            title = parser.nextText().trim()
                        }

                        "updated" -> if (insideEntry) {
                            updated = parser.nextText().trim()
                        }

                        "content" -> if (insideEntry) {
                            content = parser.nextText().trim()
                        }

                        "name" -> if (insideEntry && insideAuthor) {
                            author = parser.nextText().trim()
                        }

                        "link" -> if (insideEntry) {
                            val href =
                                parser
                                    .getAttributeValue(null, "href")
                                    .orEmpty()
                            val rel =
                                parser
                                    .getAttributeValue(null, "rel")
                                    .orEmpty()

                            if (
                                href.isNotBlank() &&
                                (link.isBlank() || rel == "alternate")
                            ) {
                                link = href
                            }
                        }
                    }
                }

                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "author" -> insideAuthor = false

                        "entry" -> {
                            entries +=
                                AtomEntry(
                                    id = id,
                                    title = title,
                                    updated = updated,
                                    link = link,
                                    content = content,
                                    author = author,
                                )
                            insideEntry = false
                            insideAuthor = false
                        }
                    }
                }
            }

            event = parser.next()
        }

        return entries
    }

    private suspend fun getFeed(url: String): HttpResponse =
        client.get(url) {
            headers {
                append(
                    "Accept",
                    "application/atom+xml, application/xml;q=0.9, " +
                        "text/xml;q=0.8, */*;q=0.5",
                )
                append("User-Agent", "capsule-android")
                append("Cache-Control", "no-cache")
            }
        }

    private fun releaseFromAtom(entry: AtomEntry): ReleaseInfo {
        val tagFromUrl =
            entry.link
                .substringAfter("/releases/tag/", "")
                .substringBefore('?')
                .substringBefore('#')
                .trim('/')

        val tag =
            tagFromUrl.ifBlank {
                entry.title
                    .removePrefix("capsule ")
                    .removePrefix("Capsule ")
                    .trim()
            }

        return ReleaseInfo(
            tagName = tag,
            name = entry.title.ifBlank { tag },
            body = entry.content.takeIf { it.isNotBlank() },
            publishedAt = entry.updated,
            htmlUrl =
                entry.link.ifBlank {
                    if (tag.isBlank()) {
                        "$RepositoryWeb/releases"
                    } else {
                        "$RepositoryWeb/releases/tag/$tag"
                    }
                },
        )
    }

    private fun commitFromAtom(entry: AtomEntry): GitCommit {
        val shaFromUrl =
            entry.link
                .substringAfter("/commit/", "")
                .substringBefore('/')
                .substringBefore('?')
                .substringBefore('#')

        val shaFromId =
            entry.id
                .substringAfterLast('/')
                .substringAfterLast(':')

        val sha =
            shaFromUrl
                .ifBlank { shaFromId }
                .take(7)
                .ifBlank { "commit" }

        return GitCommit(
            sha = sha,
            message = entry.title.lines().firstOrNull().orEmpty(),
            author = entry.author.ifBlank { "Unknown" },
            date = entry.updated,
            url = entry.link,
        )
    }

    private fun isNormalCapsuleRelease(release: ReleaseInfo): Boolean =
        !release.tagName
            .trim()
            .startsWith(
                "video-engine-",
                ignoreCase = true,
            )

    suspend fun getCachedReleases(): List<ReleaseInfo> =
        cachedReleases

    suspend fun getLatestVersionName(): Result<String> =
        getLatestReleaseInfo().map { latest ->
            latest.name.ifBlank { latest.tagName }
        }

    suspend fun getLatestReleaseNotes(): Result<String?> =
        getLatestReleaseInfo().map { it.body }

    suspend fun getLatestReleaseInfo(): Result<ReleaseInfo> =
        runCatching {
            val latest =
                getAllReleases()
                    .getOrThrow()
                    .firstOrNull()
                    ?: throw IllegalStateException(
                        "No capsule releases found",
                    )

            lastCheckTime = System.currentTimeMillis()
            latest
        }

    suspend fun getAllReleases(
        perPage: Int = 30,
        forceRefresh: Boolean = false,
    ): Result<List<ReleaseInfo>> =
        runCatching {
            val now = System.currentTimeMillis()
            val limit = perPage.coerceAtLeast(1)

            if (
                !forceRefresh &&
                lastReleaseFetchAt > 0L &&
                now - lastReleaseFetchAt < ReleaseCacheIntervalMs
            ) {
                lastCheckTime = now
                return@runCatching cachedReleases.take(limit)
            }

            val response = getFeed(ReleasesFeed)
            val status = response.status.value

            if (status == 404) {
                cachedReleases = emptyList()
                lastReleaseFetchAt = now
                lastCheckTime = now
                return@runCatching emptyList()
            }

            if (status !in 200..299) {
                if (cachedReleases.isNotEmpty()) {
                    lastCheckTime = now
                    return@runCatching cachedReleases.take(limit)
                }

                /*
                 * Do not leak GitHub REST-style HTTP 403 messages into UI.
                 */
                throw IllegalStateException(
                    "Could not reach capsule releases. " +
                        "Please try again later.",
                )
            }

            val parsed =
                parseAtomFeed(response.bodyAsText())
                    .map(::releaseFromAtom)
                    .filter {
                        it.tagName.isNotBlank() ||
                            it.name.isNotBlank()
                    }
                    .filter(::isNormalCapsuleRelease)

            cachedReleases = parsed
            lastReleaseFetchAt = now
            lastCheckTime = now

            parsed.take(limit)
        }

    suspend fun getCommitHistory(
        count: Int = 20,
        branch: String = "main",
    ): Result<List<GitCommit>> =
        runCatching {
            val safeBranch = branch.trim().ifBlank { "main" }
            val response =
                getFeed(
                    "$RepositoryWeb/commits/$safeBranch.atom",
                )
            val status = response.status.value

            if (status !in 200..299) {
                throw IllegalStateException(
                    "Commit history is temporarily unavailable",
                )
            }

            parseAtomFeed(response.bodyAsText())
                .map(::commitFromAtom)
                .take(count.coerceAtLeast(1))
        }

    /*
     * GitHub /releases/latest ignores prereleases, so automatic
     * video-engine-* candidate releases do not hijack this URL.
     */
    fun getLatestDownloadUrl(): String =
        "$RepositoryWeb/releases/latest"
}
