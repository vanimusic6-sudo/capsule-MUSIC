package com.nikhil.yt.together

import com.nikhil.yt.BuildConfig

internal fun normalizeTogetherBearerToken(raw: String?): String? =
    raw?.trim()?.takeIf { it.isNotBlank() }

/**
 * Single source of configuration for the current Together online bearer.
 *
 * The value is injected at build time from local.properties/environment via
 * BuildConfig. It is configuration, not a secret boundary: anything shipped
 * in an APK can be extracted, so server-side authorization must not depend on
 * this value remaining confidential.
 */
internal object TogetherOnlineCredentials {
    fun bearerTokenOrNull(): String? =
        normalizeTogetherBearerToken(BuildConfig.TOGETHER_BEARER_TOKEN)
}
