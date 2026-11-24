/* Capsule MUSIC — sound studio voicings. GPL-3.0. */
package com.nikhil.yt.playback

internal data class CapsuleStudioSound(
    val levelsMb: List<Int> = emptyList(),
    val tape: Boolean = false,
    val adaptive: Boolean = false,
) {
    val enabled: Boolean get() = levelsMb.isNotEmpty() || tape || adaptive
}

internal enum class CapsuleEqPresets(val id: String, levels: List<Int>) {
    AUTO("studio_auto", listOf(0, 30, 105, 170, 110, 40, 0, -35, -20, 0)),
    WARM("studio_warm", listOf(0, 30, 105, 170, 110, 40, 0, -35, -20, 0)),
    TAPE("studio_tape", listOf(0, 10, 65, 135, 110, 50, -15, -75, -105, -80)),
    CLEAR("studio_clear", listOf(-35, -90, -55, 25, 65, 105, 115, 65, 25, 0));

    val sound = CapsuleStudioSound(capsuleMusicalEqLevels(levels), tape = id == "studio_tape", adaptive = id == "studio_auto")

    companion object {
        fun byId(id: String): CapsuleEqPresets? = when (val key = id.removePrefix("capsule:")) {
            "soft_bass_v2", "warm_v2" -> WARM
            "vocal_v2", "air_v2", "live_v2" -> CLEAR
            else -> entries.firstOrNull { it.id == key }
        }
    }
}
