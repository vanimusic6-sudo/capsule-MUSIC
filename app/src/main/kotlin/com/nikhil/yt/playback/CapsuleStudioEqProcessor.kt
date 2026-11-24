/* Capsule MUSIC — PCM equalizer. GPL-3.0. */
package com.nikhil.yt.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.StreamMetadata
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

internal const val CAPSULE_EQ_PEAK_AMPLITUDE = 0.7943282347 // -2 dBFS sample peak

/** One processor per sink. The curve supplier publishes immutable snapshots across threads. */
internal class CapsuleStudioEqProcessor(
    private val readSound: () -> CapsuleStudioSound,
    private val readTrack: () -> String?,
) : BaseAudioProcessor() {
    private var filter: CapsuleStudioEqFilter? = null
    private var analysis: CapsuleStudioAnalysis? = null
    private var selectedTone: CapsuleEqPresets? = null
    private var analyzedTrack: String? = null
    private var analyzing = false

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat =
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT &&
            inputAudioFormat.sampleRate in 4_000..384_000 && inputAudioFormat.channelCount in 1..32
        ) inputAudioFormat else AudioFormat.NOT_SET

    override fun onFlush(streamMetadata: StreamMetadata) {
        filter = if (outputAudioFormat == AudioFormat.NOT_SET) null
            else CapsuleStudioEqFilter(inputAudioFormat.sampleRate, inputAudioFormat.channelCount)
        analysis = null
    }

    override fun onReset() {
        filter = null
        analysis = null
        selectedTone = null
        analyzedTrack = null
        analyzing = false
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val equalizer = checkNotNull(filter)
        val setting = readSound()
        val track = readTrack()
        if (analyzing != setting.adaptive || track != analyzedTrack) {
            analyzing = setting.adaptive
            analyzedTrack = track
            analysis = null
            selectedTone = null
        }
        // Each player publishes its own item ID across threads.
        // Keep a completed choice across a seek or PCM-format flush on that item.
        if (analyzing && selectedTone == null) {
            val detector = analysis ?: CapsuleStudioAnalysis(inputAudioFormat.sampleRate, inputAudioFormat.channelCount)
                .also { analysis = it }
            selectedTone = detector.observe(inputBuffer)
            if (selectedTone != null) analysis = null
        }
        val sound = if (setting.adaptive) when (selectedTone) {
            CapsuleEqPresets.TAPE -> CapsuleEqPresets.brightAutoSound
            CapsuleEqPresets.CLEAR -> CapsuleEqPresets.CLEAR.sound
            else -> CapsuleEqPresets.WARM.sound
        } else setting
        equalizer.setSound(sound, smooth = setting.adaptive)
        val output = replaceOutputBuffer(inputBuffer.remaining())
        equalizer.process(inputBuffer, output)
        output.flip()
    }
}

/** A ten-band peaking EQ with a static input trim and a linked stereo peak guard. */
internal class CapsuleStudioEqFilter(sampleRate: Int, private val channels: Int) {
    private val rate = sampleRate
    private val fastTransitionFrames = (sampleRate * 24 / 1000).coerceAtLeast(1)
    private val slowTransitionFrames = (sampleRate * 180 / 1000).coerceAtLeast(1)
    private var transitionFrames = fastTransitionFrames
    private val peakRelease = 1.0 - exp(-1.0 / (sampleRate * 0.045))
    private var current: StudioEqCascade? = null
    private var incoming: StudioEqCascade? = null
    private var targetSound = CapsuleStudioSound()
    private var remaining = 0
    private var peakGain = 1.0
    private var started = false
    private val frame = DoubleArray(channels)

    fun setSound(sound: CapsuleStudioSound, smooth: Boolean = false) {
        // Complete the current ramp before acting on a newer snapshot.
        if (remaining != 0 || sound == targetSound) return
        targetSound = sound
        val curve = sound.levelsMb
        val bands = CAPSULE_STUDIO_FREQUENCIES_HZ.mapIndexedNotNull { i, frequency ->
            val gain = curve.getOrElse(i) { 0 }.coerceIn(capsuleStudioBandLimit(frequency))
            if (gain == 0 || frequency >= rate * 0.47) null
            else StudioEqBiquad.create(frequency, rate, gain, channels)
        }.toTypedArray()
        incoming = if (bands.isEmpty() && !sound.tape) null else StudioEqCascade(bands, rate, channels, sound.tape)
        if (!started) {
            current = incoming
        } else if (current != null || incoming != null) {
            transitionFrames = if (smooth) slowTransitionFrames else fastTransitionFrames
            remaining = transitionFrames
        }
    }

    fun process(input: ByteBuffer, output: ByteBuffer) {
        if (!input.hasRemaining()) return
        started = true
        if (current == null && remaining == 0 && peakGain == 1.0) {
            output.put(input)
            return
        }
        val frameBytes = channels * Short.SIZE_BYTES
        while (input.remaining() >= frameBytes) {
            val mix = if (remaining == 0) 0.0 else 1.0 - (remaining - 1.0) / transitionFrames
            var peak = 0.0
            for (channel in 0 until channels) {
                val sample = input.getShort() / 32768.0
                val old = current?.process(sample, channel) ?: sample
                val mixed = if (remaining != 0) {
                    val next = incoming?.process(sample, channel) ?: sample
                    old + (next - old) * mix
                } else old
                frame[channel] = if (mixed.isFinite()) mixed else 0.0
                peak = max(peak, abs(frame[channel]))
            }
            val oldCeiling = if (current == null) 1.0 else CAPSULE_EQ_PEAK_AMPLITUDE
            val ceiling = if (remaining == 0) oldCeiling else {
                val newCeiling = if (incoming == null) 1.0 else CAPSULE_EQ_PEAK_AMPLITUDE
                oldCeiling + (newCeiling - oldCeiling) * mix
            }
            val requiredGain = if (peak > ceiling) ceiling / peak else 1.0
            peakGain = minOf(requiredGain, peakGain + (1.0 - peakGain) * peakRelease)
            for (value in frame) {
                val pcm = (value * peakGain * 32768.0).roundToInt().coerceIn(-32768, 32767)
                output.putShort(pcm.toShort())
            }
            if (remaining != 0 && --remaining == 0) current = incoming
            if (current == null && remaining == 0 && peakGain >= 0.9999) {
                peakGain = 1.0
                break
            }
        }
        if (input.hasRemaining()) output.put(input)
    }
}

private class StudioEqCascade(
    private val bands: Array<StudioEqBiquad>, sampleRate: Int, channels: Int, tape: Boolean,
) {
    private val transport = if (tape) CapsuleTapeTransport(sampleRate, channels) else null
    private val trim = run {
        // Estimate the maximum of the *combined* filter response at the
        // centers and across the spectrum. A fixed margin covers grid gaps.
        var peakSquared = 1.0
        fun observe(frequency: Double) {
            val w = 2.0 * PI * frequency / sampleRate
            val c = cos(w)
            val s = sin(w)
            val c2 = cos(2.0 * w)
            val s2 = sin(2.0 * w)
            var magnitude = 1.0
            for (band in bands) magnitude *= band.magnitudeSquared(c, s, c2, s2)
            peakSquared = max(peakSquared, magnitude)
        }
        for (frequency in CAPSULE_STUDIO_FREQUENCIES_HZ) {
            if (frequency < sampleRate / 2) observe(frequency.toDouble())
        }
        val nyquist = sampleRate / 2.0
        for (step in 0..160) observe(20.0 * (nyquist / 20.0).pow(step / 160.0))
        10.0.pow(-0.6 / 20.0) / sqrt(peakSquared)
    }

    fun process(sample: Double, channel: Int): Double {
        var value = sample * trim
        for (band in bands) value = band.process(value, channel)
        return transport?.process(value, channel) ?: value
    }
}

private class StudioEqBiquad(
    private val b0: Double, private val b1: Double, private val b2: Double,
    private val a1: Double, private val a2: Double, channels: Int,
) {
    private val z1 = DoubleArray(channels)
    private val z2 = DoubleArray(channels)

    fun process(sample: Double, channel: Int): Double {
        val output = b0 * sample + z1[channel]
        z1[channel] = b1 * sample - a1 * output + z2[channel]
        z2[channel] = b2 * sample - a2 * output
        return output
    }

    fun magnitudeSquared(c: Double, s: Double, c2: Double, s2: Double): Double {
        val nr = b0 + b1 * c + b2 * c2
        val ni = b1 * s + b2 * s2
        val dr = 1.0 + a1 * c + a2 * c2
        val di = a1 * s + a2 * s2
        return (nr * nr + ni * ni) / (dr * dr + di * di)
    }

    companion object {
        fun create(hz: Int, sampleRate: Int, gainMb: Int, channels: Int): StudioEqBiquad {
            val amplitude = 10.0.pow(gainMb / 4000.0)
            val w = 2.0 * PI * hz / sampleRate
            // Roughly octave-wide bands with smooth overlap. Use a narrower
            // last band to keep 12 kHz adjustments out of upper mids.
            val q = if (hz >= 10_000) 1.55 else 1.32
            val alpha = sin(w) / (2.0 * q)
            val a0 = 1.0 + alpha / amplitude
            val common = -2.0 * cos(w) / a0
            return StudioEqBiquad(
                (1.0 + alpha * amplitude) / a0, common, (1.0 - alpha * amplitude) / a0,
                common, (1.0 - alpha / amplitude) / a0, channels,
            )
        }
    }
}
