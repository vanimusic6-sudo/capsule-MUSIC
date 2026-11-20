package com.nikhil.yt.playback

import javax.inject.Singleton
import org.junit.Assert.assertTrue
import org.junit.Test

/** DownloadService, MusicService and UI must share one Media3 download manager. */
class DownloadUtilSingletonTest {
    @Test fun downloadManagerOwnerIsScopedToTheApplication() {
        assertTrue(
            "DownloadUtil must be a singleton to avoid competing DownloadManagers for one index",
            DownloadUtil::class.java.isAnnotationPresent(Singleton::class.java),
        )
    }
}
