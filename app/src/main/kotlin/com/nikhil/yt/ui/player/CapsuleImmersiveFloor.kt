/*
 * Capsule MUSIC — artwork-driven colours for the Immersion player only.
 * The controls and all other player designs do not consume this state.
 * GPL-3.0
 */
package com.nikhil.yt.ui.player

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.nikhil.yt.innertube.toHighResThumbnail
import com.nikhil.yt.models.MediaMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

internal val IMMERSIVE_NEUTRAL_COLOR = Color(0xFF262626)
private const val ARTWORK_SAMPLE_SIZE = 256
private val videoIdPattern = Regex("^[a-zA-Z0-9_-]{11}$")

internal data class ImmersiveArtworkTone(
    val edge: Color = IMMERSIVE_NEUTRAL_COLOR,
    val landscape: Boolean = false,
    val accent: Color = IMMERSIVE_NEUTRAL_COLOR,
    val bottomTexture: Float = 0f,
    // Seven left-to-right colours from the actual visible artwork crop.
    val edgeBands: List<Color> = emptyList(),
    // True two-dimensional colour, sampled once from the same decoded crop.
    val colorField: CapsuleColorField? = null,
    // Accurate, spatially sampled lower edge of the displayed (overscanned) crop.
    // Drives the photo-to-floor dissolve; the coarse all-image grid cannot
    // accurately match the colour at the image's last visible scanline.
    val visibleTail: CapsuleColorField? = null,
    // Wider photographic overlap; does not participate in palette/floor extraction.
    val diffusionTail: CapsuleColorField? = null,
    // Decoded 256 px preview for the very first frame of an immediate open.
    // This avoids a second network request before any image can be displayed.
    val preview: ImageBitmap? = null,
    // Only genuinely near-white, nearly neutral covers need the new soft
    // seam treatment. Every other album gets the ORIGINAL palette and fade.
    val nearWhiteBackground: Boolean = false,
    val displayUrl: String? = null,
    // A thumbnail URL alone does not imply the dimensions and background are prepared.
    // The player must not render the raw 16:9 preview while this is false.
    val ready: Boolean = false,
)

/** The raw 16:9 image and its coloured floor must become visible in the same frame. */
internal fun canRevealImmersiveArtwork(tone: ImmersiveArtworkTone, imageLoaded: Boolean): Boolean =
    tone.ready && !tone.displayUrl.isNullOrBlank() &&
        (imageLoaded || tone.preview != null)

private val immersiveArtworkCache = object : LinkedHashMap<String, ImmersiveArtworkTone>(24, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImmersiveArtworkTone>?): Boolean =
        size > 16
}

/**
 * ArchiveTune's relevant visual behaviour: try actual YouTube video thumbnails (when the
 * original artwork already comes from ytimg), display widescreen frames with Fit over a
 * separate, filled background, and extract a Palette from the SAME decoded image that
 * will be displayed. Do not prefetch a YouTube image for every ordinary square album cover.
 */
internal fun immersiveArtworkCandidates(mediaId: String?, original: String?): List<String> {
    if (original.isNullOrBlank()) return emptyList()
    // The shared toHighResThumbnail() can still leave Google's 120–512px
    // sources undersized for the full-bleed Immersion photo. The sampled
    // palette and visible bitmap must use the SAME upgraded URL so their
    // crop and lower-edge colours remain identical.
    val libraryHighRes = original.toHighResThumbnail()
    val base = com.nikhil.yt.ui.component.capsuleArtworkUrl(libraryHighRes, 960)
        ?: libraryHighRes
    if (mediaId == null || !videoIdPattern.matches(mediaId) ||
        (!original.contains("ytimg.com/vi/", ignoreCase = true) &&
            !original.contains("ytimg.com/vi_webp/", ignoreCase = true))
    ) return listOf(base)

    return (
        listOf(
            "https://i.ytimg.com/vi/$mediaId/maxresdefault.jpg",
            "https://i.ytimg.com/vi/$mediaId/hq720.jpg",
        ) + capsuleFullPlayerArtworkCandidates(original)
    ).distinct()
}

@Composable
internal fun rememberImmersiveEdgeColor(
    mediaMetadata: MediaMetadata?,
    enabled: Boolean = true,
    visibleArtworkAspectRatio: Float = 1f,
    refreshOnOpen: Boolean = false,
): ImmersiveArtworkTone {
    val context = LocalContext.current
    var pinnedSource by remember(mediaMetadata?.id) { mutableStateOf<String?>(null) }
    val sourceUrl = pinnedSource ?: mediaMetadata?.thumbnailUrl?.takeIf(String::isNotBlank)
    // A different viewport shows a different centre-cropped area of the same frame.
    val artworkAspect = visibleArtworkAspectRatio.coerceIn(0.55f, 2.2f)
    val key = mediaMetadata?.id?.let { "$it|$sourceUrl|${(artworkAspect * 100f).toInt()}" }
    var resolved by remember { mutableStateOf<ImmersiveArtworkTone?>(null) }
    var resolvedKey by remember { mutableStateOf<String?>(null) }
    val cached = key?.let { synchronized(immersiveArtworkCache) { immersiveArtworkCache[it] } }
    var failureRetry by remember(key) { mutableIntStateOf(0) }
    // A failed decode needs a second chance when the user reopens the
    // player, but opening during an IN-FLIGHT decode must not cancel it.
    LaunchedEffect(key, refreshOnOpen) {
        if (refreshOnOpen && resolvedKey == key && resolved?.ready != true) {
            failureRetry++
        }
    }

    LaunchedEffect(key, sourceUrl, enabled, failureRetry) {
        // The player is still composed while its sheet is collapsed. Avoid spending
        // energy (or trying multiple ytimg qualities) for rapidly skipped unseen songs.
        if (!enabled) return@LaunchedEffect
        if (key == null || sourceUrl.isNullOrBlank()) {
            resolved = ImmersiveArtworkTone()
            resolvedKey = key
            return@LaunchedEffect
        }
        cached?.let {
            pinnedSource = sourceUrl
            resolved = it
            resolvedKey = key
            return@LaunchedEffect
        }
        // A failed sample must be retried when the user reopens this sheet; do not
        // permanently freeze an otherwise valid thumbnail in the grey placeholder.
        if (resolvedKey == key && resolved?.ready == true) return@LaunchedEffect

        val candidates = immersiveArtworkCandidates(mediaMetadata.id, sourceUrl)
        val isYtimg = candidates.size > 1
        var resultTone: ImmersiveArtworkTone? = null

        for ((index, url) in candidates.withIndex()) {
            val image = try {
                val request = ImageRequest.Builder(context)
                    .data(url)
                    .size(ARTWORK_SAMPLE_SIZE)
                    .allowHardware(false)
                    .build()
                val result = withContext(Dispatchers.IO) { context.imageLoader.execute(request) }
                (result as? SuccessResult)?.image?.toBitmap()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            } ?: continue

            val landscape = image.width.toFloat() / image.height.coerceAtLeast(1) >= 4f / 3f
            // maxresdefault/hq720 may return a small 'unavailable thumbnail' image with HTTP
            // 200. Those must not replace the actual song cover or contaminate its palette.
            if (isYtimg && (!landscape || image.width < 224)) continue

            resultTone = withContext(Dispatchers.Default) {
                // ORIGINAL Immersion colour extraction: the modal background
                // in the lower visible centre crop, not a full-artwork palette.
                // Only near-white, low-saturation covers bypass the original
                // brightness clamp so the isolated softer fade can eliminate
                // the noticeable white->grey edge.
                // The visible BOTTOM of the displayed crop (including overscan)
                // must drive both the seam and the entire controls floor.
                // Sampling 72–95% of the source used to pick the distant sky or
                // mid-artwork, creating an unrelated muddy brown on the bottom.
                val visibleTail = image.capsuleImmersiveVisibleTail(artworkAspect, landscape)
                val rawEdge = capsuleDominantEdgeColor(visibleTail)
                val nearWhite = rawEdge.isNearWhiteImmersiveBackground()
                val bottomTexture = image.immersiveBottomTexture(artworkAspect)
                val colorField = image.capsuleColorField(artworkAspect)
                // Legacy colour bands remain available for old snapshots and
                // incomplete artwork, but do NOT drive the normal backdrop.
                val edgeBands = emptyList<Color>()
                ImmersiveArtworkTone(
                    // Do not clamp this colour: a clamped seam is visibly
                    // darker than the last actual row of the artwork.
                    edge = rawEdge,
                    accent = capsuleCleanImmersiveFloor(visibleTail),
                    bottomTexture = bottomTexture,
                    edgeBands = edgeBands,
                    colorField = colorField,
                    visibleTail = visibleTail,
                    diffusionTail = image.capsuleImmersiveVisibleTail(
                        artworkAspect, landscape, fraction = 1f - IMMERSIVE_DIFFUSION_START,
                    ),
                    // Own the preview pixels independently of Coil's bitmap
                    // pool. A retained player frame must never point at a
                    // bitmap that another request can recycle/reuse.
                    preview = image.copy(Bitmap.Config.ARGB_8888, false).asImageBitmap(),
                    nearWhiteBackground = nearWhite,
                    landscape = landscape,
                    displayUrl = url,
                    ready = true,
                )
            }
            break
        }
        // A failed decode has neither reliable crop geometry nor a sampled background.
        // Leave the player neutral instead of showing a raw thumbnail with black bars.
        val finalTone = resultTone ?: ImmersiveArtworkTone()
        // Cache only a real decoded image with a sampled background. Transient thumbnail
        // errors should not poison every later attempt for this same track.
        if (resultTone != null) {
            pinnedSource = sourceUrl
            synchronized(immersiveArtworkCache) { immersiveArtworkCache[key] = finalTone }
        }
        resolved = finalTone
        resolvedKey = key
    }

    val target = when {
        cached != null -> cached
        resolvedKey == key -> resolved ?: ImmersiveArtworkTone()
        else -> ImmersiveArtworkTone()
    }
    // A colour animation made the background visibly build itself after the cover appeared.
    // The sample is computed off-thread; apply the completed palette atomically with the
    // matching decoded image in the player. Video-card transitions are animated separately.
    // Do NOT expose the unsampled original URL during a new selection: AsyncImage used
    // to draw it immediately with the default square zoom before the real 16:9 dimensions
    // and matching background had been calculated.
    val readyForCurrentTrack = target.ready && (cached != null || resolvedKey == key)
    return target.copy(
        edge = target.edge,
        accent = target.accent,
        displayUrl = if (readyForCurrentTrack) target.displayUrl else null,
        ready = readyForCurrentTrack,
    )
}

/**
 * A clean, readable controls floor derived exclusively from the last two
 * rows of the *visible* cover. Preserve the real hue, avoid mixing unrelated
 * sky/subject RGB samples or artificially forcing warm/cold colour families.
 *
 * Called just once during off-main artwork preparation, never during drawing.
 */
internal fun capsuleCleanImmersiveFloor(edgeMap: CapsuleColorField): Color {
    // Scene-specific floor: distinguish black/neutral area from genuine
    // coloured objects. RGB medians of black + magenta + skin produced a
    // completely unrelated muddy grey/brown even though no such area existed
    // in the art. The last four visible rows carry the strongest weight.
    val scores = FloatArray(24)
    val red = FloatArray(24)
    val green = FloatArray(24)
    val blue = FloatArray(24)
    val hueSample = FloatArray(3)
    val neutrals = ArrayList<Float>(edgeMap.width * 4)
    var weightedChromatic = 0f
    var weightedTotal = 0f
    val first = (edgeMap.height - 4).coerceAtLeast(0)

    for (y in first until edgeMap.height) for (x in 0 until edgeMap.width) {
        val c = edgeMap.cell(x, y)
        val top = maxOf(c.red, c.green, c.blue)
        val low = minOf(c.red, c.green, c.blue)
        val chroma = top - low
        val sat = if (top > 0.0001f) chroma / top else 0f
        val rowWeight = (1f + y - first) / (edgeMap.height - first)
        weightedTotal += rowWeight
        neutrals.add(top)
        if (sat < 0.18f || top < 0.15f) continue
        weightedChromatic += rowWeight
        android.graphics.Color.colorToHSV(c.toArgb(), hueSample)
        val bin = (hueSample[0] / 15f).toInt().coerceIn(0, 23)
        val weight = rowWeight * (0.30f + 0.70f * sat) *
            (0.45f + 0.55f * top)
        scores[bin] += weight
        red[bin] += weight * c.red
        green[bin] += weight * c.green
        blue[bin] += weight * c.blue
    }

    val chromaticCoverage = weightedChromatic / weightedTotal.coerceAtLeast(0.0001f)
    val winner = scores.indices.maxByOrNull { scores[it] } ?: 0
    if (chromaticCoverage >= 0.16f && scores[winner] > 0.001f) {
        // Use a colour that genuinely appears in the image, without combining
        // unlike hues. Adjust only its luminance and mild saturation for text.
        val source = Color(
            red[winner] / scores[winner],
            green[winner] / scores[winner],
            blue[winner] / scores[winner],
        )
        android.graphics.Color.colorToHSV(source.toArgb(), hueSample)
        hueSample[1] = (hueSample[1] * 0.78f).coerceIn(0f, 0.62f)
        hueSample[2] = (0.085f + hueSample[2] * 0.21f).coerceIn(0.095f, 0.285f)
        return Color(android.graphics.Color.HSVToColor(hueSample))
    }

    // Black/white artwork must not acquire arbitrary purple or brown.
    // The lower 40th percentile is resistant to a white logo on black.
    neutrals.sort()
    val value = neutrals[(neutrals.size * 0.40f).toInt()
        .coerceIn(0, neutrals.lastIndex)]
    val shade = (0.065f + value * 0.17f).coerceIn(0.065f, 0.24f)
    return Color(shade, shade, shade)
}

/** Only near-white, nearly neutral backgrounds receive the slightly wider fade. */
internal fun Color.isNearWhiteImmersiveBackground(): Boolean {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(toArgb(), hsv)
    return hsv[1] <= 0.12f && hsv[2] >= 0.88f
}

/**
 * Darken the colour actually extracted from the lower visible artwork crop.
 * Preserve its own hue; never inject a synthetic blue/slate/graphite tint.
 * For achromatic artwork saturation remains zero.
 *
 * Only called when decoding/sampling the artwork off the UI thread.
 */
internal fun Color.immersiveMutedAccent(): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(toArgb(), hsv)
    hsv[1] = (hsv[1] * 0.92f).coerceIn(0f, 1f)
    hsv[2] = (hsv[2] * 0.78f).coerceIn(0.22f, 0.82f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

/**
 * Determine the backdrop directly behind the fade, not the picture's vibrant foreground.
 *
 * This samples the same centre crop shown by ContentScale.Crop at the provided viewport
 * aspect. Only the bottom portion before the fade is considered. The modal colour block
 * (not an average of unrelated objects) is used so sparse green lettering on white paper
 * cannot recolour the entire player green. Edge pixels get a small weight: they tend to
 * describe the image's background rather than its foreground subject.
 *
 * Internal for small synthetic-bitmap regression tests.
 */
internal fun Bitmap.immersiveBottomBackground(artworkAspect: Float): Color {
    if (width <= 0 || height <= 0) return IMMERSIVE_NEUTRAL_COLOR
    val targetAspect = artworkAspect.coerceIn(0.55f, 2.2f)
    val naturalAspect = width.toFloat() / height
    val visibleWidth = if (naturalAspect > targetAspect) (height * targetAspect).toInt() else width
    val visibleHeight = if (naturalAspect < targetAspect) (width / targetAspect).toInt() else height
    val x0 = ((width - visibleWidth) / 2).coerceAtLeast(0)
    val y0 = ((height - visibleHeight) / 2).coerceAtLeast(0)
    val w = visibleWidth.coerceAtLeast(1)
    val h = visibleHeight.coerceAtLeast(1)
    // Analyse the lower part of the *image* before our overlay hides it. The very last
    // rows of a YouTube thumbnail may be baked-in black letterboxing or a logo.
    val top = (y0 + h * 0.72f).toInt().coerceIn(0, height - 1)
    val bottom = (y0 + h * 0.95f).toInt().coerceIn(top + 1, height)
    val stepX = (w / 112).coerceAtLeast(1)
    val stepY = ((bottom - top) / 52).coerceAtLeast(1)
    // Small quantised bins group a textured background without combining distinct hues.
    val counts = IntArray(512)
    val rSums = LongArray(512)
    val gSums = LongArray(512)
    val bSums = LongArray(512)
    var samples = 0
    for (y in top until bottom step stepY) {
        for (x in x0 until (x0 + w).coerceAtMost(width) step stepX) {
            val argb = getPixel(x, y)
            if ((argb ushr 24 and 0xFF) < 128) continue
            val r = argb shr 16 and 0xFF
            val g = argb shr 8 and 0xFF
            val b = argb and 0xFF
            // Ignore letterbox and fine black linework unless the entire artwork is dark.
            if (maxOf(r, g, b) < 24) continue
            val bucket = ((r shr 5) shl 6) or ((g shr 5) shl 3) or (b shr 5)
            val relativeX = (x - x0).toFloat() / w
            val weight = if (relativeX < 0.22f || relativeX > 0.78f) 2 else 1
            counts[bucket] += weight
            rSums[bucket] += (r * weight).toLong()
            gSums[bucket] += (g * weight).toLong()
            bSums[bucket] += (b * weight).toLong()
            samples += weight
        }
    }
    if (samples == 0) return Color(0xFF1D1D1D)
    val dominant = counts.indices.maxByOrNull { counts[it] } ?: return IMMERSIVE_NEUTRAL_COLOR
    val n = counts[dominant].coerceAtLeast(1)
    return Color(
        red = (rSums[dominant] / n).toInt(),
        green = (gSums[dominant] / n).toInt(),
        blue = (bSums[dominant] / n).toInt(),
    )
}

/**
 * Cheap difficulty score for the lower visible crop.
 *
 * This does NOT choose or modify colour. It only measures local luminance changes so dense
 * line-art, text, checker patterns and hard black/white edges can receive a longer dissolve.
 * The bitmap is already the same small 256px sample used for Immersive colour extraction.
 */
internal fun Bitmap.immersiveBottomTexture(artworkAspect: Float): Float {
    if (width <= 1 || height <= 1) return 0f

    val targetAspect = artworkAspect.coerceIn(0.55f, 2.2f)
    val naturalAspect = width.toFloat() / height
    val visibleWidth =
        if (naturalAspect > targetAspect) {
            (height * targetAspect).toInt()
        } else {
            width
        }
    val visibleHeight =
        if (naturalAspect < targetAspect) {
            (width / targetAspect).toInt()
        } else {
            height
        }

    val x0 = ((width - visibleWidth) / 2).coerceAtLeast(0)
    val y0 = ((height - visibleHeight) / 2).coerceAtLeast(0)
    val w = visibleWidth.coerceAtLeast(1)
    val h = visibleHeight.coerceAtLeast(1)

    val top = (y0 + h * 0.68f).toInt().coerceIn(0, height - 1)
    val bottom = (y0 + h * 0.96f).toInt().coerceIn(top + 1, height)
    val stepX = (w / 96).coerceAtLeast(1)
    val stepY = ((bottom - top) / 42).coerceAtLeast(1)

    var totalDifference = 0f
    var comparisons = 0
    var previousRow = FloatArray(0)
    var rowIndex = 0

    for (y in top until bottom step stepY) {
        val row = ArrayList<Float>()
        var previous: Float? = null

        for (x in x0 until (x0 + w).coerceAtMost(width) step stepX) {
            val argb = getPixel(x, y)
            val r = (argb shr 16 and 0xFF).toFloat()
            val g = (argb shr 8 and 0xFF).toFloat()
            val b = (argb and 0xFF).toFloat()
            val luminance = 0.2126f * r + 0.7152f * g + 0.0722f * b

            previous?.let {
                totalDifference += abs(luminance - it)
                comparisons += 1
            }

            val column = row.size
            if (rowIndex > 0 && column < previousRow.size) {
                totalDifference += abs(luminance - previousRow[column])
                comparisons += 1
            }

            row += luminance
            previous = luminance
        }

        previousRow = row.toFloatArray()
        rowIndex += 1
    }

    if (comparisons == 0) return 0f

    return ((totalDifference / comparisons) / 72f)
        .coerceIn(0f, 1f)
}



/** Keep greys grey: increasing the saturation of a neutral background tinted white art. */
internal fun Color.comfortableImmersiveColor(): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(toArgb(), hsv)
    // Preserve background hue AND saturation; only limit luminance for white controls.
    // In particular, do not manufacture 32% saturation for greyscale artwork.
    hsv[2] = hsv[2].coerceIn(0.17f, 0.44f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

/**
 * Samples the bottom edge of the same centre crop that the player displays.
 * A 7-column, quantized modal estimator is robust to labels and foreground
 * objects and retains asymmetric colours that a single RGB average destroys.
 * Called only once per decoded cover on Dispatchers.Default, never per frame.
 */
internal fun Bitmap.immersiveBottomEdgeBands(artworkAspect: Float): List<Color> {
    if (width <= 0 || height <= 0) return emptyList()
    val aspect = artworkAspect.coerceIn(0.55f, 2.2f)
    val natural = width.toFloat() / height
    val visibleWidth = (if (natural > aspect) height * aspect else width.toFloat()).toInt()
        .coerceIn(1, width)
    val visibleHeight = (if (natural < aspect) width / aspect else height.toFloat()).toInt()
        .coerceIn(1, height)
    val left = (width - visibleWidth) / 2
    val top = (height - visibleHeight) / 2
    val yStart = (top + visibleHeight * 0.73f).toInt().coerceIn(0, height - 1)
    val yEnd = (top + visibleHeight * 0.96f).toInt().coerceIn(yStart + 1, height)
    val bandCount = 7
    return List(bandCount) { band ->
        val xStart = left + visibleWidth * band / bandCount
        val xEnd = (left + visibleWidth * (band + 1) / bandCount).coerceAtMost(width)
        val counts = IntArray(512)
        val red = LongArray(512)
        val green = LongArray(512)
        val blue = LongArray(512)
        val stepX = ((xEnd - xStart) / 24).coerceAtLeast(1)
        val stepY = ((yEnd - yStart) / 32).coerceAtLeast(1)
        for (y in yStart until yEnd step stepY) {
            for (x in xStart until xEnd.coerceAtLeast(xStart + 1) step stepX) {
                val pixel = getPixel(x.coerceIn(0, width - 1), y)
                val r = pixel shr 16 and 255
                val g = pixel shr 8 and 255
                val b = pixel and 255
                val bucket = ((r shr 5) shl 6) or ((g shr 5) shl 3) or (b shr 5)
                counts[bucket]++
                red[bucket] += r.toLong()
                green[bucket] += g.toLong()
                blue[bucket] += b.toLong()
            }
        }
        val best = counts.indices.maxByOrNull { counts[it] } ?: 0
        val n = counts[best].coerceAtLeast(1)
        Color(
            red = (red[best].toFloat() / n / 255f).coerceIn(0f, 1f),
            green = (green[best].toFloat() / n / 255f).coerceIn(0f, 1f),
            blue = (blue[best].toFloat() / n / 255f).coerceIn(0f, 1f),
        )
    }
}
