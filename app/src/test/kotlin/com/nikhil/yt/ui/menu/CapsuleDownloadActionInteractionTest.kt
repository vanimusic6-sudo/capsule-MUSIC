package com.nikhil.yt.ui.menu

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.media3.exoplayer.offline.Download
import com.nikhil.yt.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w393dp-h851dp-xhdpi")
class CapsuleDownloadActionInteractionTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun clickLastMatching(label: String) {
        val matches = rule.onAllNodesWithText(label)
        matches[matches.fetchSemanticsNodes().lastIndex].performClick()
    }

    @Test fun completedDownloadRequiresOneConfirmationAndActuallyInvokesRemove() {
        var removed = 0
        val label = rule.activity.getString(R.string.delete)
        rule.setContent {
            MaterialTheme {
                CapsuleDownloadAction(
                    state = Download.STATE_COMPLETED,
                    onDownload = {},
                    onStop = {},
                    onRemove = { removed++ },
                    actionKey = "track-1",
                    collectionName = "Example",
                )
            }
        }
        rule.onNodeWithText(label).performClick()
        clickLastMatching(label)
        rule.runOnIdle { assertEquals(1, removed) }
    }

    @Test fun stoppedDownloadOffersResumeOrRemoveWithoutNestedConfirmations() {
        var removed = 0
        var resumed = 0
        val downloadLabel = rule.activity.getString(R.string.capsule_download_resume_partial)
        val removeLabel = rule.activity.getString(R.string.delete)
        rule.setContent {
            MaterialTheme {
                CapsuleDownloadAction(
                    state = Download.STATE_STOPPED,
                    hasStoredItems = true,
                    onDownload = { resumed++ },
                    onStop = {},
                    onRemove = { removed++ },
                    actionKey = "track-2",
                    collectionName = "Example",
                )
            }
        }
        rule.onNodeWithText(downloadLabel).performClick()
        // The chooser itself is confirmation: there must be no nested dialog.
        clickLastMatching(removeLabel)
        rule.runOnIdle {
            assertEquals(1, removed)
            assertEquals(0, resumed)
        }
    }
    @Test fun partiallySavedPlaylistLabelsNextActionDownloadRemaining() {
        val label = rule.activity.getString(R.string.capsule_download_remaining)
        rule.setContent {
            MaterialTheme {
                CapsuleDownloadAction(
                    state = Download.STATE_STOPPED,
                    hasStoredItems = true,
                    hasCompletedItems = true,
                    onDownload = {},
                    onStop = {},
                    onRemove = {},
                    actionKey = "playlist-partial",
                    collectionName = "My playlist",
                )
            }
        }
        rule.onNodeWithText(label).assertExists()
    }

    @Test fun fastSecondTapBeforeMedia3IndexUpdatesStillOffersDelete() {
        var starts = 0
        var deletes = 0
        val startLabel = rule.activity.getString(R.string.action_download)
        val deleteLabel = rule.activity.getString(R.string.capsule_download_stop_delete)
        rule.setContent {
            MaterialTheme {
                CapsuleDownloadAction(
                    state = Download.STATE_STOPPED,
                    onDownload = { starts++ },
                    onStop = {},
                    onRemove = { deletes++ },
                    actionKey = "still-queuing",
                    collectionName = "Queue",
                )
            }
        }
        rule.onNodeWithText(startLabel).performClick()
        rule.onNodeWithText(rule.activity.getString(R.string.downloading)).performClick()
        rule.onNodeWithText(deleteLabel).performClick()
        rule.runOnIdle {
            assertEquals(1, starts)
            assertEquals(1, deletes)
        }
    }

    @Test fun secondTapDuringDownloadOffersDeleteInsteadOfStoppingImmediately() {
        var stopped = 0
        var removed = 0
        val downloading = rule.activity.getString(R.string.downloading)
        val delete = rule.activity.getString(R.string.capsule_download_stop_delete)
        rule.setContent {
            MaterialTheme {
                CapsuleDownloadAction(
                    state = Download.STATE_DOWNLOADING,
                    onDownload = {},
                    onStop = { stopped++ },
                    onRemove = { removed++ },
                    actionKey = "active-track",
                    collectionName = "Test download",
                )
            }
        }
        rule.onNodeWithText(downloading).performClick()
        rule.runOnIdle {
            assertEquals("Second tap must not stop anything on its own", 0, stopped)
            assertEquals(0, removed)
        }
        rule.onNodeWithText(delete).performClick()
        rule.runOnIdle {
            assertEquals(0, stopped)
            assertEquals(1, removed)
        }
    }

    @Test fun secondTapDuringDownloadAlsoAllowsKeepingPartialBytes() {
        var stopped = 0
        var removed = 0
        rule.setContent {
            MaterialTheme {
                CapsuleDownloadAction(
                    state = Download.STATE_DOWNLOADING,
                    onDownload = {},
                    onStop = { stopped++ },
                    onRemove = { removed++ },
                    actionKey = "active-keep",
                    isCollection = false,
                )
            }
        }
        rule.onNodeWithText(rule.activity.getString(R.string.downloading)).performClick()
        rule.onNodeWithText(rule.activity.getString(R.string.capsule_download_pause_keep)).performClick()
        rule.runOnIdle {
            assertEquals(1, stopped)
            assertEquals(0, removed)
        }
    }


    @Test fun playerActiveDownloadSecondTapDeletesImmediatelyWithoutDialog() {
        var stopped = 0
        var removed = 0
        rule.setContent {
            MaterialTheme {
                CapsuleDownloadAction(
                    state = Download.STATE_DOWNLOADING,
                    onDownload = {},
                    onStop = { stopped++ },
                    onRemove = { removed++ },
                    instantRemove = true,
                    actionKey = "player-downloading",
                )
            }
        }
        rule.onNodeWithText(rule.activity.getString(R.string.downloading)).performClick()
        rule.runOnIdle {
            assertEquals(1, removed)
            assertEquals(0, stopped)
        }
        // The modal Delete/Stop chooser must NOT appear in Player More.
        assertTrue(
            rule.onAllNodesWithText(rule.activity.getString(R.string.capsule_download_stop_delete))
                .fetchSemanticsNodes().isEmpty()
        )
    }

    @Test fun playerCompletedDownloadDeletesOnFirstTapWithoutDialog() {
        var removed = 0
        rule.setContent {
            MaterialTheme {
                CapsuleDownloadAction(
                    state = Download.STATE_COMPLETED,
                    onDownload = {},
                    onStop = {},
                    onRemove = { removed++ },
                    instantRemove = true,
                    actionKey = "player-completed",
                )
            }
        }
        rule.onNodeWithText(rule.activity.getString(R.string.delete)).performClick()
        rule.runOnIdle { assertEquals(1, removed) }
    }

    @Test fun individualSongMenuDeletesOnTapWithoutStopChooser() {
        var removed = 0
        rule.setContent {
            MaterialTheme {
                CapsuleDownloadAction(
                    state = Download.STATE_DOWNLOADING,
                    onDownload = {},
                    onStop = {},
                    onRemove = { removed++ },
                    instantRemove = true,
                    actionKey = "not-player",
                    isCollection = false,
                )
            }
        }
        rule.onNodeWithText(rule.activity.getString(R.string.downloading)).performClick()
        rule.runOnIdle { assertEquals(1, removed) }
        assertTrue(rule.onAllNodesWithText(rule.activity.getString(R.string.capsule_download_pause_keep))
            .fetchSemanticsNodes().isEmpty())
    }

    @Test fun singleTrackFastSecondTapWorksBeforeMedia3IndexUpdates() {
        var starts = 0
        var removes = 0
        rule.setContent {
            MaterialTheme {
                CapsuleDownloadAction(
                    state = Download.STATE_STOPPED,
                    actionKey = "single-early-second-tap",
                    instantRemove = true,
                    isCollection = false,
                    onDownload = { starts++ },
                    onStop = {},
                    onRemove = { removes++ },
                )
            }
        }
        rule.onNodeWithText(rule.activity.getString(R.string.action_download)).performClick()
        rule.onNodeWithText(rule.activity.getString(R.string.downloading)).performClick()
        rule.runOnIdle { assertEquals(1, starts); assertEquals(1, removes) }
    }

    @Test fun stopOnFirstFrameIsAcknowledgedBeforeMedia3HasAnyEntry() {
        var started = 0
        var stopped = 0
        rule.setContent {
            MaterialTheme {
                CapsuleDownloadAction(
                    state = Download.STATE_STOPPED,
                    actionKey = "batch-early-stop-ui",
                    collectionName = "Playlist",
                    onDownload = { started++ },
                    onStop = { stopped++ },
                    onRemove = {},
                )
            }
        }
        rule.onNodeWithText(rule.activity.getString(R.string.action_download)).performClick()
        rule.onNodeWithText(rule.activity.getString(R.string.downloading)).performClick()
        rule.onNodeWithText(rule.activity.getString(R.string.capsule_download_pause_keep)).performClick()
        rule.runOnIdle { assertEquals(1, started); assertEquals(1, stopped) }
    }

    @Test fun stoppedPlaylistImmediatelyShowsResumeWithoutWaitingForMedia3() {
        var stopped = 0
        var resumed = 0
        val key = "stopped-aggregate-stays-active"
        rule.setContent {
            MaterialTheme {
                // Simulates a manual single song still transferring inside
                // this playlist after collection Stop was accepted.
                CapsuleDownloadAction(
                    state = Download.STATE_DOWNLOADING,
                    actionKey = key,
                    collectionName = "Mixed playlist",
                    hasStoredItems = true,
                    onDownload = { resumed++ },
                    onStop = { stopped++ },
                    onRemove = {},
                )
            }
        }
        rule.onNodeWithText(rule.activity.getString(R.string.downloading)).performClick()
        rule.onNodeWithText(rule.activity.getString(R.string.capsule_download_pause_keep)).performClick()
        rule.runOnIdle { assertEquals(1, stopped) }

        // A stale aggregate DOWNLOADING must never trap the UI in Stopping…
        rule.onNodeWithText(rule.activity.getString(R.string.capsule_download_resume_partial))
            .assertExists()
        assertTrue(rule.onAllNodesWithText(rule.activity.getString(R.string.capsule_download_stopping))
            .fetchSemanticsNodes().isEmpty())

        // User can immediately resume without waiting for a timeout or reboot.
        rule.onNodeWithText(rule.activity.getString(R.string.capsule_download_resume_partial))
            .performClick()
        clickLastMatching(rule.activity.getString(R.string.capsule_download_resume_partial))
        rule.runOnIdle { assertEquals(1, resumed) }
    }

    @Test fun collectionChoiceParenthesisIsAlwaysOnItsOwnLine() {
        assertEquals("Остановить\n(сохранить скачанное)",
            formatCollectionDownloadChoiceLabel("Остановить(сохранить скачанное)"))
        assertEquals("Остановить\n(сохранить скачанное)",
            formatCollectionDownloadChoiceLabel("Остановить (сохранить скачанное)"))
        assertEquals("Скачать оставшиеся",
            formatCollectionDownloadChoiceLabel("Скачать оставшиеся"))
    }

    @Test fun playlistHeroUsesTheSameStopKeepAndDeleteChooserAsMore() {
        var stopped = 0
        var removed = 0
        rule.setContent {
            MaterialTheme {
                androidx.compose.foundation.layout.Row {
                    CapsuleCollectionDownloadButton(
                        actionKey = "header-active-unique",
                        collectionName = "Playlist",
                        state = Download.STATE_DOWNLOADING,
                        hasStoredItems = true,
                        hasCompletedItems = false,
                        onDownload = {},
                        onStop = { stopped++ },
                        onRemove = { removed++ },
                    )
                }
            }
        }
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.downloading)).performClick()
        rule.onNodeWithText(
            formatCollectionDownloadChoiceLabel(
                rule.activity.getString(R.string.capsule_download_pause_keep)
            )
        ).performClick()
        rule.runOnIdle { assertEquals(1, stopped); assertEquals(0, removed) }
    }

    @Test fun removingCollectionHidesRedRemoveEvenWhileMedia3IndexIsStale() {
        var finished by androidx.compose.runtime.mutableStateOf(false)
        var deletes = 0
        val removeLabel = rule.activity.getString(R.string.delete)
        rule.setContent {
            MaterialTheme {
                CapsuleDownloadAction(
                    actionKey = "pending-collection-red-unique",
                    collectionName = "Playlist",
                    state = if (finished) Download.STATE_STOPPED else Download.STATE_COMPLETED,
                    hasStoredItems = !finished,
                    hasCompletedItems = !finished,
                    onDownload = {},
                    onStop = {},
                    onRemove = { deletes++ },
                )
            }
        }
        rule.onNodeWithText(removeLabel).performClick()
        clickLastMatching(removeLabel)
        rule.runOnIdle { assertEquals(1, deletes) }
        assertTrue(rule.onAllNodesWithText(removeLabel).fetchSemanticsNodes().isEmpty())
        rule.onNodeWithText(rule.activity.getString(R.string.capsule_download_removing)).assertExists()
        rule.runOnIdle { finished = true }
        rule.waitForIdle()
        rule.waitUntil(3_000L) {
            rule.onAllNodesWithText(rule.activity.getString(R.string.action_download))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun sharedPendingRemovePreventsOldRedMenuWhenOpeningAnotherSurface() {
        val key = "shared-remove-unique"
        var deleted = 0
        var showSecond by androidx.compose.runtime.mutableStateOf(false)
        rule.setContent {
            MaterialTheme {
                androidx.compose.foundation.layout.Column {
                    CapsuleDownloadAction(
                        actionKey = key,
                        state = Download.STATE_COMPLETED,
                        hasStoredItems = true,
                        onDownload = {},
                        onStop = {},
                        onRemove = { deleted++; showSecond = true },
                    )
                    if (showSecond) CapsuleDownloadAction(
                        actionKey = key,
                        state = Download.STATE_COMPLETED,
                        hasStoredItems = true,
                        onDownload = {},
                        onStop = {},
                        onRemove = {},
                    )
                }
            }
        }
        val remove = rule.activity.getString(R.string.delete)
        rule.onNodeWithText(remove).performClick()
        clickLastMatching(remove)
        rule.runOnIdle { assertEquals(1, deleted) }
        assertTrue(rule.onAllNodesWithText(remove).fetchSemanticsNodes().isEmpty())
        rule.onAllNodesWithText(rule.activity.getString(R.string.capsule_download_removing))
            .fetchSemanticsNodes().let { assertEquals(2, it.size) }
    }

}
