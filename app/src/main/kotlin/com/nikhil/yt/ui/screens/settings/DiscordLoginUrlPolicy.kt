package com.nikhil.yt.ui.screens.settings

import java.net.URI

/**
 * Retain the strict origin check for the eventual supported Discord OAuth
 * integration and for regression tests. The legacy user-token login remains
 * disabled; restoring this helper does NOT re-enable token scraping.
 */
internal fun isTrustedDiscordLoginUrl(raw: String?): Boolean {
    val uri = runCatching { URI(raw ?: return false) }.getOrNull() ?: return false
    return uri.scheme.equals("https", ignoreCase = true) &&
        (uri.host.equals("discord.com", ignoreCase = true) ||
            uri.host.equals("www.discord.com", ignoreCase = true)) &&
        uri.userInfo == null && (uri.port == -1 || uri.port == 443)
}
