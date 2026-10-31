package com.nikhil.yt.playback

import android.app.Application
import android.app.Notification
import android.media.session.MediaSession
import android.os.Build
import com.nikhil.yt.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Assert the native Android MediaStyle payload that HONOR and SystemUI see.
 * Never use a normal service notification as a fallback media card.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class NativeMediaCardFallbackTest {
    @Test fun fallbackAttachesTheRealPlatformMediaTokenAndThreeControls() {
        val context = RuntimeEnvironment.getApplication()
        val platformSession = MediaSession(context, "capsule-native-card-contract")
        try {
            val notification = buildNativeMediaCardNotification(
                context = context,
                token = platformSession.sessionToken,
                title = "System media",
                artist = "Android",
                isPlaying = true,
            )
            assertEquals(Notification.CATEGORY_TRANSPORT, notification.category)
            assertNotNull(notification.extras?.getParcelable<android.os.Parcelable>(
                Notification.EXTRA_MEDIA_SESSION,
            ))
            assertEquals("System media", notification.extras.getCharSequence(Notification.EXTRA_TITLE))
            assertEquals(3, notification.actions.size)
            assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        } finally {
            platformSession.release()
        }
    }
}
