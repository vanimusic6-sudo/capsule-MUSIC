package com.nikhil.yt.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

class CapsuleStudioEqProcessorTest {
    @Test fun broadPresetsProduceAudibleContrastInPcm() {
        val bass = capsuleMusicalEqLevels(CapsuleEqPresets.byId("soft_bass_v2")!!.referenceLevelsMb)
        val vocals = capsuleMusicalEqLevels(CapsuleEqPresets.byId("vocal_v2")!!.referenceLevelsMb)
        val air = capsuleMusicalEqLevels(CapsuleEqPresets.byId("air_v2")!!.referenceLevelsMb)
        for (rate in listOf(44_100, 48_000)) {
            assertTrue(toneGainDb(bass, rate, 160) - toneGainDb(bass, rate, 5_000) > 7.0)
            assertTrue(toneGainDb(vocals, rate, 2_500) - toneGainDb(vocals, rate, 160) > 6.0)
            assertTrue(toneGainDb(air, rate, 5_000) - toneGainDb(air, rate, 315) > 5.0)
        }
    }

    @Test fun oneManualBandHasAnAudibleEffectAndTheCurveAddsNoOverallSignalGain() {
        val request = List(10) { if (it == 4) 520 else 0 }
        val curve = capsuleMusicalEqLevels(request)
        assertTrue(toneGainDb(curve, 48_000, 630) - toneGainDb(curve, 48_000, 5_000) > 4.0)
        for (rate in listOf(16_000, 44_100, 48_000, 96_000)) {
            for (preset in CapsuleEqPresets.all) {
                val profile = capsuleMusicalEqLevels(preset.referenceLevelsMb)
                for (hz in CAPSULE_STUDIO_FREQUENCIES_HZ.filter { it < rate / 2 }) {
                    val measured = toneGainDb(profile, rate, hz)
                    assertTrue("${preset.id} at $rate Hz/$hz Hz = $measured dB", measured <= 0.10)
                }
            }
        }
    }

    @Test fun fullScaleTransitionsAreLimitedAndLeaveBothChannelsAligned() {
        val eq = CapsuleStudioEqFilter(48_000, 2)
        val left = ShortArray(2_400) { if (it % 5 == 0) Short.MIN_VALUE else Short.MAX_VALUE }
        fun play(curve: List<Int>): ShortArray {
            eq.setCurve(curve)
            return render(eq, ShortArray(left.size * 2) { i -> if (i % 2 == 0) left[i / 2] else 0 })
        }
        val bass = capsuleMusicalEqLevels(CapsuleEqPresets.all.first().referenceLevelsMb)
        val vocals = capsuleMusicalEqLevels(CapsuleEqPresets.byId("vocal_v2")!!.referenceLevelsMb)
        for (out in listOf(play(bass), play(vocals))) {
            assertEquals(left.size * 2, out.size)
            assertTrue(out.indices.filter { it % 2 == 1 }.all { out[it] == 0.toShort() })
            assertTrue(out.maxOf { abs(it.toInt()) } <= (CAPSULE_EQ_PEAK_AMPLITUDE * 32768).roundToInt())
        }
    }

    @Test fun bypassAndFlatCurveAreBitExactAndLowSampleRatesSkipUltrasonicBands() {
        val input = shortArrayOf(Short.MIN_VALUE, -100, 0, 100, Short.MAX_VALUE)
        val eq = CapsuleStudioEqFilter(16_000, 1)
        assertTrue(render(eq, input).contentEquals(input))
        eq.setCurve(List(10) { 0 })
        assertTrue(render(eq, input).contentEquals(input))
        eq.setCurve(List(10) { if (it == 9) 260 else 0 })
        assertTrue(render(eq, input).contentEquals(input))
    }

    @Test fun musicCrossfadeAndVideoUsePcmEqAndListeningCeiling() {
        fun source(name: String) = File("src/main/kotlin/com/nikhil/yt/playback/$name.kt").readText()
        val renderer = source("CapsuleAudioRenderersFactory")
        val service = source("MusicService")
        val video = source("video/CapsuleVideoCompanion")
        assertTrue(renderer.contains("super.getAudioProcessors() + CapsuleStudioEqProcessor(readEqCurve)"))
        assertTrue(service.contains("requested = enabled && !soundStudioNeedsPcm"))
        assertTrue(service.contains("overlapPlayerFactory = {"))
        assertTrue(service.contains("CapsuleAudioRenderersFactory(this, { soundStudioCurve })"))
        assertTrue(service.contains("readEqCurve = { soundStudioCurve }"))
        assertTrue(video.contains("CapsuleAudioRenderersFactory(context, readEqCurve, useMusicBuffer = false)"))
        assertTrue(service.contains("hearingCareOutputVolume(base * transition, ceiling)"))
        assertTrue(service.contains("videoCompanion?.setVolume(protectedVolume)"))
    }

    private fun toneGainDb(curve: List<Int>, rate: Int, frequency: Int): Double {
        val samples = maxOf(rate / 6, rate / frequency * 8)
        val inPcm = ShortArray(samples) { i -> (8_000 * sin(2 * PI * frequency * i / rate)).roundToInt().toShort() }
        val eq = CapsuleStudioEqFilter(rate, 1).apply { setCurve(curve) }
        val out = render(eq, inPcm)
        fun rms(signal: ShortArray): Double {
            var sum = 0.0
            for (i in samples / 2 until samples) sum += signal[i].toDouble() * signal[i]
            return sqrt(sum / (samples - samples / 2))
        }
        return 20 * log10(rms(out) / rms(inPcm))
    }

    private fun render(eq: CapsuleStudioEqFilter, samples: ShortArray): ShortArray {
        val input = ByteBuffer.allocate(samples.size * Short.SIZE_BYTES).order(ByteOrder.nativeOrder())
        for (sample in samples) input.putShort(sample)
        input.flip()
        val output = ByteBuffer.allocate(input.remaining()).order(ByteOrder.nativeOrder())
        eq.process(input, output)
        output.flip()
        return ShortArray(output.remaining() / Short.SIZE_BYTES) { output.getShort() }
    }
}
