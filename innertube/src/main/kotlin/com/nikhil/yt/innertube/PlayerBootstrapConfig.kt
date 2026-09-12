/*
 * Capsule MUSIC
 * Runtime bootstrap for YouTube HTML5 player identities.
 *
 * Web Embedded and TV are not fully described by a name/version pair. Their
 * public bootstrap pages provide short-lived context flags used by the player
 * endpoint. Keeping those flags out of the request makes YouTube return the
 * generic "reload the page" response even though the client itself is valid.
 *
 * GPL-3.0
 */

package com.nikhil.yt.innertube

import com.nikhil.yt.innertube.models.Context
import com.nikhil.yt.innertube.models.YouTubeClient
import com.nikhil.yt.innertube.models.YouTubeLocale
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

internal data class PlayerBootstrapConfig(
    val context: JsonObject? = null,
    val apiKey: String? = null,
    val signatureTimestamp: Int? = null,
    val encryptedHostFlags: String? = null,
) {
    val hasRuntimeData: Boolean
        get() =
            context != null ||
                apiKey != null ||
                signatureTimestamp != null ||
                encryptedHostFlags != null

    companion object {
        val EMPTY = PlayerBootstrapConfig()
    }
}

@OptIn(ExperimentalSerializationApi::class)
internal val PlayerRequestJson =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

internal fun playerBootstrapUrl(
    client: YouTubeClient,
    videoId: String,
): String? =
    when {
        client.isEmbedded ->
            "https://www.youtube.com/embed/$videoId?html5=1"

        client.clientName.equals("TVHTML5", ignoreCase = true) ->
            "https://www.youtube.com/tv"

        client.clientName.equals("MWEB", ignoreCase = true) ->
            "https://m.youtube.com"

        client.clientName.equals("WEB", ignoreCase = true) ->
            "https://www.youtube.com"

        else -> null
    }

/**
 * Reads every ytcfg.set({...}) block and merges it in declaration order.
 * A small balanced-object scanner is used instead of a broad regular
 * expression so braces and escaped quotes inside JSON strings are safe.
 */
internal fun parsePlayerBootstrapConfig(
    html: String,
    client: YouTubeClient,
): PlayerBootstrapConfig {
    var cursor = 0
    var merged = JsonObject(emptyMap())

    while (cursor < html.length) {
        val marker = html.indexOf("ytcfg.set", startIndex = cursor)
        if (marker < 0) break

        val openingParenthesis = html.indexOf('(', startIndex = marker + 9)
        if (openingParenthesis < 0) break

        val openingBrace = html.indexOf('{', startIndex = openingParenthesis + 1)
        if (openingBrace < 0) break

        val closingBrace = findJsonObjectEnd(html, openingBrace)
        if (closingBrace < 0) {
            cursor = openingBrace + 1
            continue
        }

        val block = html.substring(openingBrace, closingBrace + 1)
        val parsed =
            runCatching {
                PlayerRequestJson.parseToJsonElement(block) as? JsonObject
            }.getOrNull()

        if (parsed != null) {
            merged = mergeJsonObjects(merged, parsed)
        }
        cursor = closingBrace + 1
    }

    if (merged.isEmpty()) return PlayerBootstrapConfig.EMPTY

    val rawContext = merged["INNERTUBE_CONTEXT"] as? JsonObject
    val context =
        rawContext?.let {
            if (client.clientName.equals("TVHTML5", ignoreCase = true)) {
                removeTvAppInstallData(it)
            } else {
                it
            }
        }

    val encryptedHostFlags =
        (merged["WEB_PLAYER_CONTEXT_CONFIGS"] as? JsonObject)
            ?.values
            ?.asSequence()
            ?.mapNotNull { value ->
                (value as? JsonObject)
                    ?.get("encryptedHostFlags")
                    ?.asNonBlankString()
            }
            ?.firstOrNull()

    return PlayerBootstrapConfig(
        context = context,
        apiKey = merged["INNERTUBE_API_KEY"].asNonBlankString(),
        signatureTimestamp =
            (merged["STS"] as? JsonPrimitive)
                ?.let { primitive ->
                    primitive.intOrNull
                        ?: primitive.contentOrNull?.toIntOrNull()
                },
        encryptedHostFlags = encryptedHostFlags,
    )
}

internal fun buildPlayerRequestPayload(
    client: YouTubeClient,
    locale: YouTubeLocale,
    visitorData: String?,
    dataSyncId: String?,
    videoId: String,
    playlistId: String?,
    signatureTimestamp: Int?,
    poToken: String?,
    bootstrap: PlayerBootstrapConfig = PlayerBootstrapConfig.EMPTY,
): JsonObject {
    val baseContext =
        client
            .toContext(locale, visitorData, dataSyncId)
            .let { context ->
                if (client.isEmbedded) {
                    context.copy(
                        thirdParty =
                            Context.ThirdParty(
                                embedUrl = YouTubeClient.THIRD_PARTY_EMBED_URL,
                            ),
                    )
                } else {
                    context
                }
            }

    val baseContextJson =
        PlayerRequestJson
            .encodeToJsonElement(baseContext) as JsonObject
    val runtimeContext = bootstrap.context ?: JsonObject(emptyMap())

    /*
     * Runtime flags are allowed to extend the baseline, but identity, locale,
     * visitor and account fields always come from Capsule's explicit session.
     */
    val mergedContext =
        mergeJsonObjects(baseContextJson, runtimeContext)
            .toMutableMap()
            .apply {
                val runtimeClient = runtimeContext["client"] as? JsonObject
                    ?: JsonObject(emptyMap())
                val baseClient = baseContextJson["client"] as JsonObject
                this["client"] = mergeJsonObjects(runtimeClient, baseClient)

                baseContextJson["user"]?.let { this["user"] = it }
                baseContextJson["thirdParty"]?.let { this["thirdParty"] = it }
            }
            .let(::JsonObject)

    val effectiveSignatureTimestamp =
        signatureTimestamp ?: bootstrap.signatureTimestamp

    return buildJsonObject {
        put("context", mergedContext)
        put("videoId", videoId)
        playlistId?.let { put("playlistId", it) }
        put(
            "playbackContext",
            buildJsonObject {
                put(
                    "contentPlaybackContext",
                    buildJsonObject {
                        put("html5Preference", "HTML5_PREF_WANTS")
                        effectiveSignatureTimestamp?.let {
                            put("signatureTimestamp", it)
                        }
                        bootstrap.encryptedHostFlags?.let {
                            put("encryptedHostFlags", it)
                        }
                    },
                )
            },
        )
        poToken?.takeIf { it.isNotBlank() }?.let {
            put(
                "serviceIntegrityDimensions",
                buildJsonObject {
                    put("poToken", it)
                },
            )
        }
        put("contentCheckOk", true)
        put("racyCheckOk", true)
    }
}

private fun findJsonObjectEnd(
    text: String,
    start: Int,
): Int {
    var depth = 0
    var inString = false
    var escaped = false

    for (index in start until text.length) {
        val character = text[index]

        if (inString) {
            when {
                escaped -> escaped = false
                character == '\\' -> escaped = true
                character == '"' -> inString = false
            }
            continue
        }

        when (character) {
            '"' -> inString = true
            '{' -> depth += 1
            '}' -> {
                depth -= 1
                if (depth == 0) return index
            }
        }
    }

    return -1
}

private fun mergeJsonObjects(
    base: JsonObject,
    overlay: JsonObject,
): JsonObject {
    val merged = base.toMutableMap()

    overlay.forEach { (key, overlayValue) ->
        val baseValue = merged[key]
        merged[key] =
            if (baseValue is JsonObject && overlayValue is JsonObject) {
                mergeJsonObjects(baseValue, overlayValue)
            } else {
                overlayValue
            }
    }

    return JsonObject(merged)
}

private fun removeTvAppInstallData(context: JsonObject): JsonObject {
    val client = context["client"] as? JsonObject ?: return context
    val configInfo = client["configInfo"] as? JsonObject ?: return context
    if ("appInstallData" !in configInfo) return context

    val cleanConfigInfo =
        JsonObject(
            configInfo.filterKeys { it != "appInstallData" },
        )
    val cleanClient =
        JsonObject(
            client.toMutableMap().apply {
                this["configInfo"] = cleanConfigInfo
            },
        )

    return JsonObject(
        context.toMutableMap().apply {
            this["client"] = cleanClient
        },
    )
}

private fun JsonElement?.asNonBlankString(): String? =
    (this as? JsonPrimitive)
        ?.contentOrNull
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
