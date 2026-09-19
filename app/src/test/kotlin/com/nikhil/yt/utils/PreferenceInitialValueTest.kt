package com.nikhil.yt.utils

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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

    /**
     * Every value this class reads is written *before* the store is started, and started against
     * this test's own context.
     *
     * Both halves matter. PreferenceStore is a process-wide singleton whose `started` flag makes
     * the first call the binding one, so without the reset a test gets a silent no-op and then
     * reads a snapshot fed from a previous test's DataStore — which is what made this class fail on
     * CI and pass locally, purely on execution order. And writing first means start()'s own
     * blocking initial read picks the values up, so nothing here waits on the asynchronous
     * collector. There is no polling, no sleep and no timeout left in this test: it either has the
     * snapshot before the first composition or it fails saying so.
     */
    @Before fun primeStore() {
        PreferenceStore.resetForTesting()
        runBlocking {
            context.dataStore.edit {
                it[ChipSortTypeKey] = LibraryFilter.PLAYLISTS.name
                it[StoredKey] = STORED_VALUE
            }
        }
        PreferenceStore.start(context)

        // A precondition, not an assertion about the code under test: if the snapshot is not primed
        // then this test cannot say anything about what Compose reads from a primed snapshot.
        check(PreferenceStore.get(ChipSortTypeKey) == LibraryFilter.PLAYLISTS.name) {
            "PreferenceStore did not prime its snapshot from this test's DataStore"
        }
        check(PreferenceStore.get(StoredKey) == STORED_VALUE) {
            "PreferenceStore did not prime its snapshot from this test's DataStore"
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
        val seen = mutableListOf<String>()
        compose.setContent {
            val value by rememberPreference(StoredKey, "fallback")
            seen += value
        }
        compose.waitForIdle()

        assertEquals("fallback was rendered before the stored value", STORED_VALUE, seen.first())
        assertEquals(STORED_VALUE, seen.last())
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
        val StoredKey = stringPreferencesKey("capsule.test.first.frame")
        const val STORED_VALUE = "stored"
    }
}
