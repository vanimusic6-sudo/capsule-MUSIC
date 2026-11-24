/* Capsule MUSIC — sound studio voicings. GPL-3.0. */
package com.nikhil.yt.playback

internal data class CapsuleStudioSound(
    val levelsMb: List<Int> = emptyList(),
    val adaptive: Boolean = false,
    val mixer: CapsuleMixerSetting = CapsuleMixerSetting(),
    val tempo: SoundStudioTempoVariant = SoundStudioTempoVariant.NORMAL,
) {
    val enabled: Boolean get() = levelsMb.isNotEmpty() || adaptive || mixer.effect != CapsuleMixerEffect.OFF
}

internal enum class CapsuleEqPresets(val id: String, levels: List<Int>) {
    AUTO("studio_auto", List(CAPSULE_STUDIO_FREQUENCIES_HZ.size) { 0 }),
    WARM("studio_warm", listOf(55, 370, 80, -50, -70)),
    TAPE("studio_tape", List(CAPSULE_STUDIO_FREQUENCIES_HZ.size) { 0 }),
    CLEAR("studio_clear", listOf(-165, -155, 85, 280, 80));

    val sound = CapsuleStudioSound(capsuleMusicalEqLevels(levels), adaptive = id == "studio_auto")

    companion object {
        fun byId(id: String): CapsuleEqPresets? = when (val key = id.removePrefix("capsule:")) {
            "soft_bass_v2", "warm_v2" -> WARM
            "vocal_v2", "air_v2", "live_v2" -> CLEAR
            else -> entries.firstOrNull { it.id == key }
        }
    }
}
