package com.nikhil.yt.viewmodels

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class BackupSecurityTest {
    @Test fun credentialsNeverEnterManualBackupOrRestore() {
        for (key in listOf("InnerTubeCookie", "DiscordToken", "PoTokenGvs", "AccountEmail",
            "VisitorData", "OAuthRefresh", "API_KEY", "DataSyncId")) {
            assertFalse(key, BackupSecurity.exportablePreference(key))
        }
        for (key in listOf("darkMode", "playerBackground", "shuffle", "volumeNormalization")) {
            assertTrue(key, BackupSecurity.exportablePreference(key))
        }
    }

    @Test fun oversizeInputIsRejected() {
        assertThrows(IOException::class.java) {
            BackupSecurity.copyBounded(ByteArrayInputStream(ByteArray(101)), ByteArrayOutputStream(), 100)
        }
    }

    @Test fun exactSizeCopies() {
        val target = ByteArrayOutputStream()
        assertEquals(1024L, BackupSecurity.copyBounded(ByteArrayInputStream(ByteArray(1024)), target, 1024))
        assertEquals(1024, target.size())
    }
}
