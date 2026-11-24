/* Capsule MUSIC — sound studio preferences. GPL-3.0. */
package com.nikhil.yt.playback

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import com.nikhil.yt.constants.EqualizerSelectedProfileIdKey
import com.nikhil.yt.constants.SoundStudioTempoVariantKey
import kotlinx.serialization.json.Json
import kotlin.math.ln
import kotlin.math.roundToInt

internal enum class SoundStudioTempoVariant(val id: String, val rate: Float) {
    NORMAL("flat", 1f),
    SPEED_UP("version:speed_up", 1.18f),
    SLOWED("version:slowed", 0.84f);

    companion object {
        fun resolve(savedId: String?, legacyProfileId: String?): SoundStudioTempoVariant =
            entries.firstOrNull { it.id == (savedId ?: legacyProfileId) } ?: NORMAL
    }
}

internal fun Preferences.readSoundStudioTempoVariant(): SoundStudioTempoVariant =
    SoundStudioTempoVariant.resolve(this[SoundStudioTempoVariantKey], this[EqualizerSelectedProfileIdKey])

internal fun MutablePreferences.setSoundStudioTempoVariant(variant: SoundStudioTempoVariant) {
    this[SoundStudioTempoVariantKey] = variant.id
}

// Keep the legacy version when a tone selection replaces its old shared key.
internal fun MutablePreferences.preserveLegacySoundStudioTempo() {
    val variant = readSoundStudioTempoVariant()
    if (this[SoundStudioTempoVariantKey] == null && variant != SoundStudioTempoVariant.NORMAL) {
        setSoundStudioTempoVariant(variant)
    }
}

internal const val CAPSULE_STUDIO_MANUAL_ID = "studio:manual"
private const val LEGACY_STUDIO_MANUAL_ID = "adaptive:manual"
internal const val CAPSULE_STUDIO_DEFAULT_ID = "capsule:studio_auto"

internal val CAPSULE_STUDIO_FREQUENCIES_HZ =
    listOf(48, 95, 180, 340, 620, 1_100, 2_100, 3_800, 6_800, 12_000)

private val legacyStudioFrequenciesHz =
    listOf(40, 80, 160, 315, 630, 1_250, 2_500, 5_000, 10_000, 16_000)

internal fun readStudioManualCurve(id: String?, raw: String?): List<Int> {
    if (id != CAPSULE_STUDIO_MANUAL_ID && id != LEGACY_STUDIO_MANUAL_ID) return emptyList()
    val saved = decodeEqBandLevels(raw)
    if (saved.isEmpty()) return emptyList()
    if (id == CAPSULE_STUDIO_MANUAL_ID) {
        return resampleEqLevelsByIndex(saved, CAPSULE_STUDIO_FREQUENCIES_HZ.size)
    }
    val old = resampleEqLevelsByIndex(saved, legacyStudioFrequenciesHz.size)
    return CAPSULE_STUDIO_FREQUENCIES_HZ.map { hz ->
        val hi = legacyStudioFrequenciesHz.indexOfFirst { it >= hz }
        if (hi <= 0) old[if (hi < 0) old.lastIndex else 0]
        else {
            val left = legacyStudioFrequenciesHz[hi - 1].toDouble()
            val mix = ln(hz / left) / ln(legacyStudioFrequenciesHz[hi] / left)
            (old[hi - 1] + (old[hi] - old[hi - 1]) * mix).roundToInt()
        }
    }
}

internal fun resampleEqLevelsByIndex(
    levelsMb: List<Int>,
    targetCount: Int,
): List<Int> {
    if (targetCount <= 0) return emptyList()
    if (levelsMb.isEmpty()) return List(targetCount.coerceAtMost(32)) { 0 }
    if (levelsMb.size == targetCount && targetCount <= 32) return levelsMb
    if (targetCount == 1) return listOf((levelsMb.sumOf { it.toLong() } / levelsMb.size).toInt())

    val lastIndex = levelsMb.lastIndex.toFloat().coerceAtLeast(1f)
    return List(targetCount.coerceAtMost(32)) { index ->
        val position = index.toFloat() * lastIndex / (targetCount.coerceAtMost(32) - 1).toFloat()
        val lower = kotlin.math.floor(position).toInt().coerceIn(0, levelsMb.lastIndex)
        val upper = kotlin.math.ceil(position).toInt().coerceIn(0, levelsMb.lastIndex)
        val fraction = (position - lower.toFloat()).coerceIn(0f, 1f)
        val start = levelsMb[lower]
        val end = levelsMb[upper]
        (start.toDouble() + (end.toDouble() - start.toDouble()) * fraction).toInt()
    }
}

/** Import/profile parsing is untrusted input: bound it before it reaches DSP state. */
internal fun decodeEqBandLevels(raw: String?): List<Int> {
    if (raw.isNullOrBlank() || raw.length > 8192) return emptyList()
    return runCatching { EqualizerJson.json.decodeFromString<List<Int>>(raw) }
        .getOrNull()?.take(64)?.map { it.coerceIn(-1500, 1500) } ?: emptyList()
}

internal object EqualizerJson {
    val json: Json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
}

