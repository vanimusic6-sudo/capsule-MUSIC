/* Capsule MUSIC — sound studio presets. GPL-3.0. */
package com.nikhil.yt.playback

internal data class CapsuleEqPreset(
    val id: String,
    val name: String,
    val referenceLevelsMb: List<Int>,
)

internal object CapsuleEqPresets {
    // Wide contours create audible contrast without a narrow midrange hole.
    val all = listOf(
        CapsuleEqPreset("soft_bass_v2", "Soft Bass",
            listOf(140, 330, 500, 410, 180, -50, -170, -240, -230, -200)),
        CapsuleEqPreset("warm_v2", "Warm",
            listOf(70, 180, 320, 460, 390, 125, -100, -290, -310, -250)),
        CapsuleEqPreset("vocal_v2", "Vocals",
            listOf(-330, -310, -235, -130, 70, 290, 410, 285, 20, -135)),
        CapsuleEqPreset("air_v2", "Air",
            listOf(-270, -250, -210, -160, -120, -60, 80, 315, 255, 220)),
        CapsuleEqPreset("live_v2", "Live",
            listOf(110, 290, 340, 185, -90, -130, 230, 320, 170, 40)),
    )

    fun byId(id: String): CapsuleEqPreset? = all.firstOrNull { it.id == id.removePrefix("capsule:") }
}
