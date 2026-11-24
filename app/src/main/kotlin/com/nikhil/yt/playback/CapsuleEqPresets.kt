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
    AUTO("studio_auto", listOf(5, 105, 275, 355, 205, 75, 0, -75, -45, -20)),
    WARM("studio_warm", listOf(5, 105, 275, 355, 205, 75, 0, -75, -45, -20)),
    TAPE("studio_tape", listOf(0, 175, 225, 125, 55, 15, -30, -125, -345, -480)),
    CLEAR("studio_clear", listOf(-75, -255, -200, -150, -15, 135, 285, 210, 70, 25));

    val sound = CapsuleStudioSound(capsuleMusicalEqLevels(levels), tape = id == "studio_tape", adaptive = id == "studio_auto")

    companion object {
        // Auto can tame a bright recording without changing its tape speed.
        val brightAutoSound = CapsuleStudioSound(
            capsuleMusicalEqLevels(listOf(0, 10, 45, 90, 70, 20, -20, -110, -145, -75)),
        )

        fun byId(id: String): CapsuleEqPresets? = when (val key = id.removePrefix("capsule:")) {
            "soft_bass_v2", "warm_v2" -> WARM
            "vocal_v2", "air_v2", "live_v2" -> CLEAR
            else -> entries.firstOrNull { it.id == key }
        }
    }
}
