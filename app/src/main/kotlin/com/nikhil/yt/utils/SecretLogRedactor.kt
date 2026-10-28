package com.nikhil.yt.utils

/**
 * Defense in depth for user-exported debug logs. Suppress likely credential
 * assignments and URL query parameters without changing network requests.
 * Best-effort only: callers must NEVER log raw credentials in the first place.
 */
internal object SecretLogRedactor {
    private val assignments = Regex(
        """(?i)(\b(?:authorization|set-cookie|cookie|access_token|refresh_token|po_token|potoken|discordtoken|password|secret|sapisid|api[_-]?key)\s*[:=]\s*)([^\s&,;]+)"""
    )
    private val bearer = Regex("""(?i)(\bBearer\s+)[A-Za-z0-9._~+/-]{12,}""")
    private val sessionInUri = Regex("""(?i)([?&](?:key|token|access_token|refresh_token|sid)=)[^&#\s]+""")

    fun redact(message: String): String =
        bearer.replace(
            sessionInUri.replace(assignments.replace(message, "$1[REDACTED]"), "$1[REDACTED]"),
            "$1[REDACTED]"
        )
}
