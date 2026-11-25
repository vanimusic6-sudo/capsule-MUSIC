package com.nikhil.yt.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.StreamMetadata
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
        assertTrue(capsuleTapeGain(0.8) in 0.69..0.73)
        for (i in -2000..2000) {
            val x = i / 1000.0
            assertTrue(capsuleTapeGain(x) in 0.63..1.0)
            assertEquals(capsuleTapeGain(x), capsuleTapeGain(-x), 0.0)
        }
    }

    @Test fun autoMeasuresBandsIndependentlyAndWaitsForAudibleContent() {
        val rate = 48_000
        fun window(frequency: Int, amplitude: Double = 12000.0): ByteBuffer {
            val frames = rate
            val buffer = ByteBuffer.allocate(frames * 4).order(ByteOrder.nativeOrder())
            repeat(frames) { i ->
                val sample = (amplitude * sin(2 * PI * frequency * i / rate)).roundToInt()
                buffer.putShort(sample.toShort()).putShort((-sample).toShort())
            }
            buffer.flip()
            return buffer
        }
        val curves = listOf(95, 420, 1_020, 2_900, 8_600).map { hz ->
            val analysis = CapsuleStudioAnalysis(rate, 2)
            val pcm = window(hz)
            assertEquals(null, analysis.observe(window(620, 0.0)))
            repeat(4) { assertEquals(null, analysis.observe(pcm)) }
            assertEquals(0, pcm.position())
            val curve = checkNotNull(analysis.observe(pcm)).levelsMb
            assertTrue(curve.zip(CAPSULE_STUDIO_FREQUENCIES_HZ).all { (level, band) ->
                level in capsuleStudioBandLimit(band) && level in -420..225
            })
            curve
        }
        assertEquals(5, curves.distinct().size)
        assertTrue(curves.first().first() < -100)
        assertTrue(curves.last().last() < -100)
        val silence = CapsuleStudioAnalysis(rate, 2)
        repeat(12) { assertEquals(null, silence.observe(window(620, 0.0))) }
        repeat(4) { assertEquals(null, silence.observe(window(420))) }
        assertEquals(curves[1], checkNotNull(silence.observe(window(420))).levelsMb)
    }

    @Test fun finishedAnalysisNeverChangesTheVersionClock() {
        val plans = listOf(CapsuleTrackSound.NEUTRAL,
            CapsuleTrackSound(listOf(0, 100, 0, 0, 0), 0f, 80f, 1f),
            CapsuleTrackSound(listOf(0, -100, 0, 0, 0), 1f, 180f, 1f))
        for (variant in SoundStudioTempoVariant.entries) {
            val shapes = plans.map { it.tempo(variant) }
            assertEquals(1, shapes.distinct().size)
            val shape = shapes.first()
            assertTrue(kotlin.math.abs(12.0 * kotlin.math.ln(shape.pitch.toDouble()) / kotlin.math.ln(2.0)) <= 0.501)
            if (variant == SoundStudioTempoVariant.NORMAL) assertEquals(1f, shape.pitch, 0f)
            else assertTrue(kotlin.math.abs(shape.speed - shape.pitch) > 0.15f)
        }
        assertEquals(1.2599211f, plans.first().tempo(SoundStudioTempoVariant.SPEED_UP).speed, 0f)
        assertEquals(0.7937005f, plans.first().tempo(SoundStudioTempoVariant.SLOWED).speed, 0f)
        assertEquals(1.0293022f, plans.first().tempo(SoundStudioTempoVariant.SPEED_UP).pitch, 0f)
        assertEquals(0.9715319f, plans.first().tempo(SoundStudioTempoVariant.SLOWED).pitch, 0f)
    }

    @Test fun explicitVersionsKeepIndependentPitchWithEveryMixerAndStrength() {
        for (variant in SoundStudioTempoVariant.entries) {
            val expected = CapsuleTrackSound.NEUTRAL.tempo(variant)
            for (effect in CapsuleMixerEffect.entries) for (amount in listOf(0, 25, 100)) {
                val shape = capsuleStudioPlaybackShape(variant, CapsuleMixerSetting(effect, amount))
                if (variant != SoundStudioTempoVariant.NORMAL || effect != CapsuleMixerEffect.TAPE || amount == 0) {
                    assertEquals(expected, shape)
                } else {
                    assertEquals(shape.speed, shape.pitch, 0f)
                    assertTrue(shape.speed in 0.91f..1f)
                }
            }
        }
        val service = File("src/main/kotlin/com/nikhil/yt/playback/MusicService.kt").readText()
        assertTrue(service.contains("PlaybackParameters(shape.speed, shape.pitch)"))
        assertTrue(service.contains("capsuleStudioPlaybackShape(sound.tempo, sound.mixer)"))
    }

    @Test fun trackVersionsHaveBoundedClockAndIndependentMastering() {
        val sparse = CapsuleTrackSound(listOf(-300, 80, 0, 10, -60), 0f, 90f, 0.8f)
        val dense = CapsuleTrackSound(listOf(0, -200, 0, -100, 30), 1f, 175f, 0.8f)
        val fast = SoundStudioTempoVariant.SPEED_UP
        val slow = SoundStudioTempoVariant.SLOWED
        assertEquals(sparse.tempo(fast), dense.tempo(fast))
        assertEquals(sparse.tempo(slow), dense.tempo(slow))
        val rate = 48_000
        val samples = ShortArray(rate) { i -> (18_000 * sin(2 * PI * 440 * i / rate)).roundToInt().toShort() }
        fun version(mode: SoundStudioTempoVariant) = render(CapsuleStudioEqFilter(rate, 1).apply {
            setVersion(mode, sparse)
            setCurve(listOf(0, 100, 0, 0, 0))
        }, samples)
        assertTrue(!version(fast).contentEquals(version(slow)))
        for (mode in SoundStudioTempoVariant.entries) {
            val output = version(mode)
            assertEquals(samples.size, output.size)
            assertTrue(output.all { abs(it.toInt()) <= (CAPSULE_EQ_PEAK_AMPLITUDE * 32768).roundToInt() })
        }
        assertEquals(0.84f, capsuleTapeRate(0.84f, slow,
            CapsuleMixerSetting(CapsuleMixerEffect.TAPE, 100)), 0.00001f)
    }

    @Test fun mixerAndVersionCombinationsStayBelowPeakCeilingAndIsolateChannels() {
        val rate = 16_000
        val signal = ShortArray(rate * 2) { i ->
            if (i % 2 != 0) 0 else if ((i / 2) % 7 < 3) Short.MAX_VALUE else Short.MIN_VALUE
        }
        for (effect in CapsuleMixerEffect.entries) for (mode in listOf(SoundStudioTempoVariant.SPEED_UP, SoundStudioTempoVariant.SLOWED)) {
            val eq = CapsuleStudioEqFilter(rate, 2).apply {
                setCurve(capsuleManualEqLevels(listOf(160, -800, 550, -700, 320)))
                setVersion(mode, CapsuleTrackSound.NEUTRAL)
                setMixer(CapsuleMixerSetting(effect, 100))
            }
            val output = render(eq, signal)
            assertEquals(signal.size, output.size)
            assertTrue(output.indices.filter { it % 2 == 1 }.all { output[it] == 0.toShort() })
            assertTrue(output.all { abs(it.toInt()) <= (CAPSULE_EQ_PEAK_AMPLITUDE * 32768).roundToInt() })
        }
    }

    @Test fun tapeTransportFollowsOneBoundedRateAndRespectsTempoSelection() {
        val full = CapsuleMixerSetting(CapsuleMixerEffect.TAPE, 100)
        val low = full.copy(amount = 25)
        val off = full.copy(amount = 0)
        val normal = SoundStudioTempoVariant.NORMAL
        val speedUp = SoundStudioTempoVariant.SPEED_UP
        val slowed = SoundStudioTempoVariant.SLOWED
        assertEquals(0.915f, capsuleTapeRate(1f, normal, full), 0.00001f)
        assertTrue(capsuleTapeRate(1f, normal, low) in 0.97f..0.99f)
        assertEquals(1f, capsuleTapeRate(1f, normal, off), 0f)
        assertEquals(1.045f, capsuleTapeRate(1.045f, speedUp, full), 0f)
        assertEquals(0.905f, capsuleTapeRate(0.905f, slowed, full), 0f)
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
        assertTrue(rms(softened) < 0.75 * rms(attack))
        assertTrue(rms(effect(CapsuleMixerEffect.SOFT, attack, 30)) > 1.2 * rms(softened))

        val fundamental = tone(1_000, 16_000)
        val sun = effect(CapsuleMixerEffect.SUN, fundamental)
        val harmonic = (rate / 2 until rate).sumOf { sun[it] * sin(2 * PI * 2_000 * it / rate) }
        val quadrature = (rate / 2 until rate).sumOf { sun[it] * kotlin.math.cos(2 * PI * 2_000 * it / rate) }
        assertTrue("No added second harmonic", kotlin.math.hypot(harmonic, quadrature) / (rate / 4) < 40.0)
        assertTrue(rms(sun) < rms(fundamental))
        assertTrue(effect(CapsuleMixerEffect.SUN, fundamental, 0).contentEquals(fundamental))

        val deck = effect(CapsuleMixerEffect.VINYL, fundamental)
        assertTrue(!deck.contentEquals(fundamental))
        assertTrue(rms(deck) < rms(fundamental))
        val soft = CapsuleStudioSound(mixer = CapsuleMixerSetting(CapsuleMixerEffect.SOFT, 100))
        assertTrue("Lower-mid body is revealed", toneGainDb(soft, rate, 410) - toneGainDb(soft, rate, 6_000) > 2.0)
        for (mode in CapsuleMixerEffect.entries) {
            assertTrue(effect(mode, ShortArray(rate)).all { it == 0.toShort() })
        }
    }

    @Test fun djIsAutomaticAndIsolatesChannels() {
        val rate = 48000
        val stereo = ShortArray(rate * 2) { i ->
            if (i % 2 == 0) (16000 * sin(2 * PI * 720 * (i / 2) / rate)).roundToInt().toShort() else 0
        }
        val eq = CapsuleStudioEqFilter(rate, 2).apply {
            setMixer(CapsuleMixerSetting(CapsuleMixerEffect.VINYL, 100))
        }
        val output = render(eq, stereo)
        assertTrue(!output.contentEquals(stereo))
        assertTrue(output.indices.filter { it % 2 == 1 }.all { output[it] == 0.toShort() })
        assertTrue(output.all { abs(it.toInt()) <= (CAPSULE_EQ_PEAK_AMPLITUDE * 32768).roundToInt() })
    }

    @Test fun lookaheadStartsAttenuationBeforeThePeakAndDrainsEveryFrame() {
        val rate = 48_000
        val signal = ShortArray(3_000) { if (it == 1_500) Short.MAX_VALUE else 11_000 }
        fun filtered(parts: List<ShortArray>): ShortArray {
            val eq = CapsuleStudioEqFilter(rate, 1).apply { setProcessingGuard(true) }
            val output = ByteBuffer.allocate(signal.size * 2).order(ByteOrder.nativeOrder())
            for (part in parts) {
                val buffer = ByteBuffer.allocate(part.size * 2).order(ByteOrder.nativeOrder())
                part.forEach { buffer.putShort(it) }
                buffer.flip()
                eq.process(buffer, output)
            }
            assertEquals(192, eq.pendingBytes)
            eq.drain(output)
            assertEquals(signal.size * 2, output.position())
            output.flip()
            return ShortArray(signal.size) { output.getShort() }
        }
        val output = filtered(listOf(signal))
        val streamed = filtered(signal.asList().chunked(137).map { it.toShortArray() })
        assertTrue(output.contentEquals(streamed))
        assertEquals(11_000, output[1_300].toInt())
        assertTrue(output[1_475] < 10_000)
        assertTrue(output[1_404].toInt() - output[1_405].toInt() < 800)
        assertTrue(output[1_500] <= (CAPSULE_EQ_PEAK_AMPLITUDE * 32768).roundToInt())
    }

    @Test fun media3ProcessorReturnsLookaheadTailAtEndOfStream() {
        val processor = CapsuleStudioEqProcessor(
            readSound = { CapsuleStudioSound(adaptive = true) },
            readTrack = { null },
        )
        processor.configure(AudioFormat(48_000, 1, C.ENCODING_PCM_16BIT))
        processor.flush(StreamMetadata.DEFAULT)
        val source = ByteBuffer.allocateDirect(600).order(ByteOrder.nativeOrder())
        repeat(300) { source.putShort(it.toShort()) }
        source.flip()
        processor.queueInput(source)
        val first = processor.output
        assertEquals(408, first.remaining())
        repeat(204) { assertEquals(it.toShort(), first.getShort()) }
        processor.queueEndOfStream()
        val tail = processor.output
        assertEquals(192, tail.remaining())
        repeat(96) { assertEquals((it + 204).toShort(), tail.getShort()) }
        assertTrue(processor.isEnded)
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

    @Test fun tempoAloneGetsFinalPeakProtectionAndNeutralIsBitExact() {
        val rate = 48_000
        val eq = CapsuleStudioEqFilter(rate, 1)
        val input = ShortArray(rate) { if (it % 4 < 2) Short.MAX_VALUE else (-32767).toShort() }
        assertTrue(render(eq, input).contentEquals(input))
        eq.setProcessingGuard(true)
        val output = render(eq, input)
        val settled = output.copyOfRange(rate / 2, rate)
        assertTrue(sqrt(2.0) * settled.maxOf { abs(it.toInt()) } / 32768.0 < 0.85)
    }

    @Test fun mixerAloneLimitsPeaksBeforeItsEffectRampsUp() {
        val fullScale = ShortArray(600) { if (it % 4 < 2) Short.MAX_VALUE else Short.MIN_VALUE }
        for (effect in CapsuleMixerEffect.entries.filter { it != CapsuleMixerEffect.OFF }) {
            val eq = CapsuleStudioEqFilter(48_000, 1)
            eq.setMixer(CapsuleMixerSetting(effect, 100))
            val output = render(eq, fullScale)
            assertTrue(output.all { abs(it.toInt()) <= (CAPSULE_EQ_PEAK_AMPLITUDE * 32768).roundToInt() })
        }
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
        assertTrue(renderer.contains("super.getAudioProcessors() + CapsuleStudioEqProcessor("))
        assertTrue(service.contains("requested = enabled && !soundStudioNeedsPcm"))
        assertTrue(service.contains("overlapPlayerFactory = {"))
        assertTrue(service.contains("CapsuleAudioRenderersFactory(this, ::activeStudioSound,"))
        assertTrue(service.contains("readStudioSound = ::activeStudioSound"))
        assertTrue(video.contains("CapsuleAudioRenderersFactory(context, readStudioSound, useMusicBuffer = false,"))
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
        eq.drain(output)
        output.flip()
        return ShortArray(output.remaining() / Short.SIZE_BYTES) { output.getShort() }
    }
}
