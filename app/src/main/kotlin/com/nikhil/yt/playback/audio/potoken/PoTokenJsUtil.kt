package com.nikhil.yt.playback.audio.potoken

import android.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

internal fun parseChallengeData(raw: String): String {
    val scrambled = Json.parseToJsonElement(raw).jsonArray
    val challenge =
        if (scrambled.size > 1 && scrambled[1].jsonPrimitive.isString) {
            Json.parseToJsonElement(descramble(scrambled[1].jsonPrimitive.content)).jsonArray
        } else {
            scrambled[0].jsonArray
        }

    val safeScript =
        challenge[1]
            .takeIf { it !is JsonNull }
            ?.jsonArray
            ?.firstOrNull { runCatching { it.jsonPrimitive.isString }.getOrDefault(false) }
            ?: JsonNull
    val trustedUrl =
        challenge[2]
            .takeIf { it !is JsonNull }
            ?.jsonArray
            ?.firstOrNull { runCatching { it.jsonPrimitive.isString }.getOrDefault(false) }
            ?: JsonNull

    return JsonObject(
        mapOf(
            "messageId" to JsonPrimitive(challenge[0].jsonPrimitive.content),
            "interpreterJavascript" to
                JsonObject(
                    mapOf(
                        "privateDoNotAccessOrElseSafeScriptWrappedValue" to safeScript,
                        "privateDoNotAccessOrElseTrustedResourceUrlWrappedValue" to trustedUrl,
                    ),
                ),
            "interpreterHash" to JsonPrimitive(challenge[3].jsonPrimitive.content),
            "program" to JsonPrimitive(challenge[4].jsonPrimitive.content),
            "globalName" to JsonPrimitive(challenge[5].jsonPrimitive.content),
            "clientExperimentsStateBlob" to JsonPrimitive(challenge[7].jsonPrimitive.content),
        ),
    ).toString()
}

internal fun parseIntegrityTokenData(raw: String): Pair<String, Long> {
    val array = Json.parseToJsonElement(raw).jsonArray
    return bytesToJavascriptArray(decodeYouTubeBase64(array[0].jsonPrimitive.content)) to
        array[1].jsonPrimitive.long
}

internal fun stringToJavascriptBytes(value: String): String =
    bytesToJavascriptArray(value.toByteArray(Charsets.UTF_8))

internal fun byteCsvToWebSafeBase64(value: String): String {
    val bytes =
        value
            .split(',')
            .filter { it.isNotBlank() }
            .map { it.trim().toInt().toByte() }
            .toByteArray()
    return Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.URL_SAFE).trimEnd('=')
}

private fun bytesToJavascriptArray(bytes: ByteArray): String =
    "new Uint8Array([${bytes.joinToString(",") { (it.toInt() and 0xFF).toString() }}])"

private fun descramble(value: String): String =
    decodeYouTubeBase64(value)
        .map { (it + 97).toByte() }
        .toByteArray()
        .toString(Charsets.UTF_8)

private fun decodeYouTubeBase64(value: String): ByteArray {
    var normalized =
        value
            .replace('-', '+')
            .replace('_', '/')
            .replace('.', '=')
    while (normalized.length % 4 != 0) normalized += "="
    return try {
        Base64.decode(normalized, Base64.DEFAULT)
    } catch (error: IllegalArgumentException) {
        throw PoTokenException("Cannot decode BotGuard base64", error)
    }
}
