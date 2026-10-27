package com.nikhil.yt.ui.menu

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
        val label = rule.activity.getString(R.string.remove_download)
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
        val removeLabel = rule.activity.getString(R.string.remove_download)
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
        rule.onNodeWithText(rule.activity.getString(R.string.remove_download)).performClick()
        rule.runOnIdle { assertEquals(1, removed) }
    }

    @Test fun normalSongMenuStillShowsStopOrKeepOptions() {
        var removed = 0
        rule.setContent {
            MaterialTheme {
                CapsuleDownloadAction(
                    state = Download.STATE_DOWNLOADING,
                    onDownload = {},
                    onStop = {},
                    onRemove = { removed++ },
                    instantRemove = false,
                    actionKey = "not-player",
                )
            }
        }
        rule.onNodeWithText(rule.activity.getString(R.string.downloading)).performClick()
        rule.runOnIdle { assertEquals(0, removed) }
        rule.onNodeWithText(rule.activity.getString(R.string.capsule_download_stop_delete)).performClick()
        rule.runOnIdle { assertEquals(1, removed) }
    }

}
