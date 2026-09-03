package com.nikhil.yt.innertube

import org.junit.Assert.assertEquals
import org.junit.Test

class YouTubeFailureClassifierTest {
    @Test
    fun httpCodeWinsOverLocalizedText() {
        assertEquals(
            YouTubeFailureKind.RATE_LIMITED,
            YouTubeFailureClassifier.classify(
                httpStatusCode = 429,
                text = "Совершенно локализованная строка",
            ),
        )
        assertEquals(
            YouTubeFailureKind.FORBIDDEN,
            YouTubeFailureClassifier.classify(
                httpStatusCode = 403,
                text = "Доступ запрещён",
            ),
        )
        assertEquals(
            YouTubeFailureKind.TRANSIENT,
            YouTubeFailureClassifier.classify(
                httpStatusCode = 503,
                text = "Сервис временно недоступен",
            ),
        )
    }

    @Test
    fun ageStatusNeverBecomesBotCheck() {
        assertEquals(
            YouTubeFailureKind.AGE_RESTRICTED,
            YouTubeFailureClassifier.classify(
                playabilityStatus = "AGE_VERIFICATION_REQUIRED",
                text = "Войдите, чтобы подтвердить возраст",
            ),
        )
        assertEquals(
            YouTubeFailureKind.AGE_RESTRICTED,
            YouTubeFailureClassifier.classify(
                playabilityStatus = "LOGIN_REQUIRED",
                text = "Sign in to confirm your age",
            ),
        )
    }

    @Test
    fun genericLoginIsNotBotCheck() {
        assertEquals(
            YouTubeFailureKind.LOGIN_REQUIRED,
            YouTubeFailureClassifier.classify(
                playabilityStatus = "LOGIN_REQUIRED",
                text = "Please sign in to continue",
            ),
        )
    }

    @Test
    fun explicitBotLanguageStillTrips() {
        assertEquals(
            YouTubeFailureKind.BOT_CHECK,
            YouTubeFailureClassifier.classify(
                playabilityStatus = "LOGIN_REQUIRED",
                text = "Sign in to confirm you're not a bot",
            ),
        )
        assertEquals(
            YouTubeFailureKind.BOT_CHECK,
            YouTubeFailureClassifier.classify(
                text = "Подтвердите, что вы не робот",
            ),
        )
        assertEquals(
            YouTubeFailureKind.BOT_CHECK,
            YouTubeFailureClassifier.classify(
                playabilityStatus = "LOGIN_REQUIRED",
                text = "Войдите в аккаунт, чтобы подтвердить, что вы не бот",
            ),
        )
    }

    @Test
    fun explicitBotBodyWinsOver401And403() {
        assertEquals(
            YouTubeFailureKind.BOT_CHECK,
            YouTubeFailureClassifier.classify(
                httpStatusCode = 403,
                text = "Sign in to confirm you're not a bot",
            ),
        )
        assertEquals(
            YouTubeFailureKind.BOT_CHECK,
            YouTubeFailureClassifier.classify(
                httpStatusCode = 401,
                text = "Подтвердите, что вы не робот",
            ),
        )
    }
}
