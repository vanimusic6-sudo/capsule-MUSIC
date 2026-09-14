package com.nikhil.yt.utils

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.nikhil.yt.constants.ChipSortTypeKey
import com.nikhil.yt.constants.LibraryFilter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A screen whose shape depends on a preference must be the right shape on its first frame.
 *
 * DataStore's flow is asynchronous, so seeding Compose state with the *default* meant the library
 * opened on the stock tab and jumped to the saved one a frame or more later — and the same flash
 * applied to anything else keyed off a setting. [PreferenceStore] already primes an in-memory
 * snapshot for the synchronous delegates; these helpers now read it too.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35, 36], application = Application::class)
class PreferenceInitialValueTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val context: Application = ApplicationProvider.getApplicationContext()

    @Before fun primeStore() {
        runBlocking {
            context.dataStore.edit { it[ChipSortTypeKey] = LibraryFilter.PLAYLISTS.name }
        }
        PreferenceStore.start(context)
        awaitSnapshot(ChipSortTypeKey, LibraryFilter.PLAYLISTS.name)
    }

    /**
     * PreferenceStore.start() primes the snapshot once per process, so a key written after the
     * first call reaches it through the background collector instead. This test is about what the
     * Compose helpers read *from* a primed snapshot, so wait for the value to be in it rather than
     * racing the collector — otherwise the test measures scheduling luck.
     */
    private fun awaitSnapshot(key: Preferences.Key<String>, expected: String) {
        val deadlineMs = System.currentTimeMillis() + SNAPSHOT_TIMEOUT_MS
        while (PreferenceStore.get(key) != expected) {
            check(System.currentTimeMillis() < deadlineMs) {
                "PreferenceStore snapshot never observed $key"
            }
            Thread.sleep(SNAPSHOT_POLL_MS)
        }
    }

    @Test fun anEnumPreferenceIsCorrectOnTheVeryFirstComposition() {
        val seen = mutableListOf<LibraryFilter>()

        compose.setContent {
            val filter by rememberEnumPreference(ChipSortTypeKey, LibraryFilter.LIBRARY)
            seen += filter
        }
        compose.waitForIdle()

        // Never the default: rendering LIBRARY even once is the flash of the stock tab.
        assertEquals(
            "the saved filter was not used for the first frame",
            LibraryFilter.PLAYLISTS,
            seen.first(),
        )
        assertEquals(LibraryFilter.PLAYLISTS, seen.last())
    }

    @Test fun aPlainPreferenceIsCorrectOnTheVeryFirstComposition() {
        val key = stringPreferencesKey("capsule.test.first.frame")
        runBlocking { context.dataStore.edit { it[key] = "stored" } }
        PreferenceStore.start(context)
        awaitSnapshot(key, "stored")

        val seen = mutableListOf<String>()
        compose.setContent {
            val value by rememberPreference(key, "fallback")
            seen += value
        }
        compose.waitForIdle()

        assertEquals("fallback was rendered before the stored value", "stored", seen.first())
    }

    @Test fun anUnwrittenPreferenceStillFallsBackToItsDefault() {
        val key = stringPreferencesKey("capsule.test.never.written")

        val seen = mutableListOf<String>()
        compose.setContent {
            val value by rememberPreference(key, "fallback")
            seen += value
        }
        compose.waitForIdle()

        assertEquals("fallback", seen.first())
        assertEquals("fallback", seen.last())
    }

    private companion object {
        const val SNAPSHOT_TIMEOUT_MS = 5_000L
        const val SNAPSHOT_POLL_MS = 10L
    }
}
