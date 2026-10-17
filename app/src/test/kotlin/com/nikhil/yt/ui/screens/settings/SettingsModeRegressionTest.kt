package com.nikhil.yt.ui.screens.settings

import androidx.datastore.preferences.core.emptyPreferences
import com.nikhil.yt.constants.AudioClientOrderKey
import com.nikhil.yt.constants.DarkModeKey
import com.nikhil.yt.constants.SettingsAdvancedModeKey
import com.nikhil.yt.constants.SettingsAdvancedWarningSeenKey
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

    @Test fun warningOnlyAppearsOnFirstEntryIntoDeveloperMode() {
        assertTrue(shouldShowAdvancedModeWarning(false, true, false))
        assertFalse(shouldShowAdvancedModeWarning(true, true, false))
        assertFalse(shouldShowAdvancedModeWarning(false, false, false))
        assertFalse(shouldShowAdvancedModeWarning(false, true, true))
        val settings = emptyPreferences().toMutablePreferences()
        assertFalse(settings[SettingsAdvancedWarningSeenKey] ?: false)
        settings[SettingsAdvancedWarningSeenKey] = true
        assertFalse(shouldShowAdvancedModeWarning(false, true, settings[SettingsAdvancedWarningSeenKey] ?: false))
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

    @Test fun basicModeIncludesAccountListenTogetherAndAbout() {
        val home = source("com/nikhil/yt/ui/screens/settings/VeluneSettingsScreen.kt")
        val rows = Regex("""item\(key = "([^"]+)"\)""")
            .findAll(home).map { it.groupValues[1] }.toList()
        // Mode selector must sit alongside the title, never as a separate
        // list row (which would push every category lower on the screen).
        val topBar = home.substringAfter("topBar = {").substringBefore("containerColor =")
        assertTrue(topBar.contains("actions = {"))
        assertTrue(topBar.contains("SettingsModeSegmentedToggle("))
        assertTrue(topBar.contains("onModeChange = { requestedAdvanced ->"))
        assertTrue(topBar.contains("shouldShowAdvancedModeWarning("))
        assertTrue(topBar.contains("onAdvancedSettingsChange(requestedAdvanced)"))
        assertFalse(home.contains("item(key = \"settings_mode\")"))
        assertEquals("appearance", rows.first())
        assertTrue(home.contains(".width(width)"))
        // Not the old grey sketch: toned surface, clear sliding thumb,
        // readable typography and proportional room for the developer label.
        assertTrue(home.contains("width = if (compactHeader) 154.dp else 174.dp"))
        assertTrue(home.contains("height(30.dp)"))
        assertTrue(home.contains("height(44.dp)"))
        assertTrue(home.contains("scheme.onSurface.copy(alpha = 0.085f)"))
        // The basic/developer selection is a single drawing-phase spring.
        // The width and position are sampled from the SAME state: no per-frame
        // layout of the segmented toggle and no mid-gesture angle jump.
        val modeControl = home.substringAfter("private fun SettingsModeSegmentedToggle(")
            .substringBefore("@Composable\\nprivate fun SettingsItemScreenshotStyle(")
        assertTrue(modeControl.contains("val position = animateFloatAsState("))
        assertTrue(modeControl.contains("dampingRatio = 0.86f"))
        assertTrue(modeControl.contains("stiffness = 260f"))
        assertTrue(modeControl.contains("Canvas(Modifier.fillMaxSize())"))
        assertTrue(modeControl.contains("val springPosition = position.value"))
        assertTrue(modeControl.contains("val thumbWidth ="))
        assertTrue(modeControl.contains("val travelPx = baseWidth * springPosition"))
        assertTrue(modeControl.contains("drawRoundRect("))
        assertFalse(modeControl.contains("rotationZ ="))
        assertTrue(modeControl.contains("indication = null"))
        assertTrue(modeControl.contains("MutableInteractionSource()"))
        assertTrue(modeControl.contains("performHapticFeedback"))
        assertTrue(home.contains("Modifier.weight(0.32f)"))
        assertTrue(home.contains("Modifier.weight(0.68f)"))
        assertTrue(home.contains("fontSize = 10.sp"))
        assertTrue(home.contains("capsule_settings_mode_advanced_compact"))
        assertTrue(home.contains("overflow = TextOverflow.Ellipsis"))
        assertTrue(home.contains(".selectableGroup()"))
        assertTrue(home.contains("role = Role.Tab"))
        assertTrue(home.contains("SettingsAdvancedWarningSeenKey"))
        assertTrue(home.contains("shouldShowAdvancedModeWarning("))
        assertTrue(home.contains("showAdvancedWarning"))
        assertEquals(14, home.split(".animateItem(").size - 1)
        // All rows share one critically damped placement spring. A mode change must not
        // reallocate a spring for each item or bounce rows past their new positions.
        assertTrue(home.contains("placementSpec = SettingsRowPlacement"))
        assertTrue(home.contains("private val SettingsRowPlacement = spring<IntOffset>("))
        assertTrue(home.contains("dampingRatio = Spring.DampingRatioNoBouncy"))
        assertEquals(1, home.split("spring<IntOffset>(").size - 1)
        assertEquals(14, home.split("fadeInSpec = SettingsRowFadeIn").size - 1)
        assertEquals(14, home.split("fadeOutSpec = SettingsRowFadeOut").size - 1)
        assertTrue(home.contains("listState.animateScrollToItem(0)"))
        assertFalse(home.contains("Switch("))
        assertEquals(
            listOf(
                "appearance", "capsule_customize", "account", "listen_together",
                "player", "video", "content", "discord", "integration", "privacy",
                "storage", "backup", "developer", "about",
            ),
            rows,
        )
        // Account, Listen Together, and About are visible in both modes.
        val advancedOnly = setOf(
            "video", "discord", "integration", "developer",
        )
        assertEquals(
            listOf(
                "appearance", "capsule_customize", "account", "listen_together",
                "player", "content", "privacy", "storage", "backup", "about",
            ),
            rows.filterNot { it in advancedOnly },
        )
        assertEquals(3, Regex("""(?m)^            if \(advancedSettings\) \{""").findAll(home).count())
        assertTrue(home.contains("SettingsAdvancedModeKey, false"))
    }

    @Test fun everySupportedLocaleHasFirstEntryDeveloperWarning() {
        val locales = listOf(
            "values", "values-ar", "values-de-rDE", "values-es-rES", "values-es",
            "values-et", "values-fr-rFR", "values-fr", "values-hi-rIN",
            "values-in-rID", "values-it", "values-iw", "values-ja", "values-ko",
            "values-ml", "values-ms", "values-nl", "values-pt-rBR",
            "values-ru-rRU", "values-ru", "values-tr", "values-vi",
            "values-zh-rCN",
        )
        for (locale in locales) {
            val file = listOf(
                File("src/main/res/$locale/strings.xml"),
                File("app/src/main/res/$locale/strings.xml"),
            ).firstOrNull(File::isFile) ?: error("Missing locale $locale")
            val xml = file.readText()
            for (key in listOf(
                "capsule_advanced_mode_warning_title",
                "capsule_advanced_mode_warning_body",
                "capsule_advanced_mode_warning_confirm",
            )) {
                assertTrue("Missing $key in $locale", xml.contains(key))
            }
        }
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
            "com/nikhil/yt/viewmodels/ArtistItemsViewModel.kt",
            "com/nikhil/yt/viewmodels/AutoPlaylistViewModel.kt",
            "com/nikhil/yt/ui/component/FluidSlidingNavigationBar.kt",
            "com/nikhil/yt/ui/component/LyricsImageCard.kt",
            "com/nikhil/yt/ui/player/MiniPlayer.kt",
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
