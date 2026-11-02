package com.nikhil.yt.ui.component

import kotlin.math.abs
import kotlin.math.roundToLong
import org.junit.Assert.*
import org.junit.Test

class LyricsPlaybackClockTest {
    @Test fun sparseMedia3UpdatesStillProduceContinuous60_90_120HzProgress() {
        for (hz in listOf(60, 90, 120)) {
            val clock = LyricsPlaybackClock()
            var previous = clock.update(10_000L, 0L, 1f, true, 180_000L)
            var previousSource = 10_000L
            var sourceChanges = 0
            val outputs = mutableSetOf(previous)
            for (frame in 1..hz * 10) {
                val now = (frame * 1_000_000_000.0 / hz).roundToLong()
                // The recording holds the fill for 16-17 frames at 60 Hz.
                val source = 10_000L + (now / 266_666_667L * 266_666_667L / 1_000_000L)
                if (source != previousSource) sourceChanges++
                val output = clock.update(source, now, 1f, true, 180_000L)
                assertTrue("$hz Hz must advance even while Media3 repeats a sample", output > previous)
                assertTrue("No 267 ms jump at the next source update", output - previous <= 1_000L / hz + 4L)
                assertTrue("Visual time stays synchronized with real elapsed playback", abs(output - (10_000L + now / 1_000_000L)) < 20L)
                outputs += output
                previous = output
                previousSource = source
            }
            assertEquals(hz * 10 + 1, outputs.size)
            assertTrue(sourceChanges < 40)
        }
    }

    @Test fun seeksAndTrackChangesRebaseImmediatelyInsteadOfAnimatingAcrossTheTimeline() {
        val clock = LyricsPlaybackClock()
        clock.update(20_000, 0, 1f, true, 180_000)
        clock.update(20_000, 16_000_000, 1f, true, 180_000)
        assertEquals(90_000L, clock.reset(90_000))
        assertEquals(90_000L, clock.update(90_000, 32_000_000, 1f, true, 180_000))
        assertEquals(1_000L, clock.update(1_000, 48_000_000, 1f, true, 180_000))
        assertEquals(0L, clock.reset(-1))
        assertEquals(0L, clock.update(0, 64_000_000, 1f, false, 180_000))
    }

    @Test fun pauseBufferingScrubbingAndSuppressionNeverExtrapolate() {
        val clock = LyricsPlaybackClock()
        clock.update(10_000, 0, 1f, true, 180_000)
        clock.update(10_000, 16_000_000, 1f, true, 180_000)
        for (frame in 2..120) assertEquals(10_010L,
            clock.update(10_010, frame * 16_666_667L, 1f, false, 180_000))
        assertEquals(45_000L, clock.update(45_000, 3_000_000_000, 1f, false, 180_000))
    }

    @Test fun playbackSpeedIsAppliedOnceAndDoesNotAccumulateDrift() {
        for (speed in listOf(0.5f, 1f, 1.5f, 2f)) {
            val clock = LyricsPlaybackClock()
            clock.update(10_000, 0, speed, true, -1)
            var value = 10_000L
            for (frame in 1..7_200) {
                val now = frame * 16_666_667L
                val sourceTime = now / 250_000_000L * 250_000_000L
                val source = 10_000L + (sourceTime / 1_000_000.0 * speed).roundToLong()
                value = clock.update(source, now, speed, true, -1)
                val expected = 10_000L + (now / 1_000_000.0 * speed).roundToLong()
                assertTrue("speed=$speed frame=$frame value=$value expected=$expected", abs(value - expected) < 20L)
            }
            assertTrue(value > 10_000)
        }
    }

    @Test fun durationAndInvalidClocksCannotRunPastTheTrackOrProduceInvalidPositions() {
        val clock = LyricsPlaybackClock()
        clock.update(990, 0, 1f, true, 1_000)
        assertEquals(1_000L, clock.update(990, 100_000_000, 1f, true, 1_000))
        assertEquals(500L, clock.update(500, 50_000_000, 1f, true, 1_000))
        assertEquals(500L, clock.update(500, 200_000_000, Float.NaN, true, 1_000))
        assertEquals(500L, clock.update(500, 300_000_000, 0f, true, 1_000))
    }
}
