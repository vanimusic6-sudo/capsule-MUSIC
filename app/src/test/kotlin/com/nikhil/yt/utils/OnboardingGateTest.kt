package com.nikhil.yt.utils

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.nikhil.yt.constants.OnboardingCompletedKey
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The welcome flow appears on a genuinely first launch, and never again.
 *
 * Both halves of that are decided on the first composed frame, which is why this is a test about
 * frames rather than about a boolean. The flow is drawn when the flag is false, so a flag that
 * arrives a frame late means an update opens on a flash of the welcome screen; and a flag that
 * reads true too early on a clean install means a first launch never sees it at all.
 *
 * It rides [PreferenceStore]'s primed snapshot for that, so the setup is the same as
 * PreferenceInitialValueTest's: reset the process-wide singleton, write before starting it, and
 * start it against this test's own context.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class OnboardingGateTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val context: Application = ApplicationProvider.getApplicationContext()

    private fun startStore(completed: Boolean?) {
        PreferenceStore.resetForTesting()
        runBlocking {
            context.dataStore.edit { prefs ->
                if (completed == null) {
                    prefs.remove(OnboardingCompletedKey)
                } else {
                    prefs[OnboardingCompletedKey] = completed
                }
            }
        }
        PreferenceStore.start(context)
    }

    private fun firstFrames(): List<Boolean> {
        val seen = mutableListOf<Boolean>()
        compose.setContent {
            val completed by rememberPreference(OnboardingCompletedKey, defaultValue = false)
            seen += completed
        }
        compose.waitForIdle()
        return seen
    }

    @Test fun aCleanInstallShowsTheWelcomeFlowFromTheFirstFrame() {
        startStore(completed = null)
        val seen = firstFrames()
        assertEquals("nothing stored means the flow has not run", listOf(false), seen.distinct())
    }

    @Test fun anAppThatHasAlreadyRunItNeverFlashesIt() {
        startStore(completed = true)
        val seen = firstFrames()
        // The important word is "first": a false on frame one is a visible flash of the welcome
        // screen on every launch, even though it settles a frame later.
        assertEquals("the very first frame already knows", true, seen.first())
        assertEquals("and it never goes back", listOf(true), seen.distinct())
    }
}
