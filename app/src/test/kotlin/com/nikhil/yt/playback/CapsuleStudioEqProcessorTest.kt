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
    @Test fun warmShapesVoiceAndDrumBodyWithoutADeepTopCut() {
        for (rate in listOf(44_100, 48_000)) {
            val warm = CapsuleEqPresets.WARM.sound
            val body = toneGainDb(warm, rate, 400)
            val voice = toneGainDb(warm, rate, 1_000)
            val air = toneGainDb(warm, rate, 6800)
            assertTrue("Body versus voice = ${body - voice} dB", body - voice in 2.0..5.0)
            assertTrue("Body versus air = ${body - air} dB", body - air in 3.4..6.0)
            val clear = CapsuleEqPresets.CLEAR.sound
            assertTrue(toneGainDb(clear, rate, 2_400) - toneGainDb(clear, rate, 60) > 3.5)
        }
    }

    @Test fun manualAndCharacterModesLeaveHeadroom() {
        val request = List(5) { if (it == 1) 600 else 0 }
        val curve = CapsuleStudioSound(capsuleManualEqLevels(request))
        assertEquals(request, curve.levelsMb)
        assertTrue(toneGainDb(curve, 48_000, 400) - toneGainDb(curve, 48_000, 3_800) > 5.0)
        for (rate in listOf(16_000, 44_100, 48_000, 96_000)) {
            for (preset in CapsuleEqPresets.entries) {
                for (hz in CAPSULE_STUDIO_FREQUENCIES_HZ.filter { it < rate / 2 }) {
                    val measured = toneGainDb(preset.sound, rate, hz)
                    assertTrue("${preset.id} at $rate Hz/$hz Hz = $measured dB", measured <= 0.10)
                }
            }
        }
    }

    @Test fun tapeRoundsLoudPeaksWithAStableQuietSignalAndNoMakeupGain() {
        assertEquals(1.0, capsuleTapeGain(0.0), 0.0)
        assertEquals(1.0, capsuleTapeGain(0.02), 0.001)
        assertTrue(capsuleTapeGain(0.8) in 0.71..0.77)
        for (i in -2000..2000) {
            val x = i / 1000.0
            assertTrue(capsuleTapeGain(x) in 0.68..1.0)
            assertEquals(capsuleTapeGain(x), capsuleTapeGain(-x), 0.0)
        }
    }

    @Test fun autoWaitsForRepeatedEvidenceAndChoosesForFrequencyBalance() {
        val rate = 48_000
        fun window(frequency: Int, amplitude: Double = 12000.0): ByteBuffer {
            val frames = rate * 5 / 4
            val buffer = ByteBuffer.allocate(frames * 4).order(ByteOrder.nativeOrder())
            repeat(frames) { i ->
                val sample = (amplitude * sin(2 * PI * frequency * i / rate)).roundToInt()
                buffer.putShort(sample.toShort()).putShort((-sample).toShort())
            }
            buffer.flip()
            return buffer
        }
        for ((hz, expected) in listOf(95 to CapsuleEqPresets.CLEAR, 620 to CapsuleEqPresets.WARM, 6800 to CapsuleEqPresets.TAPE)) {
            val analysis = CapsuleStudioAnalysis(rate, 2)
            val quiet = window(620, 0.0)
            assertEquals(null, analysis.observe(quiet))
            val pcm = window(hz)
            assertEquals(null, analysis.observe(pcm))
            assertEquals(0, pcm.position())
            assertEquals(expected, analysis.observe(pcm))
        }
        val changing = CapsuleStudioAnalysis(rate, 2)
        assertEquals(null, changing.observe(window(95)))
        assertEquals(null, changing.observe(window(6800)))
        assertEquals(CapsuleEqPresets.TAPE, changing.observe(window(6800)))
        assertTrue(!CapsuleEqPresets.brightAutoSound.mixer.enabled)
    }

    @Test fun tapeTransportMovesPitchInBothDirectionsAndKeepsTheFrameCount() {
        val rate = 48_000
        val hz = 800.0
        val input = ShortArray(rate * 3) { (9000 * sin(2 * PI * hz * it / rate)).roundToInt().toShort() }
        fun frequencies(amount: Int): List<Double> {
            val eq = CapsuleStudioEqFilter(rate, 1).apply {
                setMixer(CapsuleMixerSetting(CapsuleMixerEffect.TAPE, amount))
            }
            val output = render(eq, input)
            assertEquals(input.size, output.size)
            val crossings = ArrayList<Double>()
            for (i in rate / 4 until output.lastIndex) {
                val a = output[i].toDouble()
                val b = output[i + 1].toDouble()
                if (a <= 0.0 && b > 0.0) crossings.add(i - a / (b - a))
            }
            return crossings.zipWithNext { a, b -> rate / (b - a) }
        }
        val frequencies = frequencies(100)
        assertTrue(frequencies.min() < hz * 0.992)
        assertTrue(frequencies.max() > hz * 1.008)
        assertTrue(frequencies.all { it in hz * 0.978..hz * 1.022 })
        assertTrue(frequencies(25).all { it in hz * 0.995..hz * 1.005 })
    }

    @Test fun fullScaleTransitionsAreLimitedAndLeaveBothChannelsAligned() {
        val eq = CapsuleStudioEqFilter(48_000, 2)
        val left = ShortArray(2_400) { if (it % 5 == 0) Short.MIN_VALUE else Short.MAX_VALUE }
        eq.setCurve(CapsuleEqPresets.WARM.sound.levelsMb)
        fun play(effect: CapsuleMixerEffect): ShortArray {
            eq.setMixer(CapsuleMixerSetting(effect, 100))
            return render(eq, ShortArray(left.size * 2) { i -> if (i % 2 == 0) left[i / 2] else 0 })
        }
        for (out in listOf(play(CapsuleMixerEffect.OFF), play(CapsuleMixerEffect.TAPE))) {
            assertEquals(left.size * 2, out.size)
            assertTrue(out.indices.filter { it % 2 == 1 }.all { out[it] == 0.toShort() })
            assertTrue(out.maxOf { abs(it.toInt()) } <= (CAPSULE_EQ_PEAK_AMPLITUDE * 32768).roundToInt())
        }
    }

    @Test fun mixerActsOnPcmIndependentlyOfFiveBandTuning() {
        val rate = 48_000
        fun tone(frequency: Int, amplitude: Int) = ShortArray(rate) { i ->
            (amplitude * sin(2 * PI * frequency * i / rate)).roundToInt().toShort()
        }
        fun effect(mode: CapsuleMixerEffect, source: ShortArray, amount: Int = 100) =
            render(CapsuleStudioEqFilter(rate, 1).apply {
                setMixer(CapsuleMixerSetting(mode, amount))
            }, source)
        fun rms(pcm: ShortArray) = sqrt(pcm.drop(rate / 2).sumOf { it.toDouble() * it } / (rate / 2))

        val attack = tone(6_000, 19_000)
        val softened = effect(CapsuleMixerEffect.SOFT, attack)
        assertTrue(rms(softened) < 0.60 * rms(attack))
        assertTrue(rms(effect(CapsuleMixerEffect.SOFT, attack, 30)) > 1.3 * rms(softened))

        val fundamental = tone(1_000, 16_000)
        val sun = effect(CapsuleMixerEffect.SUN, fundamental)
        val harmonic = (rate / 2 until rate).sumOf { sun[it] * sin(2 * PI * 3_000 * it / rate) }
        assertTrue("Generated third harmonic = ${abs(harmonic) / (rate / 4)}",
            abs(harmonic) / (rate / 4) > 50.0)
        assertTrue(effect(CapsuleMixerEffect.SUN, fundamental, 0).contentEquals(fundamental))

        val vinyl = effect(CapsuleMixerEffect.VINYL, fundamental)
        assertTrue(vinyl.sliceArray(0 until rate / 3).contentEquals(fundamental.sliceArray(0 until rate / 3)))
        assertTrue((rate / 3 until rate / 2).any { vinyl[it] != fundamental[it] })
        assertTrue(vinyl.sliceArray(rate / 2 until rate).contentEquals(fundamental.sliceArray(rate / 2 until rate)))
        for (mode in CapsuleMixerEffect.entries) {
            assertTrue(effect(mode, ShortArray(rate)).all { it == 0.toShort() })
        }
    }

    @Test fun sharedPeakGuardLimitsFiveBandExtremesWithEachEffect() {
        for (rate in listOf(16_000, 44_100, 48_000, 96_000)) {
            val eq = CapsuleStudioEqFilter(rate, 2)
            eq.setCurve(capsuleManualEqLevels(listOf(160, -800, 550, -700, 320)))
            val source = ShortArray(rate) { i ->
                if ((i / 2 + i % 2) % 9 < 4) Short.MAX_VALUE else Short.MIN_VALUE
            }
            for (mode in CapsuleMixerEffect.entries) {
                eq.setMixer(CapsuleMixerSetting(mode, 100))
                val result = render(eq, source)
                assertEquals(source.size, result.size)
                assertTrue("${mode.id} at ${rate} Hz", result.all {
                    abs(it.toInt()) <= (CAPSULE_EQ_PEAK_AMPLITUDE * 32768).roundToInt()
                })
            }
        }
    }

    @Test fun fastEdgesReserveHeadroomForTheReconstructedWaveform() {
        val rate = 48_000
        val eq = CapsuleStudioEqFilter(rate, 1).apply { setCurve(listOf(0, 0, 25, 0, 1)) }
        // A 12 kHz sine can hit the same sample amplitude twice in a row,
        // while its peak between those samples is sqrt(2) times larger.
        val input = ShortArray(rate) { if (it % 4 < 2) Short.MAX_VALUE else (-32767).toShort() }
        val output = render(eq, input)
        val settled = output.copyOfRange(rate / 2, rate)
        val reconstructed = sqrt(2.0) * settled.maxOf { abs(it.toInt()) } / 32768.0
        assertTrue("Reconstructed amplitude $reconstructed", reconstructed < 0.9)
    }

    @Test fun bypassAndFlatCurveAreBitExactAndLowSampleRatesSkipUltrasonicBands() {
        val input = shortArrayOf(Short.MIN_VALUE, -100, 0, 100, Short.MAX_VALUE)
        val eq = CapsuleStudioEqFilter(16_000, 1)
        assertTrue(render(eq, input).contentEquals(input))
        eq.setCurve(List(5) { 0 })
        assertTrue(render(eq, input).contentEquals(input))
        eq.setCurve(List(5) { if (it == 4) 200 else 0 })
        assertTrue(render(eq, input).contentEquals(input))
    }

    @Test fun musicCrossfadeAndVideoUsePcmEqAndListeningCeiling() {
        fun source(name: String) = File("src/main/kotlin/com/nikhil/yt/playback/$name.kt").readText()
        val renderer = source("CapsuleAudioRenderersFactory")
        val service = source("MusicService")
        val video = source("video/CapsuleVideoCompanion")
        assertTrue(renderer.contains("super.getAudioProcessors() + CapsuleStudioEqProcessor(readStudioSound)"))
        assertTrue(service.contains("requested = enabled && !soundStudioNeedsPcm"))
        assertTrue(service.contains("overlapPlayerFactory = {"))
        assertTrue(service.contains("CapsuleAudioRenderersFactory(this, ::activeStudioSound)"))
        assertTrue(service.contains("readStudioSound = ::activeStudioSound"))
        assertTrue(video.contains("CapsuleAudioRenderersFactory(context, readStudioSound, useMusicBuffer = false)"))
        assertTrue(service.contains("hearingCareOutputVolume(base * transition, ceiling, fade)"))
        assertTrue(service.contains("addListener(musicRenderers)"))
        assertTrue(service.contains("addListener(overlapRenderers)"))
        assertTrue(video.contains("addListener(renderers)"))
        assertTrue(service.contains("videoCompanion?.setVolume(protectedVolume)"))
    }

    private fun toneGainDb(sound: CapsuleStudioSound, rate: Int, frequency: Int): Double {
        val samples = maxOf(rate / 6, rate / frequency * 8)
        val inPcm = ShortArray(samples) { i -> (8_000 * sin(2 * PI * frequency * i / rate)).roundToInt().toShort() }
        val eq = CapsuleStudioEqFilter(rate, 1).apply {
            setCurve(sound.levelsMb)
            setMixer(sound.mixer)
        }
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
