package com.nikhil.yt.playback.audio

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Structural guardrail; live playback still requires on-device testing. */
class CapsuleSabrSourceRegressionTest {
    private fun text(path: String): String =
        listOf(File("src/main/kotlin/com/nikhil/yt/$path"),
            File("app/src/main/kotlin/com/nikhil/yt/$path"))
            .firstOrNull(File::isFile)?.readText() ?: error("Missing $path")

    @Test fun sabrCanOnlyTravelViaItsOwnDataSource() {
        val engine = text("playback/audio/CapsuleInnerTubeXPlayer.kt")
        val service = text("playback/MusicService.kt")
        val source = text("playback/audio/CapsuleSabrDataSource.kt")
        assertTrue(engine.contains("allowSabr = true"))
        assertTrue(engine.contains("VISIONOS_SABR"))
        assertTrue(engine.contains("WEB_REMIX_SABR"))
        assertTrue(service.contains("CapsuleSabrRoutingDataSource("))
        assertTrue(service.contains("CapsuleSabrOpenContext(bootstrap)"))
        assertTrue(source.contains("SabrAudioStream("))
        assertTrue(source.contains("chunk.endRangeExclusive"))
        assertTrue(source.contains("Channel(Channel.RENDEZVOUS)"))
        assertFalse(source.contains("ByteArrayOutputStream"))
    }
}
