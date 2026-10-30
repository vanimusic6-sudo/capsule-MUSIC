package com.nikhil.yt.ui.screens.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscordLoginOriginTest {
    @Test fun onlyOfficialHttpsOriginAllowed() {
        for (u in listOf("https://discord.com/login", "https://www.discord.com/channels/@me")) {
            assertTrue(u, isTrustedDiscordLoginUrl(u))
        }
        for (u in listOf("http://discord.com", "https://discord.com.evil.test/login",
            "https://evil.test/discord.com", "https://discord.com@evil.test/",
            "javascript:alert(1)", "file:///etc/passwd", "https://discord.com:8080",
            "https://sub.discord.com/login", "https://discord.com.attacker.org/")) {
            assertFalse(u, isTrustedDiscordLoginUrl(u))
        }
    }
}
