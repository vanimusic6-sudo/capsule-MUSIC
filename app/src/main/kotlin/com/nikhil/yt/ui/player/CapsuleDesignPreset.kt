/*
 * Capsule MUSIC
 * Portable presets for the "Capsule your way" player-layout editor.
 * GPL-3.0
 */

package com.nikhil.yt.ui.player

import com.nikhil.yt.constants.CapsuleCustomizeTarget
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

private const val CAPSULE_DESIGN_PRESET_HEADER = "CAPSULE-DESIGN/1"

internal data class CapsuleDesignPreset(
    val target: CapsuleCustomizeTarget,
    val layoutOrder: String,
    val canvasPositions: String,
    val metadataOrder: String,
    val modeOrder: String,
    val avOrder: String,
    val transportOrder: String,
    val artworkWidthScale: Float? = null,
    val artworkHeightScale: Float? = null,
    val blockGaps: String? = null,
    val lyricLineEnabled: Boolean? = null,
)

/**
 * Dependency-free, versioned interchange format for community-created Capsule screens.
 *
 * Do not use org.json here. The app also carries the standalone org.json artifact, while Android
 * ships its own classes with the same package name. R8 can optimise against one hierarchy and then
 * Android verifies against the other one, which causes a VerifyError before the share sheet even
 * opens on some Android 16 builds.
 *
 * Each value is URL-escaped, so delimiters inside saved layout strings remain safe. On import every
 * field still goes through the editor's existing decoders/encoders before it reaches DataStore.
 */
internal object CapsuleDesignPresetCodec {
    private fun escape(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun unescape(value: String): String =
        URLDecoder.decode(value, Charsets.UTF_8.name())

    fun encode(preset: CapsuleDesignPreset): String =
        buildString {
            appendLine(CAPSULE_DESIGN_PRESET_HEADER)
            appendLine("target=${escape(preset.target.name)}")
            appendLine("order=${escape(preset.layoutOrder)}")
            appendLine("positions=${escape(preset.canvasPositions)}")
            appendLine("metadata=${escape(preset.metadataOrder)}")
            appendLine("mode=${escape(preset.modeOrder)}")
            appendLine("av=${escape(preset.avOrder)}")
            appendLine("transport=${escape(preset.transportOrder)}")

            if (preset.target == CapsuleCustomizeTarget.LIGHT) {
                appendLine("artworkWidth=${escape((preset.artworkWidthScale ?: 1f).toString())}")
                appendLine("artworkHeight=${escape((preset.artworkHeightScale ?: 1f).toString())}")
                appendLine("gaps=${escape(preset.blockGaps ?: CapsuleLightBaseGapsEncoded)}")
                appendLine("lyricLine=${if (preset.lyricLineEnabled != false) "1" else "0"}")
            }
        }.trimEnd()

    fun decode(raw: String): CapsuleDesignPreset? =
        runCatching {
            val lines =
                raw
                    .trim()
                    .lineSequence()
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .toList()

            require(lines.firstOrNull() == CAPSULE_DESIGN_PRESET_HEADER)

            val fields =
                buildMap {
                    lines.drop(1).forEach { line ->
                        val parts = line.split('=', limit = 2)
                        if (parts.size == 2) {
                            put(parts[0], unescape(parts[1]))
                        }
                    }
                }

            val target =
                CapsuleCustomizeTarget.valueOf(
                    fields.getValue("target").uppercase(Locale.US),
                )

            val layoutOrder =
                when (target) {
                    CapsuleCustomizeTarget.LIGHT ->
                        encodeCapsuleLightOrder(
                            decodeCapsuleLightOrder(
                                fields["order"] ?: CapsuleLightBaseOrderEncoded,
                            ),
                        )

                    CapsuleCustomizeTarget.IMMERSIVE ->
                        encodeCapsuleImmersiveOrder(
                            decodeCapsuleImmersiveOrder(
                                fields["order"] ?: CapsuleImmersiveBaseOrderEncoded,
                            ),
                        )
                }

            val canvasPositions =
                encodeCapsuleLightCanvasPositions(
                    decodeCapsuleLightCanvasPositions(
                        fields["positions"] ?: CapsuleLightCanvasPositionsBaseEncoded,
                    ),
                )

            val metadataOrder =
                encodeCapsuleLightMetadataOrder(
                    decodeCapsuleLightMetadataOrder(
                        fields["metadata"] ?: CapsuleLightMetadataBaseOrderEncoded,
                    ),
                )
            val modeOrder =
                encodeCapsuleLightModeOrder(
                    decodeCapsuleLightModeOrder(
                        fields["mode"] ?: CapsuleLightModeBaseOrderEncoded,
                    ),
                )
            val avOrder =
                encodeCapsuleLightAvOrder(
                    decodeCapsuleLightAvOrder(
                        fields["av"] ?: CapsuleLightAvBaseOrderEncoded,
                    ),
                )
            val transportOrder =
                encodeCapsuleLightTransportOrder(
                    decodeCapsuleLightTransportOrder(
                        fields["transport"] ?: CapsuleLightTransportBaseOrderEncoded,
                    ),
                )

            if (target == CapsuleCustomizeTarget.LIGHT) {
                val widthScale =
                    fields["artworkWidth"]
                        ?.toFloatOrNull()
                        ?.takeIf { it.isFinite() }
                        ?.coerceIn(0.55f, 1.08f)
                        ?: 1f
                val heightScale =
                    fields["artworkHeight"]
                        ?.toFloatOrNull()
                        ?.takeIf { it.isFinite() }
                        ?.coerceIn(0.55f, 1.35f)
                        ?: 1f
                val gaps =
                    encodeCapsuleLightBlockGaps(
                        decodeCapsuleLightBlockGaps(
                            fields["gaps"] ?: CapsuleLightBaseGapsEncoded,
                        ),
                    )
                val lyricLineEnabled =
                    when (fields["lyricLine"]?.lowercase(Locale.US)) {
                        "0", "false", "off" -> false
                        "1", "true", "on" -> true
                        else -> true
                    }

                CapsuleDesignPreset(
                    target = target,
                    layoutOrder = layoutOrder,
                    canvasPositions = canvasPositions,
                    metadataOrder = metadataOrder,
                    modeOrder = modeOrder,
                    avOrder = avOrder,
                    transportOrder = transportOrder,
                    artworkWidthScale = widthScale,
                    artworkHeightScale = heightScale,
                    blockGaps = gaps,
                    lyricLineEnabled = lyricLineEnabled,
                )
            } else {
                CapsuleDesignPreset(
                    target = target,
                    layoutOrder = layoutOrder,
                    canvasPositions = canvasPositions,
                    metadataOrder = metadataOrder,
                    modeOrder = modeOrder,
                    avOrder = avOrder,
                    transportOrder = transportOrder,
                )
            }
        }.getOrNull()
}
