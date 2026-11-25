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
    private val readPlan: (String) -> CapsuleTrackSound? = { null },
    private val publishPlan: (String, CapsuleTrackSound) -> Unit = { _, _ -> },
) : BaseAudioProcessor() {
    private var filter: CapsuleStudioEqFilter? = null
    private var analysis: CapsuleStudioAnalysis? = null
    private var plan: CapsuleTrackSound? = null
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
        plan = null
        analyzedTrack = null
        analyzing = false
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val equalizer = checkNotNull(filter)
        val setting = readSound()
        val track = readTrack()
        val needsAnalysis = setting.adaptive || setting.tempo != SoundStudioTempoVariant.NORMAL
        if (track != analyzedTrack) {
            analyzedTrack = track
            analysis = null
            plan = null
        }
        if (analyzing != needsAnalysis) {
            analyzing = needsAnalysis
            analysis = null
        }
        if (analyzing && plan == null && track != null) {
            plan = readPlan(track)
        }
        if (analyzing && plan == null && track != null) {
            val detector = analysis ?: CapsuleStudioAnalysis(inputAudioFormat.sampleRate, inputAudioFormat.channelCount)
                .also { analysis = it }
            detector.observe(inputBuffer)?.let {
                plan = it
                analysis = null
                publishPlan(track, it)
            }
        }
        val tone = if (setting.adaptive) plan?.levelsMb.orEmpty() else setting.levelsMb
        equalizer.setCurve(tone, smooth = setting.adaptive)
        equalizer.setVersion(setting.tempo, plan ?: CapsuleTrackSound.NEUTRAL)
        equalizer.setMixer(setting.mixer)
        equalizer.setProcessingGuard(setting.adaptive || setting.tempo != SoundStudioTempoVariant.NORMAL)
        val output = replaceOutputBuffer(inputBuffer.remaining())
        equalizer.process(inputBuffer, output)
        output.flip()
    }

    override fun onQueueEndOfStream() {
        val equalizer = filter ?: return
        val output = replaceOutputBuffer(equalizer.pendingBytes)
        equalizer.drain(output)
        output.flip()
    }
}

/** EQ, independent version mastering and mixer share the final linked peak guard. */
internal class CapsuleStudioEqFilter(sampleRate: Int, private val channels: Int) {
    private val rate = sampleRate
    private val fastTransitionFrames = (sampleRate * 24 / 1000).coerceAtLeast(1)
    private val slowTransitionFrames = (sampleRate * 180 / 1000).coerceAtLeast(1)
    private var transitionFrames = fastTransitionFrames
    private val edgeRelease = exp(-1.0 / (sampleRate * 0.035))
    private val loadStep = 1.0 - exp(-1.0 / (sampleRate * 0.42))
    private var current: StudioEqCascade? = null
    private var incoming: StudioEqCascade? = null
    private var targetCurve = emptyList<Int>()
    private var remaining = 0
    private var pendingCurve: List<Int>? = null
    private var pendingSmooth = false
    private var edgeRisk = 0.0
    private var sustainedPower = 0.0
    private var processingGuard = false
    private var processingGuardMix = 0.0
    private var started = false
    private val frame = DoubleArray(channels)
    private val dryFrame = DoubleArray(channels)
    private val loadGuard = CapsuleStudioLoadGuard(sampleRate, channels)
    private val previousFrame = DoubleArray(channels)
    private val mixer = CapsuleMixerStage(sampleRate, channels)
    private val versionStage = CapsuleVersionStage(sampleRate, channels)
    private var versionMode: CapsuleVoicing? = null
    private var versionCharacter = 0.76
    private val peakMonitor = CapsuleReconstructedPeak(channels)
    private val limiter = CapsuleLookaheadLimiter(sampleRate, channels)
    private val warmupFrames = (sampleRate * 38 / 1000).coerceAtLeast(1)
    private val history = ShortArray(warmupFrames * channels)
    private var historyCursor = 0
    private var historySize = 0

    val pendingBytes: Int get() = limiter.pendingFrames * channels * Short.SIZE_BYTES

    fun drain(output: ByteBuffer) = limiter.drain(output)

    fun setMixer(setting: CapsuleMixerSetting) = mixer.set(setting)
    fun setVersion(variant: SoundStudioTempoVariant, plan: CapsuleTrackSound) {
        versionMode = when (variant) {
            SoundStudioTempoVariant.NORMAL -> null
            SoundStudioTempoVariant.SPEED_UP -> CapsuleVoicing.FAST
            SoundStudioTempoVariant.SLOWED -> CapsuleVoicing.SLOW
        }
        versionCharacter = if (plan.pace.isFinite()) plan.pace.toDouble().coerceIn(0.0, 1.0) else 0.76
    }
    fun setProcessingGuard(enabled: Boolean) {
        processingGuard = enabled
        if (enabled || !started) processingGuardMix = if (enabled) 1.0 else 0.0
    }

    fun setCurve(curve: List<Int>, smooth: Boolean = false) {
        // Keep the newest edit during an in-flight ramp and apply it at the
        // sample boundary, not on a later decoder buffer.
        if (remaining != 0) {
            pendingCurve = curve.takeUnless { it == targetCurve }
            pendingSmooth = smooth
            return
        }
        pendingCurve = null
        if (curve == targetCurve) return
        targetCurve = curve
        val bands = CAPSULE_STUDIO_FREQUENCIES_HZ.mapIndexedNotNull { i, frequency ->
            val gain = curve.getOrElse(i) { 0 }.coerceIn(capsuleStudioBandLimit(frequency))
            if (gain == 0 || frequency >= rate * 0.47) null
            else StudioEqBiquad.create(frequency, rate, gain, channels)
        }.toTypedArray()
        incoming = if (bands.isEmpty()) null else StudioEqCascade(bands, rate)
        if (!started) {
            current = incoming
        } else if (current != null || incoming != null) {
            incoming?.let(::warmCascade)
            transitionFrames = if (smooth) slowTransitionFrames else fastTransitionFrames
            remaining = transitionFrames
        }
    }

    fun process(input: ByteBuffer, output: ByteBuffer) {
        if (!input.hasRemaining()) return
        started = true
        if (current == null && remaining == 0 && !limiter.active && !mixer.active &&
            !processingGuard && processingGuardMix == 0.0 && versionMode == null && !versionStage.active
        ) {
            captureDryHistory(input)
            output.put(input)
            return
        }
        val frameBytes = channels * Short.SIZE_BYTES
        while (input.remaining() >= frameBytes) {
            val mix = if (remaining == 0) 0.0 else 1.0 - (remaining - 1.0) / transitionFrames
            var peak = 0.0
            for (channel in 0 until channels) {
                val raw = input.getShort()
                history[historyCursor * channels + channel] = raw
                val sample = raw / 32768.0
                dryFrame[channel] = sample
                val old = current?.process(sample, channel) ?: sample
                val mixed = if (remaining != 0) {
                    val next = incoming?.process(sample, channel) ?: sample
                    old + (next - old) * mix
                } else old
                frame[channel] = if (mixed.isFinite()) mixed else 0.0
                peak = max(peak, abs(frame[channel]))
            }
            commitHistoryFrame()
            if (versionMode != null || versionStage.active) {
                versionStage.apply(frame, versionMode, versionCharacter)
                peak = 0.0
                for (channel in frame.indices) {
                    if (!frame[channel].isFinite()) frame[channel] = 0.0
                    peak = max(peak, abs(frame[channel]))
                }
            }
            if (mixer.active) {
                mixer.process(frame, peak)
                peak = 0.0
                for (channel in frame.indices) {
                    if (!frame[channel].isFinite()) frame[channel] = 0.0
                    peak = max(peak, abs(frame[channel]))
                }
            }
            val oldCeiling = if (current == null) 1.0 else CAPSULE_EQ_PEAK_AMPLITUDE
            val eqCeiling = if (remaining == 0) oldCeiling else {
                val newCeiling = if (incoming == null) 1.0 else CAPSULE_EQ_PEAK_AMPLITUDE
                oldCeiling + (newCeiling - oldCeiling) * mix
            }
            if (!processingGuard) processingGuardMix = (processingGuardMix - 1.0 / fastTransitionFrames).coerceAtLeast(0.0)
            val versionGuard = if (versionMode != null || versionStage.active) 1.0 else 0.0
            val protectionMix = max(max(mixer.ceilingFraction, processingGuardMix), versionGuard)
            val ceiling = minOf(eqCeiling, 1.0 - (1.0 - CAPSULE_EQ_PEAK_AMPLITUDE) * protectionMix)
            // Fast changes near full scale need room for peaks between PCM samples.
            // Keep a shared reserve across channels and release it gradually.
            var swing = 0.0
            for (channel in 0 until channels) {
                swing = max(swing, abs(frame[channel] - previousFrame[channel]))
                previousFrame[channel] = frame[channel]
            }
            edgeRisk = max(edgeRisk * edgeRelease, ((swing - 0.35) / 0.85).coerceIn(0.0, 1.0))
            val protection = (1.0 - ceiling) / (1.0 - CAPSULE_EQ_PEAK_AMPLITUDE)
            val peakLimit = ceiling * (1.0 - 0.32 * edgeRisk * protection)
            val reconstructed = if (protection > 0.0) peakMonitor.observe(frame, peak) else 0.0
            val measuredPeak = max(peak, reconstructed)
            val peakProtection = if (measuredPeak > peakLimit) peakLimit / measuredPeak else 1.0
            // Prolonged near-full-scale PCM needs more headroom than a musical transient.
            sustainedPower += (peak * peak - sustainedPower) * loadStep
            val sustainedProtection = if (sustainedPower > 0.60) {
                1.0 - (1.0 - sqrt(0.60 / sustainedPower)) * protection
            } else 1.0
            val bandProtection = loadGuard.observe(dryFrame, frame, protection)
            val requiredGain = minOf(peakProtection, sustainedProtection, bandProtection)
            limiter.queue(frame, requiredGain, output)
            if (remaining != 0 && --remaining == 0) {
                current = incoming
                pendingCurve?.let { setCurve(it, pendingSmooth) }
            }
        }
        if (input.hasRemaining()) output.put(input)
    }

    private fun captureDryHistory(input: ByteBuffer) {
        val frameBytes = channels * Short.SIZE_BYTES
        val frames = input.remaining() / frameBytes
        val count = minOf(warmupFrames, frames)
        var byteIndex = input.position() + (frames - count) * frameBytes
        repeat(count) {
            for (channel in 0 until channels) {
                history[historyCursor * channels + channel] = input.getShort(byteIndex)
                byteIndex += Short.SIZE_BYTES
            }
            commitHistoryFrame()
        }
    }

    private fun commitHistoryFrame() {
        if (++historyCursor == warmupFrames) historyCursor = 0
        historySize = minOf(historySize + 1, warmupFrames)
    }

    private fun warmCascade(cascade: StudioEqCascade) {
        var cursor = (historyCursor - historySize + warmupFrames) % warmupFrames
        repeat(historySize) {
            for (channel in 0 until channels) {
                cascade.process(history[cursor * channels + channel] / 32768.0, channel)
            }
            if (++cursor == warmupFrames) cursor = 0
        }
    }
}

/** Linked two-millisecond lookahead. Once armed, delay stays stable until the sink drains. */
private class CapsuleLookaheadLimiter(rate: Int, private val channels: Int) {
    private val delay = (rate / 500).coerceAtLeast(1)
    private val capacity = delay + 1
    private val frames = DoubleArray(capacity * channels)
    private val required = DoubleArray(capacity)
    private val minimum = LongArray(capacity + 1)
    private val attack = 1.0 - exp(-8.0 / (delay - 12).coerceAtLeast(delay / 2).coerceAtLeast(1))
    private val release = 1.0 - exp(-1.0 / (rate * 0.045))
    private var gain = 1.0
    private var written = 0L
    private var emitted = 0L
    private var head = 0
    private var tail = 0

    val active: Boolean get() = written != 0L
    val pendingFrames: Int get() = (written - emitted).toInt()

    fun queue(frame: DoubleArray, demand: Double, output: ByteBuffer) {
        val slot = (written % capacity).toInt()
        frame.copyInto(frames, slot * channels)
        required[slot] = demand
        while (tail != head) {
            val last = (tail + capacity) % minimum.size
            if (required[(minimum[last] % capacity).toInt()] < demand) break
            tail = last
        }
        minimum[tail] = written++
        tail = (tail + 1) % minimum.size
        if (pendingFrames > delay) emit(output)
    }

    fun drain(output: ByteBuffer) {
        while (pendingFrames != 0) emit(output)
    }

    private fun emit(output: ByteBuffer) {
        val slot = (emitted % capacity).toInt()
        val target = required[(minimum[head] % capacity).toInt()]
        val smoothTarget = if (target < 1.0) target * 0.995 else 1.0
        gain += (smoothTarget - gain) * if (smoothTarget < gain) attack else release
        val safe = minOf(gain, required[slot])
        for (channel in 0 until channels) {
            val pcm = (frames[slot * channels + channel] * safe * 32768.0).roundToInt().coerceIn(-32768, 32767)
            output.putShort(pcm.toShort())
        }
        if (minimum[head] == emitted) head = (head + 1) % minimum.size
        emitted++
    }
}

/** Oversampled history detector, evaluated while samples are still in the lookahead window. */
private class CapsuleReconstructedPeak(private val channels: Int) {
    private val history = DoubleArray(16 * channels)
    private var cursor = 0
    private var watch = 0

    fun observe(frame: DoubleArray, peak: Double): Double {
        frame.copyInto(history, cursor * channels)
        if (peak > auditThreshold) watch = 12
        var maximum = 0.0
        if (watch > 0) {
            watch--
            for (channel in 0 until channels) {
                for (kernel in kernels) {
                    var interpolated = 0.0
                    for (tap in kernel.indices) {
                        val index = ((cursor - 11 + tap) and 15) * channels + channel
                        interpolated += kernel[tap] * history[index]
                    }
                    maximum = max(maximum, abs(interpolated))
                }
            }
        }
        cursor = (cursor + 1) and 15
        return maximum
    }

    companion object {
        private val kernels = Array(3) { phase ->
            val weights = DoubleArray(12) { tap ->
                val x = tap - 5.0 - (phase + 1) / 4.0
                sin(PI * x) / (PI * x) * (0.5 + 0.5 * cos(PI * x / 6.0))
            }
            val sum = weights.sum()
            DoubleArray(weights.size) { weights[it] / sum }
        }
        private val auditThreshold = 0.62 / kernels.maxOf { kernel -> kernel.sumOf { abs(it) } }
    }
}

private class StudioEqCascade(private val bands: Array<StudioEqBiquad>, sampleRate: Int) {
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
        observe(0.0)
        for (step in 0..192) observe(2.0 * (nyquist / 2.0).pow(step / 192.0))
        10.0.pow(-0.6 / 20.0) / sqrt(peakSquared)
    }

    fun process(sample: Double, channel: Int): Double {
        var value = sample * trim
        for (band in bands) value = band.process(value, channel)
        return value
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
            // Broad bands retain useful overlap across the five centers.
            val q = if (hz >= 10_000) 0.66 else 0.78
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
