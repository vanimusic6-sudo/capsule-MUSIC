package com.nikhil.yt.ui.screens.settings

import androidx.datastore.preferences.core.emptyPreferences
import com.nikhil.yt.constants.AudioClientOrderKey
import com.nikhil.yt.constants.DarkModeKey
import com.nikhil.yt.constants.SettingsAdvancedModeKey
import com.nikhil.yt.constants.CropThumbnailToSquareKey
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TASK 07: the mode is a single UI-level DataStore preference. It does not
 * migrate or replace the settings that the playback engine actually reads.
 * Structural checks complement (not replace) device navigation testing.
 */
class SettingsModeRegressionTest {
    private fun source(file: String): String =
        listOf(File("src/main/kotlin/$file"), File("app/src/main/kotlin/$file"))
            .firstOrNull(File::isFile)?.readText() ?: error("Missing $file")

    @Test fun newInstallStartsInUserModeAndExplicitModeSurvives() {
        val settings = emptyPreferences().toMutablePreferences()
        assertFalse(settings[SettingsAdvancedModeKey] ?: false)
        settings[SettingsAdvancedModeKey] = true
        assertTrue(settings[SettingsAdvancedModeKey] ?: false)
        settings[SettingsAdvancedModeKey] = false
        assertFalse(settings[SettingsAdvancedModeKey] ?: true)
    }

    @Test fun switchingModesNeverRewritesPersistedFunctionalPreferences() {
        val settings = emptyPreferences().toMutablePreferences()
        settings[AudioClientOrderKey] = "TVHTML5_SIMPLY,WEB_REMIX"
        settings[CropThumbnailToSquareKey] = false
        settings[DarkModeKey] = "OFF"

        settings[SettingsAdvancedModeKey] = true
        settings[SettingsAdvancedModeKey] = false

        assertEquals("TVHTML5_SIMPLY,WEB_REMIX", settings[AudioClientOrderKey])
        assertEquals(false, settings[CropThumbnailToSquareKey])
        assertEquals("OFF", settings[DarkModeKey])
    }

    @Test fun userModeHasExactlySevenIndependentDestinations() {
        val home = source("com/nikhil/yt/ui/screens/settings/VeluneSettingsScreen.kt")
        val rows = Regex("""item\(key = "([^"]+)"\)""")
            .findAll(home).map { it.groupValues[1] }.toList()
        assertEquals(
            listOf(
                "appearance", "capsule_customize", "account", "listen_together",
                "player", "video", "content", "discord", "integration", "privacy",
                "storage", "backup", "developer", "about",
            ),
            rows,
        )
        val advancedOnly = setOf(
            "account", "listen_together", "video", "discord", "integration",
            "developer", "about",
        )
        assertEquals(
            listOf("appearance", "capsule_customize", "player", "content",
                   "privacy", "storage", "backup"),
            rows.filterNot { it in advancedOnly },
        )
        assertEquals(4, Regex("""(?m)^            if \(advancedSettings\) \{""").findAll(home).count())
        assertTrue(home.contains("SettingsAdvancedModeKey, false"))
    }

    @Test fun userScreensGateExtrasWithoutReplacingTheirHandlers() {
        val appearance = source("com/nikhil/yt/ui/screens/settings/AppearanceSettings.kt")
        val player = source("com/nikhil/yt/ui/screens/settings/PlayerSettings.kt")
        val content = source("com/nikhil/yt/ui/screens/settings/ContentSettings.kt")
        assertTrue(appearance.contains("SettingsAdvancedModeKey, false"))
        assertTrue(appearance.contains("if (advancedSettings) {"))
        assertTrue(player.contains("SettingsAdvancedModeKey, false"))
        assertTrue(player.contains("onAutoStartOnBluetoothChange"))
        assertTrue(player.contains("onAutoDownloadOnLikeChange"))
        assertTrue(content.contains("SettingsAdvancedModeKey, false"))
        assertTrue(content.contains("PreferenceGroupTitle(title = stringResource(R.string.app_language))"))
        assertTrue(content.contains("onHideExplicitChange"))
        assertTrue(content.contains("if (advancedSettings && proxyEnabled)"))
    }

    @Test fun obsoletePreferenceIdentifiersAreNoLongerReadBySettingsOrService() {
        val files = listOf(
            "com/nikhil/yt/ui/screens/settings/AppearanceSettings.kt",
            "com/nikhil/yt/ui/screens/settings/ContentSettings.kt",
            "com/nikhil/yt/MainActivity.kt",
            "com/nikhil/yt/App.kt",
            "com/nikhil/yt/playback/MusicService.kt",
            "com/nikhil/yt/viewmodels/ExploreViewModel.kt",
            "com/nikhil/yt/viewmodels/HomeViewModel.kt",
            "com/nikhil/yt/viewmodels/LibraryViewModels.kt",
            "com/nikhil/yt/viewmodels/LocalPlaylistViewModel.kt",
            "com/nikhil/yt/viewmodels/NewReleaseViewModel.kt",
            "com/nikhil/yt/viewmodels/OnlineSearchSuggestionViewModel.kt",
            "com/nikhil/yt/viewmodels/OnlineSearchViewModel.kt",
            "com/nikhil/yt/viewmodels/YouTubeBrowseViewModel.kt",
            "com/nikhil/yt/utils/ForYouSuggestionEngine.kt",
        )
        files.forEach { file ->
            val text = source(file)
            listOf("CapsuleBottomBarEnabledKey", "RandomThemeOnStartupKey",
                   "UseSystemFontKey", "HideVideoKey").forEach { obsolete ->
                assertFalse("$file retains removed key $obsolete", text.contains(obsolete))
            }
        }
    }
}
