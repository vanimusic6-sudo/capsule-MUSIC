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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w393dp-h851dp-xhdpi")
class CapsuleDownloadActionInteractionTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

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
        rule.onAllNodesWithText(label).onLast().performClick()
        rule.runOnIdle { assertEquals(1, removed) }
    }

    @Test fun stoppedDownloadOffersResumeOrRemoveWithoutNestedConfirmations() {
        var removed = 0
        var resumed = 0
        val downloadLabel = rule.activity.getString(R.string.action_download)
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
        rule.onAllNodesWithText(removeLabel).onLast().performClick()
        rule.onAllNodesWithText(removeLabel).onLast().performClick()
        rule.runOnIdle {
            assertEquals(1, removed)
            assertEquals(0, resumed)
        }
    }
}
