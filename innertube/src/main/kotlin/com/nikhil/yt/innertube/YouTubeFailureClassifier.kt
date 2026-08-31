/*
 * Capsule MUSIC
 * Locale-safe YouTube failure classification shared by AUDIO and VIDEO.
 *
 * Machine-readable signals win. Human-readable text is only a fallback.
 * Generic "sign in" is never treated as a bot-check by itself.
 * GPL-3.0
 */
package com.nikhil.yt.innertube

import java.util.Locale

enum class YouTubeFailureKind {
    NONE,
    RATE_LIMITED,
    BOT_CHECK,
    FORBIDDEN,
    TRANSIENT,
    LOGIN_REQUIRED,
    AGE_RESTRICTED,
    UNPLAYABLE,
    PERMANENT,
}

object YouTubeFailureClassifier {
    fun classify(
        httpStatusCode: Int? = null,
        playabilityStatus: String? = null,
        text: String? = null,
    ): YouTubeFailureKind {
        val normalizedStatus =
            playabilityStatus
                ?.trim()
                ?.uppercase(Locale.US)
                .orEmpty()
        val normalizedText = text.orEmpty().lowercase(Locale.US)

        when (httpStatusCode) {
            429 -> return YouTubeFailureKind.RATE_LIMITED
            401 -> return YouTubeFailureKind.LOGIN_REQUIRED
            403 -> return YouTubeFailureKind.FORBIDDEN
            in 500..599 -> return YouTubeFailureKind.TRANSIENT
        }

        when (normalizedStatus) {
            "OK" -> return YouTubeFailureKind.NONE

            "AGE_VERIFICATION_REQUIRED",
            "CONTENT_CHECK_REQUIRED",
            -> return YouTubeFailureKind.AGE_RESTRICTED

            "LOGIN_REQUIRED" -> {
                if (containsExplicitBotSignal(normalizedText)) {
                    return YouTubeFailureKind.BOT_CHECK
                }
                if (containsAgeSignal(normalizedText)) {
                    return YouTubeFailureKind.AGE_RESTRICTED
                }
                return YouTubeFailureKind.LOGIN_REQUIRED
            }

            "UNPLAYABLE" -> return YouTubeFailureKind.UNPLAYABLE

            "ERROR" -> {
                classifyText(normalizedText)
                    .takeIf { it != YouTubeFailureKind.NONE }
                    ?.let { return it }
                return YouTubeFailureKind.TRANSIENT
            }
        }

        return classifyText(normalizedText)
    }

    fun isExplicitBotCheck(text: String?): Boolean =
        containsExplicitBotSignal(text.orEmpty().lowercase(Locale.US))

    private fun classifyText(text: String): YouTubeFailureKind {
        if (text.isBlank()) return YouTubeFailureKind.NONE

        if (
            "http 429" in text ||
            "response code 429" in text ||
            "too many requests" in text ||
            "rate limit" in text ||
            "quota exceeded" in text ||
            "слишком много запросов" in text ||
            "превышен лимит" in text
        ) {
            return YouTubeFailureKind.RATE_LIMITED
        }

        if (containsExplicitBotSignal(text)) {
            return YouTubeFailureKind.BOT_CHECK
        }

        if (containsAgeSignal(text)) {
            return YouTubeFailureKind.AGE_RESTRICTED
        }

        if (
            "login required" in text ||
            "sign in required" in text ||
            "please sign in" in text ||
            "войдите в аккаунт" in text ||
            "требуется вход" in text
        ) {
            return YouTubeFailureKind.LOGIN_REQUIRED
        }

        if (
            "video unavailable" in text ||
            "is not available" in text ||
            "no longer available" in text ||
            "private video" in text ||
            "has been removed" in text ||
            "removed by the uploader" in text ||
            "not available in your country" in text ||
            "недоступно в вашей стране" in text ||
            "видео недоступно" in text ||
            "частное видео" in text
        ) {
            return YouTubeFailureKind.PERMANENT
        }

        if (
            "http 403" in text ||
            "response code 403" in text ||
            "forbidden" in text
        ) {
            return YouTubeFailureKind.FORBIDDEN
        }

        if (
            "timeout" in text ||
            "timed out" in text ||
            "connection reset" in text ||
            "unexpected end of stream" in text ||
            "unable to resolve host" in text ||
            "http 500" in text ||
            "http 502" in text ||
            "http 503" in text ||
            "http 504" in text
        ) {
            return YouTubeFailureKind.TRANSIENT
        }

        return YouTubeFailureKind.NONE
    }

    private fun containsExplicitBotSignal(text: String): Boolean =
        "not a bot" in text ||
            "bot detection" in text ||
            "unusual traffic" in text ||
            "captcha" in text ||
            "recaptcha" in text ||
            "confirm you're not a bot" in text ||
            "confirm you’re not a bot" in text ||
            "verify you're human" in text ||
            "verify you are human" in text ||
            "подтвердите, что вы не робот" in text ||
            "подтвердите что вы не робот" in text ||
            "необычный трафик" in text ||
            "проверка captcha" in text

    private fun containsAgeSignal(text: String): Boolean =
        ("age" in text && ("verify" in text || "verification" in text || "confirm" in text)) ||
            "age-restricted" in text ||
            "age restricted" in text ||
            "confirm your age" in text ||
            "подтвердите свой возраст" in text ||
            "подтвердить возраст" in text ||
            "возрастное ограничение" in text
}
