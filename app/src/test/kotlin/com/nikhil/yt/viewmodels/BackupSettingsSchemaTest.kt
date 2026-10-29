package com.nikhil.yt.viewmodels

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupSettingsSchemaTest {
    @Test fun acceptsOnlyKnownPreferenceTypes() {
        assertTrue(BackupSettingsSchema.isAllowed("darkMode", "string", "DARK"))
        assertTrue(BackupSettingsSchema.isAllowed("pureBlack", "boolean", "false"))
        assertFalse(BackupSettingsSchema.isAllowed("darkMode", "float", "1.0"))
        assertFalse(BackupSettingsSchema.isAllowed("pureBlack", "string", "true"))
        assertFalse(BackupSettingsSchema.isAllowed("attacker_extension", "string", "anything"))
    }

    @Test fun ignoresSecretsAndRejectsNumericPoisoning() {
        assertFalse(BackupSettingsSchema.isAllowed("innerTubeCookie", "string", "SID"))
        assertFalse(BackupSettingsSchema.isAllowed("proxyUrl", "string", "http://example.org"))
        assertFalse(BackupSettingsSchema.isAllowed("scrobbleDelayPercent", "float", "NaN"))
        assertFalse(BackupSettingsSchema.isAllowed("scrobbleDelayPercent", "float", "Infinity"))
        assertTrue(BackupSettingsSchema.isAllowed("scrobbleDelayPercent", "float", "0.5"))
        assertFalse(BackupSettingsSchema.isAllowed("launch_count", "int", "2147483648"))
    }
}
