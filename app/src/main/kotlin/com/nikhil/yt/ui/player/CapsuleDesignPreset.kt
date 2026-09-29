/*
 * Capsule MUSIC
 * Portable presets for the "Capsule your way" player-layout editor.
 * GPL-3.0
 */

package com.nikhil.yt.ui.player

import com.nikhil.yt.constants.CapsuleCustomizeTarget
import org.json.JSONObject
import java.util.Locale

private const val CAPSULE_DESIGN_PRESET_FORMAT = "capsule-player-layout"
private const val CAPSULE_DESIGN_PRESET_VERSION = 1

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
 * Small, versioned interchange format for community-created Capsule screens.
 *
 * The codec deliberately stores the same semantic state as the editor rather than raw pixels or a
 * screenshot. On import every field is run through the editor's existing decoders/encoders so an
 * old, hand-edited or partially corrupt preset falls back to legal Capsule geometry instead of
 * injecting unchecked values into DataStore.
 */
internal object CapsuleDesignPresetCodec {
    fun encode(preset: CapsuleDesignPreset): String {
        val layout =
            JSONObject()
                .put("order", preset.layoutOrder)
                .put("positions", preset.canvasPositions)
                .put("metadataOrder", preset.metadataOrder)
                .put("modeOrder", preset.modeOrder)
                .put("avOrder", preset.avOrder)
                .put("transportOrder", preset.transportOrder)

        if (preset.target == CapsuleCustomizeTarget.LIGHT) {
            layout
                .put("artworkWidthScale", preset.artworkWidthScale ?: 1f)
                .put("artworkHeightScale", preset.artworkHeightScale ?: 1f)
                .put("legacyGaps", preset.blockGaps ?: CapsuleLightBaseGapsEncoded)
                .put("lyricLineEnabled", preset.lyricLineEnabled ?: true)
        }

        return JSONObject()
            .put("format", CAPSULE_DESIGN_PRESET_FORMAT)
            .put("version", CAPSULE_DESIGN_PRESET_VERSION)
            .put("target", preset.target.name)
            .put("layout", layout)
            .toString(2)
    }

    fun decode(raw: String): CapsuleDesignPreset? =
        runCatching {
            val root = JSONObject(raw.trim())
            require(root.optString("format") == CAPSULE_DESIGN_PRESET_FORMAT)

            val version = root.optInt("version", -1)
            require(version in 1..CAPSULE_DESIGN_PRESET_VERSION)

            val target =
                CapsuleCustomizeTarget.valueOf(
                    root.getString("target").uppercase(Locale.US),
                )
            val layout = root.getJSONObject("layout")

            val layoutOrder =
                when (target) {
                    CapsuleCustomizeTarget.LIGHT ->
                        encodeCapsuleLightOrder(
                            decodeCapsuleLightOrder(
                                layout.optString("order", CapsuleLightBaseOrderEncoded),
                            ),
                        )

                    CapsuleCustomizeTarget.IMMERSIVE ->
                        encodeCapsuleImmersiveOrder(
                            decodeCapsuleImmersiveOrder(
                                layout.optString("order", CapsuleImmersiveBaseOrderEncoded),
                            ),
                        )
                }

            val canvasPositions =
                encodeCapsuleLightCanvasPositions(
                    decodeCapsuleLightCanvasPositions(
                        layout.optString("positions", CapsuleLightCanvasPositionsBaseEncoded),
                    ),
                )

            val metadataOrder =
                encodeCapsuleLightMetadataOrder(
                    decodeCapsuleLightMetadataOrder(
                        layout.optString(
                            "metadataOrder",
                            CapsuleLightMetadataBaseOrderEncoded,
                        ),
                    ),
                )
            val modeOrder =
                encodeCapsuleLightModeOrder(
                    decodeCapsuleLightModeOrder(
                        layout.optString("modeOrder", CapsuleLightModeBaseOrderEncoded),
                    ),
                )
            val avOrder =
                encodeCapsuleLightAvOrder(
                    decodeCapsuleLightAvOrder(
                        layout.optString("avOrder", CapsuleLightAvBaseOrderEncoded),
                    ),
                )
            val transportOrder =
                encodeCapsuleLightTransportOrder(
                    decodeCapsuleLightTransportOrder(
                        layout.optString(
                            "transportOrder",
                            CapsuleLightTransportBaseOrderEncoded,
                        ),
                    ),
                )

            if (target == CapsuleCustomizeTarget.LIGHT) {
                val widthScale =
                    layout
                        .optDouble("artworkWidthScale", 1.0)
                        .toFloat()
                        .takeIf { it.isFinite() }
                        ?.coerceIn(0.55f, 1.08f)
                        ?: 1f
                val heightScale =
                    layout
                        .optDouble("artworkHeightScale", 1.0)
                        .toFloat()
                        .takeIf { it.isFinite() }
                        ?.coerceIn(0.55f, 1.35f)
                        ?: 1f
                val gaps =
                    encodeCapsuleLightBlockGaps(
                        decodeCapsuleLightBlockGaps(
                            layout.optString("legacyGaps", CapsuleLightBaseGapsEncoded),
                        ),
                    )

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
                    lyricLineEnabled = layout.optBoolean("lyricLineEnabled", true),
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
