package com.nikhil.yt.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurePreferencesDataStoreTest {
    @Test fun protectsOnlySensitiveValues() {
        assertTrue(SecurePreferencesDataStore.isSensitivePreference("innerTubeCookie"))
        assertTrue(SecurePreferencesDataStore.isSensitivePreference("discordToken"))
        assertTrue(SecurePreferencesDataStore.isSensitivePreference("LastFMSessionKey"))
        assertTrue(SecurePreferencesDataStore.isSensitivePreference("visitorData"))
        assertTrue(SecurePreferencesDataStore.isSensitivePreference("po_token"))
        assertTrue(SecurePreferencesDataStore.isSensitivePreference("proxyUrl"))
        assertTrue(SecurePreferencesDataStore.isSensitivePreference("oauthRefresh"))
        assertTrue(SecurePreferencesDataStore.isSensitivePreference("accountName"))
        assertTrue(SecurePreferencesDataStore.isSensitivePreference("clientApiKey"))
        assertTrue(SecurePreferencesDataStore.isSensitivePreference("togetherBearer"))
        assertFalse(SecurePreferencesDataStore.isSensitivePreference("playerBackground"))
        assertFalse(SecurePreferencesDataStore.isSensitivePreference("audioQuality"))
        assertFalse(SecurePreferencesDataStore.isSensitivePreference("theme"))
    }
}
