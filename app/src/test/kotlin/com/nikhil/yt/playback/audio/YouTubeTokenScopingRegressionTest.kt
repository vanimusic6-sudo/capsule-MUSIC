package com.nikhil.yt.playback.audio

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Static source checks: not an end-to-end attestation or Googlevideo test. */
class YouTubeTokenScopingRegressionTest {
    private fun source(name: String): String =
        listOf(File("src/main/kotlin/com/nikhil/yt/playback/audio/$name"),
            File("app/src/main/kotlin/com/nikhil/yt/playback/audio/$name"))
            .firstOrNull(File::isFile)?.readText() ?: error("Missing $name")

    @Test fun gvsTokenMustBeBoundToVideoAndNeverReusedFromPreferences() {
        val engine = source("CapsuleInnerTubeXPlayer.kt")
        val generator = source("potoken/PoTokenGenerator.kt")
        val result = source("potoken/PoTokenResult.kt")
        assertFalse(engine.contains("configuredGvs"))
        assertFalse(engine.contains("configuredPlayer"))
        assertTrue(engine.contains("videoId = videoId"))
        assertTrue(generator.contains("generatePoToken(videoId)"))
        assertTrue(result.contains("streamingDataPoToken = videoIdPoToken"))
    }
}
