package com.nikhil.yt.constants

import androidx.datastore.preferences.core.stringPreferencesKey
import java.util.Locale

val AudioClientOrderKey =
    stringPreferencesKey("capsuleAudioClientOrderV1")

/**
 * Persisted, user-controlled priority for the small maintained AUDIO client set.
 * Arbitrary InnerTube identities are intentionally not accepted here.
 */
object AudioClientOrder {
    const val VISIONOS = "VISIONOS"
    const val VISIONOS_0_1 = "VISIONOS_0_1"
    const val WEB_REMIX = "WEB_REMIX"
    const val WEB_EMBEDDED = "WEB_EMBEDDED_PLAYER"
    const val WEB_CREATOR = "WEB_CREATOR"
    const val TVHTML5_SIMPLY = "TVHTML5_SIMPLY"

    val supportedProfiles: List<String> =
        listOf(
            VISIONOS,
            VISIONOS_0_1,
            WEB_REMIX,
            WEB_EMBEDDED,
            WEB_CREATOR,
            TVHTML5_SIMPLY,
        )

    private val supportedSet = supportedProfiles.toSet()

    fun resolve(
        raw: String?,
        legacyPolicy: AudioStreamPolicy,
    ): List<String> {
        val parsed =
            raw
                .orEmpty()
                .split(',')
                .map(::normalize)
                .filter { it in supportedSet }
                .distinct()

        val seed = parsed.ifEmpty { legacyOrder(legacyPolicy) }
        return (seed + legacyOrder(legacyPolicy) + supportedProfiles)
            .map(::normalize)
            .filter { it in supportedSet }
            .distinct()
    }

    fun encode(order: List<String>): String =
        resolve(
            raw = order.joinToString(","),
            legacyPolicy = AudioStreamPolicy.VISIONOS,
        ).joinToString(",")

    fun legacyOrder(policy: AudioStreamPolicy): List<String> =
        when (policy.normalizedForPlayback()) {
            AudioStreamPolicy.WEB ->
                listOf(
                    WEB_REMIX,
                    VISIONOS_0_1,
                    WEB_EMBEDDED,
                    VISIONOS,
                    WEB_CREATOR,
                    TVHTML5_SIMPLY,
                )
            AudioStreamPolicy.WEB_EMBEDDED ->
                listOf(
                    WEB_EMBEDDED,
                    VISIONOS_0_1,
                    WEB_REMIX,
                    VISIONOS,
                    WEB_CREATOR,
                    TVHTML5_SIMPLY,
                )
            else ->
                listOf(
                    VISIONOS,
                    WEB_REMIX,
                    WEB_EMBEDDED,
                    VISIONOS_0_1,
                    WEB_CREATOR,
                    TVHTML5_SIMPLY,
                )
        }

    private fun normalize(profileId: String): String =
        profileId.substringBefore('@').trim().uppercase(Locale.US)
}
