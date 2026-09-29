/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */



package com.nikhil.yt.ui.component

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.foundation.text.InlineTextContent
import kotlin.math.sin
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.activity.compose.BackHandler
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import android.view.WindowManager
import androidx.palette.graphics.Palette
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.nikhil.yt.LocalPlayerConnection
import com.nikhil.yt.R
import com.nikhil.yt.constants.CapsulePlayerDesign
import com.nikhil.yt.constants.CapsulePlayerDesignKey
import com.nikhil.yt.constants.DarkModeKey
import com.nikhil.yt.constants.LyricsClickKey
import com.nikhil.yt.constants.LyricsRomanizeJapaneseKey
import com.nikhil.yt.constants.LyricsRomanizeKoreanKey
import com.nikhil.yt.constants.LyricsScrollKey
import com.nikhil.yt.constants.LyricsSyncOffsetKey
import com.nikhil.yt.constants.LyricsTextPositionKey
import com.nikhil.yt.constants.LyricsAnimationStyle
import com.nikhil.yt.constants.LyricsAnimationStyleKey
import com.nikhil.yt.constants.LyricsTextSizeKey
import com.nikhil.yt.constants.LyricsUsePlayerThemeKey
import com.nikhil.yt.constants.LyricsLineSpacingKey
import com.nikhil.yt.constants.PlayerBackgroundStyle
import com.nikhil.yt.constants.PlayerBackgroundStyleKey
import com.nikhil.yt.constants.UseSystemFontKey
import com.nikhil.yt.db.entities.LyricsEntity.Companion.LYRICS_NOT_FOUND
import com.nikhil.yt.lyrics.LyricsEntry
import com.nikhil.yt.lyrics.LyricsUtils.isChinese
import com.nikhil.yt.lyrics.LyricsUtils.findCurrentLineIndex
import com.nikhil.yt.lyrics.LyricsUtils.isJapanese
import com.nikhil.yt.lyrics.LyricsUtils.isKorean
import com.nikhil.yt.lyrics.LyricsUtils.isTtml
import com.nikhil.yt.lyrics.LyricsUtils.parseLyrics
import com.nikhil.yt.lyrics.LyricsUtils.parseTtml
import com.nikhil.yt.lyrics.LyricsUtils.romanizeJapanese
import com.nikhil.yt.lyrics.LyricsUtils.romanizeKorean
import com.nikhil.yt.ui.component.shimmer.ShimmerHost
import com.nikhil.yt.ui.component.shimmer.TextPlaceholder
import com.nikhil.yt.ui.menu.LyricsMenu
import com.nikhil.yt.ui.menu.clampOffset
import com.nikhil.yt.ui.screens.settings.DarkMode
import com.nikhil.yt.ui.screens.settings.LyricsPosition
import com.nikhil.yt.ui.utils.fadingEdge
import com.nikhil.yt.ui.utils.smoothFadingEdge
import com.nikhil.yt.utils.ComposeToImage
import com.nikhil.yt.utils.rememberEnumPreference
import com.nikhil.yt.utils.rememberPreference
import com.nikhil.yt.utils.reportRecoverableException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.pow
import kotlin.math.abs
import kotlin.math.exp
import com.nikhil.yt.ui.motion.CapsuleStandardEasing


private val AppleMusicEasing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1.0f)
private val ReturnToSyncApproachEasing = CubicBezierEasing(0.20f, 0.48f, 0.42f, 0.90f)
private val ReturnToSyncSettleEasing = CubicBezierEasing(0.18f, 0.58f, 0.22f, 1f)
private val SmoothDecelerateEasing = CubicBezierEasing(0.0f, 0.0f, 0.2f, 1.0f)

private fun isRtlText(text: String): Boolean {
    for (ch in text) {
        when (Character.getDirectionality(ch)) {
            Character.DIRECTIONALITY_RIGHT_TO_LEFT,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_EMBEDDING,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_OVERRIDE -> return true

            Character.DIRECTIONALITY_LEFT_TO_RIGHT,
            Character.DIRECTIONALITY_LEFT_TO_RIGHT_EMBEDDING,
            Character.DIRECTIONALITY_LEFT_TO_RIGHT_OVERRIDE -> return false
        }
    }
    return false
}

private fun rtlAwareHorizontalGradient(
    isRtl: Boolean,
    vararg colorStops: Pair<Float, Color>
): Brush {
    val stops =
        if (isRtl) {
            colorStops
                .map { (f, c) -> (1f - f).coerceIn(0f, 1f) to c }
                .sortedBy { it.first }
        } else {
            colorStops.toList()
        }
    return Brush.horizontalGradient(*stops.toTypedArray())
}


/**
 * Renders a single word with karaoke fill animation.
 * Optimized to perform animation in the draw phase to avoid recomposition.
 */
@Composable
private fun KaraokeWord(
    text: String,
    startTime: Long,
    endTime: Long,
    currentTimeProvider: () -> Long,
    isRtl: Boolean,
    fontSize: TextUnit,
    textColor: Color,
    inactiveAlpha: Float,
    fontWeight: FontWeight = FontWeight.ExtraBold,
    isBackground: Boolean = false,
    nudgeEnabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val duration = endTime - startTime
    val glowPadding = 10.dp // Reduced to 10dp for tighter spacing

    Box(
        modifier = modifier
            .layout { measurable, constraints ->
                val glowPaddingPx = glowPadding.roundToPx()
                val looseConstraints = constraints.copy(
                    minWidth = 0,
                    maxWidth = Constraints.Infinity,
                    minHeight = 0,
                    maxHeight = Constraints.Infinity
                )
                val placeable = measurable.measure(looseConstraints)
                
                val coreWidth = (placeable.width - glowPaddingPx * 2).coerceAtLeast(0)
                val coreHeight = (placeable.height - glowPaddingPx * 2).coerceAtLeast(0)
                
                layout(coreWidth, coreHeight) {
                    placeable.place(-glowPaddingPx, -glowPaddingPx)
                }
            }
            .graphicsLayer {
                clip = false
                val currentTime = currentTimeProvider()
                
                // Nudge parameters
                val maxShift = 5f
                val attackDuration = 120L
                val decayDuration = 250L
                val totalImpulseTime = attackDuration + decayDuration
                
                val shift = if (nudgeEnabled && currentTime >= startTime && currentTime < startTime + totalImpulseTime) {
                    val timeSinceStart = currentTime - startTime
                    if (timeSinceStart < attackDuration) {
                        // Attack: 0 -> max
                        val progress = timeSinceStart.toFloat() / attackDuration.toFloat()
                        androidx.compose.ui.util.lerp(0f, maxShift, progress)
                    } else {
                        // Decay: max -> 0
                        val decayProgress = (timeSinceStart - attackDuration).toFloat() / decayDuration.toFloat()
                        androidx.compose.ui.util.lerp(maxShift, 0f, decayProgress)
                    }
                } else {
                    0f
                }
                
                translationX = if (isRtl) -shift else shift
            }
    ) {
        // 1. Inactive (unfilled) layer
        val effectiveFontSize = if (isBackground) fontSize * 0.7f else fontSize
        val effectiveAlpha = if (isBackground) 0.6f else 1f
        
        Text(
            text = text,
            fontSize = effectiveFontSize,
            color = textColor.copy(alpha = inactiveAlpha * effectiveAlpha),
            fontWeight = fontWeight,
            modifier = Modifier.padding(glowPadding)
        )

        // 2. Completed (filled) layer
        Text(
            text = text,
            fontSize = effectiveFontSize,
            color = textColor.copy(alpha = effectiveAlpha),
            fontWeight = fontWeight,
            modifier = Modifier
                .padding(glowPadding)
                .drawWithContent {
                    val currentTime = currentTimeProvider()
                    val isDone = currentTime >= endTime
                    if (isDone) {
                        drawContent()
                    }
                }
        )

        // 3. Active (filling) layer - SOFT MASK (no glow)
        Box(
            modifier = Modifier
                .graphicsLayer {
                     compositingStrategy = CompositingStrategy.Offscreen
                     
                    val currentTime = currentTimeProvider()
                    val fadeDuration = 200L
                    
                    if (currentTime >= endTime) {
                        val timeSinceEnd = currentTime - endTime
                        val fadeProgress = (timeSinceEnd.toFloat() / fadeDuration.toFloat()).coerceIn(0f, 1f)
                        alpha = 1f - fadeProgress
                    } else {
                        alpha = 1f
                    }
                }
                .drawWithContent {
                    val currentTime = currentTimeProvider()
                    val progress = if (duration > 0) {
                        val elapsed = currentTime - startTime
                        (elapsed.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
                    } else if (currentTime >= endTime) {
                        1f
                    } else {
                        0f
                    }

                    val fadeDuration = 200L
                    val isFading = currentTime >= endTime && currentTime < (endTime + fadeDuration)
                    
                    if ((progress > 0f && progress < 1f) || isFading) {
                        drawContent()
                        
                        val fadeWidth = 20f 
                        val totalWidth = size.width
                        val paddingPx = glowPadding.toPx()
                        
                        // Calculated relative to the padded box
                        val textWidth = totalWidth - (paddingPx * 2)
                        
                        // Fill width based on text width
                        val fillWidth = textWidth * progress
                        
                        val endFraction = (paddingPx + fillWidth + fadeWidth) / totalWidth
                        val solidFraction = (paddingPx + fillWidth) / totalWidth

                        val softFillBrush =
                            if (!isRtl) {
                                Brush.horizontalGradient(
                                    0f to Color.Black,
                                    solidFraction.coerceAtLeast(0f) to Color.Black,
                                    endFraction.coerceAtMost(1f) to Color.Transparent
                                )
                            } else {
                                val solidStartX = (paddingPx + (textWidth - fillWidth)).coerceIn(0f, totalWidth)
                                val fadeStartX = (solidStartX - fadeWidth).coerceIn(0f, totalWidth)
                                val fadeStartFraction = (fadeStartX / totalWidth).coerceIn(0f, 1f)
                                val solidStartFraction = (solidStartX / totalWidth).coerceIn(0f, 1f)
                                Brush.horizontalGradient(
                                    0f to Color.Transparent,
                                    fadeStartFraction to Color.Transparent,
                                    solidStartFraction to Color.Black,
                                    1f to Color.Black
                                )
                            }
                        
                        drawRect(
                            brush = softFillBrush,
                            blendMode = BlendMode.DstIn
                        )
                    }
                }
                .padding(glowPadding)
        ) {
             Text(
                text = text,
                fontSize = effectiveFontSize,
                color = textColor.copy(alpha = effectiveAlpha),
                fontWeight = fontWeight
                // Removed shadow effect to match player's clean style
             )
        }
    }
}


@Composable
private fun ArchiveTuneWord(
    text: String,
    startTime: Long,
    endTime: Long,
    currentTime: Long,
    isRtl: Boolean,
    fontSize: TextUnit,
    textColor: Color,
    isBackground: Boolean,
    lineFocus: Float,
    motionEnabled: Boolean,
    releaseCompleted: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val duration = (endTime - startTime).coerceAtLeast(1L)
    val isComplete = releaseCompleted || currentTime >= endTime
    val isActive = !releaseCompleted && currentTime in startTime until endTime
    val progress =
        when {
            isComplete -> 1f
            currentTime <= startTime -> 0f
            else -> ((currentTime - startTime).toFloat() / duration).coerceIn(0f, 1f)
        }
    val safeLineFocus = lineFocus.coerceIn(0f, 1f)
    val wave = sin(progress * Math.PI).toFloat()
    // The word is always measured at its maximum size, so FlowRow never needs to reflow when
    // focus moves to this line. Only the already-reserved visual layer moves from 96% -> 100%.
    /*
     * The previous version fed a new target into two animateFloatAsState instances on every lyric
     * clock tick. That repeatedly restarted tiny per-word animations and caused occasional frame
     * spikes on long lines. The wave is already eased, so drawing its sampled value directly is
     * both smoother under load and much cheaper.
     */
    val scale =
        if (motionEnabled && isActive) {
            (0.96f + 0.04f * wave * safeLineFocus).coerceIn(0.96f, 1f)
        } else {
            0.96f
        }
    val lift =
        if (motionEnabled && isActive) {
            -3.2f * wave * safeLineFocus
        } else {
            0f
        }
    val glowProgress = (progress * 2f).coerceAtMost(1f)
    val glowAlpha =
        if (motionEnabled && isActive) {
            glowProgress * 0.34f * safeLineFocus
        } else {
            0f
        }
    val glowRadius = if (glowAlpha > 0f) glowProgress * 8f else 0f
    val effectiveFontSize = if (isBackground) fontSize * 0.82f else fontSize
    // As focus arrives, the subdued line smoothly trades its diffuse light for the word reveal.
    val restingAlpha = if (isBackground) 0.54f else 0.70f
    val focusedBaseAlpha = if (isBackground) 0.22f else 0.30f
    val baseAlpha =
        restingAlpha + (focusedBaseAlpha - restingAlpha) * safeLineFocus
    val revealAlpha =
        safeLineFocus * if (isBackground) 0.78f else 1f
    val fontWeight = FontWeight.Bold

    Box(
        modifier =
            modifier.graphicsLayer {
                clip = false
                translationY = lift
                scaleX = scale
                scaleY = scale
            },
    ) {
        Text(
            text = text,
            fontSize = effectiveFontSize,
            color = textColor.copy(alpha = baseAlpha),
            fontWeight = fontWeight,
        )

        if (safeLineFocus > 0.001f && (isComplete || isActive)) {
            Text(
                text = text,
                fontSize = effectiveFontSize,
                color = textColor.copy(alpha = revealAlpha),
                fontWeight = fontWeight,
                style =
                    LocalTextStyle.current.copy(
                        shadow =
                            if (glowAlpha > 0f) {
                                Shadow(
                                    color = textColor.copy(alpha = glowAlpha),
                                    offset = Offset.Zero,
                                    blurRadius = glowRadius.coerceAtLeast(1f),
                                )
                            } else {
                                null
                            },
                    ),
                modifier =
                    if (isActive && !isComplete) {
                        Modifier
                            .graphicsLayer {
                                compositingStrategy = CompositingStrategy.Offscreen
                            }
                            .drawWithContent {
                                drawContent()
                                val edgeWidth = 10.dp.toPx()
                                val center =
                                    if (isRtl) {
                                        size.width - ((size.width + edgeWidth * 2f) * progress - edgeWidth)
                                    } else {
                                        (size.width + edgeWidth * 2f) * progress - edgeWidth
                                    }
                                drawRect(
                                    brush =
                                        Brush.horizontalGradient(
                                            colors =
                                                if (isRtl) {
                                                    listOf(Color.Transparent, Color.Black)
                                                } else {
                                                    listOf(Color.Black, Color.Transparent)
                                                },
                                            startX = center - edgeWidth,
                                            endX = center + edgeWidth,
                                        ),
                                    blendMode = BlendMode.DstIn,
                                )
                            }
                    } else {
                        Modifier
                    },
            )
        }
    }
}


@RequiresApi(Build.VERSION_CODES.M)
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@SuppressLint("UnusedBoxWithConstraintsScope", "StringFormatInvalid")
@Composable
fun Lyrics(
    sliderPositionProvider: () -> Long?,
    isVisible: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val menuState = LocalMenuState.current
    val density = LocalDensity.current
    val context = LocalContext.current
    val resources = LocalResources.current
    val configuration = LocalConfiguration.current

    val isPlaying by playerConnection.isPlaying.collectAsState()

    DisposableEffect(isPlaying) {
        val window = (context as? android.app.Activity)?.window
        if (isPlaying) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    val landscapeOffset =
        configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    val lyricsTextPosition by rememberEnumPreference(LyricsTextPositionKey, LyricsPosition.LEFT)
    val lyricsAnimationStyle by rememberEnumPreference(LyricsAnimationStyleKey, LyricsAnimationStyle.APPLE)
    val lyricsTextSize by rememberPreference(LyricsTextSizeKey, 26f)
    val lyricsLineSpacing by rememberPreference(LyricsLineSpacingKey, 1.3f)
    val useSystemFont by rememberPreference(UseSystemFontKey, false)
    val lyricsFontFamily = remember(useSystemFont) {
        if (useSystemFont) null else FontFamily(Font(R.font.sfprodisplaybold))
    }

    val verticalLineSpacing = with(LocalDensity.current) {
        (lyricsTextSize.sp * (lyricsLineSpacing - 1f)).toDp().coerceAtLeast(0.dp)
    }
    val changeLyrics by rememberPreference(LyricsClickKey, true)
    val scrollLyrics by rememberPreference(LyricsScrollKey, true)
    val romanizeJapaneseLyrics by rememberPreference(LyricsRomanizeJapaneseKey, true)
    val romanizeKoreanLyrics by rememberPreference(LyricsRomanizeKoreanKey, true)
    val scope = rememberCoroutineScope()

    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()
    val lyricsEntity by playerConnection.currentLyrics.collectAsState(initial = null)
    val lyrics = remember(lyricsEntity) { lyricsEntity?.lyrics?.trim() }

    val playerBackground by rememberEnumPreference(
        key = PlayerBackgroundStyleKey,
        defaultValue = PlayerBackgroundStyle.CAPSULE_STAR
    )
    val playerDesign by rememberEnumPreference(
        key = CapsulePlayerDesignKey,
        defaultValue = CapsulePlayerDesign.SUPER,
    )
    val lyricsUsePlayerTheme by rememberPreference(
        LyricsUsePlayerThemeKey,
        defaultValue = false,
    )

    val darkTheme by rememberEnumPreference(DarkModeKey, defaultValue = DarkMode.AUTO)
    val isSystemInDarkTheme = isSystemInDarkTheme()
    val useDarkTheme = remember(darkTheme, isSystemInDarkTheme) {
        if (darkTheme == DarkMode.AUTO) isSystemInDarkTheme else darkTheme == DarkMode.ON
    }

    val lines = remember(lyrics, scope, mediaMetadata?.id, mediaMetadata?.duration) {
        if (lyrics == null || lyrics == LYRICS_NOT_FOUND) {
            emptyList()
        } else if (lyrics.startsWith("[")) {
            val parsedLines = parseLyrics(lyrics)
            parsedLines.map { entry ->
                val newEntry = LyricsEntry(entry.time, entry.text, entry.words)
                if (romanizeJapaneseLyrics) {
                    if (isJapanese(entry.text) && !isChinese(entry.text)) {
                        scope.launch {
                            try {
                                newEntry.romanizedTextFlow.value = romanizeJapanese(entry.text)
                            } catch (e: Exception) {
                                com.nikhil.yt.utils.reportException(e)
                            }
                        }
                    }
                }
                if (romanizeKoreanLyrics) {
                    if (isKorean(entry.text)) {
                        scope.launch {
                            try {
                                newEntry.romanizedTextFlow.value = romanizeKorean(entry.text)
                            } catch (e: Exception) {
                                com.nikhil.yt.utils.reportException(e)
                            }
                        }
                    }
                }
                newEntry
            }.let {
                listOf(LyricsEntry.HEAD_LYRICS_ENTRY) + it
            }
        } else if (isTtml(lyrics)) {
            val parsedLines = parseTtml(lyrics, mediaMetadata?.duration)
            parsedLines.map { entry ->
                val newEntry = LyricsEntry(entry.time, entry.text, entry.words)
                if (romanizeJapaneseLyrics) {
                    if (isJapanese(entry.text) && !isChinese(entry.text)) {
                        scope.launch {
                            try {
                                newEntry.romanizedTextFlow.value = romanizeJapanese(entry.text)
                            } catch (e: Exception) {
                                com.nikhil.yt.utils.reportException(e)
                            }
                        }
                    }
                }
                if (romanizeKoreanLyrics) {
                    if (isKorean(entry.text)) {
                        scope.launch {
                            try {
                                newEntry.romanizedTextFlow.value = romanizeKorean(entry.text)
                            } catch (e: Exception) {
                                com.nikhil.yt.utils.reportException(e)
                            }
                        }
                    }
                }
                newEntry
            }.let {
                listOf(LyricsEntry.HEAD_LYRICS_ENTRY) + it
            }
        } else {
            lyrics.lines().mapIndexed { index, line ->
                val newEntry = LyricsEntry(index * 100L, line)
                if (romanizeJapaneseLyrics) {
                    if (isJapanese(line) && !isChinese(line)) {
                        scope.launch {
                            try {
                                newEntry.romanizedTextFlow.value = romanizeJapanese(line)
                            } catch (e: Exception) {
                                com.nikhil.yt.utils.reportException(e)
                            }
                        }
                    }
                }
                if (romanizeKoreanLyrics) {
                    if (isKorean(line)) {
                        scope.launch {
                            try {
                                newEntry.romanizedTextFlow.value = romanizeKorean(line)
                            } catch (e: Exception) {
                                com.nikhil.yt.utils.reportException(e)
                            }
                        }
                    }
                }
                newEntry
            }
        }
    }
    val isSynced =
        remember(lyrics) {
            !lyrics.isNullOrEmpty() && (lyrics.startsWith("[") || isTtml(lyrics))
        }

    val lyricsUsesDarkSurface =
        lyricsUsePlayerTheme &&
            (
                playerDesign == CapsulePlayerDesign.IMMERSIVE ||
                    playerBackground != PlayerBackgroundStyle.DEFAULT
            )
    val lyricsBaseColor =
        if (useDarkTheme || lyricsUsesDarkSurface) Color.White else Color.Black
    val lyricsGlowColor = lyricsBaseColor
    val textColor = lyricsBaseColor

    /*
     * The user's own correction, on top of the built-in lead.
     *
     * A lyrics file that is uniformly early or late is the common defect and the only one a single
     * number can fix, so this is a shift rather than a rate: positive shows lines sooner, negative
     * later. Clamped on read as well as on write, because a value can also arrive from a restored
     * backup or an older build.
     */
    val syncOffsetMs by rememberPreference(LyricsSyncOffsetKey, defaultValue = 0)
    val userOffsetMs = clampOffset(syncOffsetMs).toLong()

    val wordSyncLeadMs = remember(lyrics, userOffsetMs) {
        (if (lyrics != null && isTtml(lyrics)) 0L else LyricsWordSyncLeadMs) + userOffsetMs
    }
    val lineSyncLeadMs = remember(lyrics, userOffsetMs) {
        (if (lyrics != null && isTtml(lyrics)) 0L else LyricsWordSyncLeadMs) + userOffsetMs
    }

    var currentLineIndex by remember {
        mutableIntStateOf(-1)
    }
    var deferredCurrentLineIndex by rememberSaveable {
        mutableIntStateOf(0)
    }

    var currentPlaybackPosition by remember {
        mutableLongStateOf(0L)
    }

    var previousLineIndex by rememberSaveable {
        mutableIntStateOf(0)
    }

    var lastPreviewTime by rememberSaveable {
        mutableLongStateOf(0L)
    }
    var isSeeking by remember {
        mutableStateOf(false)
    }

    var initialScrollDone by remember(mediaMetadata?.id) {
        mutableStateOf(false)
    }

    var shouldScrollToFirstLine by rememberSaveable {
        mutableStateOf(true)
    }

    var isAppMinimized by rememberSaveable {
        mutableStateOf(false)
    }

    var showProgressDialog by remember { mutableStateOf(false) }
    var showShareDialog by remember { mutableStateOf(false) }
    var shareDialogData by remember { mutableStateOf<Triple<String, String, String>?>(null) }

    var showColorPickerDialog by remember { mutableStateOf(false) }
    var selectedGlassStyle by remember { mutableStateOf(LyricsGlassStyle.FrostedDark) }
    var paletteGlassStyle by remember { mutableStateOf<LyricsGlassStyle?>(null) }

    var isSelectionModeActive by rememberSaveable { mutableStateOf(false) }
    val selectedIndices = remember { mutableStateListOf<Int>() }
    var showMaxSelectionToast by remember { mutableStateOf(false) }

    val lazyListState = rememberLazyListState()

    BackHandler(enabled = isSelectionModeActive) {
        isSelectionModeActive = false
        selectedIndices.clear()
    }

    val maxSelectionLimit = 5

    LaunchedEffect(showMaxSelectionToast) {
        if (showMaxSelectionToast) {
            Toast.makeText(
                context,
                resources.getString(R.string.max_selection_limit, maxSelectionLimit),
                Toast.LENGTH_SHORT
            ).show()
            showMaxSelectionToast = false
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                // Never preserve a half-finished list offset across background/foreground. The old
                // Velune logic only reset when the current line happened to be visible, which is
                // exactly how an accidental off-centre anchor survived a minimize cycle.
                initialScrollDone = false
                isAppMinimized = true
            } else if (event == Lifecycle.Event.ON_START) {
                isAppMinimized = false
                initialScrollDone = false
                lastPreviewTime = 0L
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(lines) {
        isSelectionModeActive = false
        selectedIndices.clear()
    }

    var isManualScrolling by rememberSaveable {
        mutableStateOf(false)
    }
    var isReturningToSync by remember {
        mutableStateOf(false)
    }

    val manualScrollThresholdPx = with(density) { 28.dp.toPx() }

    /*
     * Every path that gives focus back to the synced line goes through the same physical anchor.
     * Previously visible items were centred, off-screen items were aligned to the list start, and
     * the "resume auto-scroll" button used yet another path. That let the anchor drift all the way
     * under the bottom controls and made the bad position persistent.
     */
    suspend fun anchorLyricLine(
        targetIndex: Int,
        animated: Boolean,
        seek: Boolean = false,
        returnToSync: Boolean = false,
    ) {
        if (!isVisible || targetIndex !in lines.indices) return

        try {
            /*
             * Explicit return-to-sync is intentionally different from ordinary lyric tracking.
             *
             * The old implementation prepared an off-screen target with scrollToItem(), which
             * meant the list teleported first and only the final centring looked animated. Here
             * every pixel from the user's current manual-scroll position to the live lyric is
             * produced by animateScrollBy(). No staging jump exists.
             *
             * Phase 1 is a fast, soft approach using the measured average row stride. It stops
             * close to the destination rather than exactly on it. Phase 2 measures the real live
             * row and performs a slower final settle into the centre anchor. currentLineIndex is a
             * LaunchedEffect key outside this function, so a line change cancels either phase and
             * immediately retargets from whatever position is on screen at that exact moment.
             */
            if (animated && returnToSync && !seek) {
                repeat(3) {
                    val layout = lazyListState.layoutInfo
                    val visible = layout.visibleItemsInfo
                    if (visible.isEmpty()) return@repeat

                    val targetInfo =
                        visible.firstOrNull { it.index == targetIndex }
                    if (targetInfo != null) return@repeat

                    val viewportHeight =
                        layout.viewportEndOffset - layout.viewportStartOffset
                    if (viewportHeight <= 0) return@repeat
                    val anchorY = layout.viewportStartOffset + viewportHeight / 2

                    val centreItem =
                        visible.minByOrNull {
                            abs((it.offset + it.size / 2) - anchorY)
                        } ?: return@repeat

                    val measuredStrides =
                        visible
                            .zipWithNext()
                            .mapNotNull { (first, second) ->
                                val indexGap = second.index - first.index
                                if (indexGap <= 0) {
                                    null
                                } else {
                                    (second.offset - first.offset).toFloat() / indexGap
                                }
                            }
                            .filter { abs(it) > 1f }

                    val averageStride =
                        if (measuredStrides.isNotEmpty()) {
                            measuredStrides.average().toFloat()
                        } else {
                            visible.map { it.size }.average().toFloat()
                        }.coerceAtLeast(1f)

                    val indexDistance = targetIndex - centreItem.index
                    val centreError =
                        (centreItem.offset + centreItem.size / 2) - anchorY
                    val estimatedDistance =
                        centreError + indexDistance * averageStride

                    if (abs(estimatedDistance) <= 2f) return@repeat

                    val distanceInRows = abs(indexDistance)
                    val approachFraction =
                        when {
                            distanceInRows >= 10 -> 0.94f
                            distanceInRows >= 5 -> 0.90f
                            else -> 0.82f
                        }
                    val approachDurationMs =
                        (300 + distanceInRows * 18)
                            .coerceIn(320, 680)

                    lazyListState.animateScrollBy(
                        value = estimatedDistance * approachFraction,
                        animationSpec =
                            tween(
                                durationMillis = approachDurationMs,
                                easing = ReturnToSyncApproachEasing,
                            ),
                    )
                    withFrameNanos { }
                }

                val settledLayout = lazyListState.layoutInfo
                val settledTarget =
                    settledLayout.visibleItemsInfo
                        .firstOrNull { it.index == targetIndex }
                        ?: return
                val viewportHeight =
                    settledLayout.viewportEndOffset - settledLayout.viewportStartOffset
                if (viewportHeight <= 0) return

                val anchorY =
                    settledLayout.viewportStartOffset + viewportHeight / 2
                val targetCenter =
                    settledTarget.offset + settledTarget.size / 2
                val finalOffset = targetCenter - anchorY
                if (abs(finalOffset) <= 2) return

                val settleDurationMs =
                    with(density) {
                        val travelDp = abs(finalOffset).toDp().value
                        (300f + travelDp * 0.48f)
                            .toInt()
                            .coerceIn(320, 560)
                    }

                lazyListState.animateScrollBy(
                    value = finalOffset.toFloat(),
                    animationSpec =
                        tween(
                            durationMillis = settleDurationMs,
                            easing = ReturnToSyncSettleEasing,
                        ),
                )
                return
            }

            var itemInfo =
                lazyListState.layoutInfo.visibleItemsInfo
                    .firstOrNull { it.index == targetIndex }

            if (itemInfo == null) {
                // Ordinary tracking/restoration keeps its established behaviour. Only the explicit
                // return mode above avoids direct positioning so we do not destabilise normal sync.
                lazyListState.scrollToItem(targetIndex)
                withFrameNanos { }
                itemInfo =
                    lazyListState.layoutInfo.visibleItemsInfo
                        .firstOrNull { it.index == targetIndex }
            }

            val measuredItem = itemInfo ?: return
            val layout = lazyListState.layoutInfo
            val viewportHeight = layout.viewportEndOffset - layout.viewportStartOffset
            if (viewportHeight <= 0) return

            val anchorY = layout.viewportStartOffset + viewportHeight / 2
            val itemCenter = measuredItem.offset + measuredItem.size / 2
            val offset = itemCenter - anchorY
            if (abs(offset) <= 2) return

            if (!animated) {
                lazyListState.scrollBy(offset.toFloat())
            } else if (seek) {
                lazyListState.animateScrollBy(
                    value = offset.toFloat(),
                    animationSpec =
                        spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMedium,
                        ),
                )
            } else {
                val durationMs =
                    with(density) {
                        val travelDp = abs(offset).toDp().value
                        (460f + travelDp * 1.35f)
                            .toInt()
                            .coerceIn(520, 1_300)
                    }
                lazyListState.animateScrollBy(
                    value = offset.toFloat(),
                    animationSpec =
                        tween(
                            durationMillis = durationMs,
                            easing = AppleMusicEasing,
                        ),
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A new line or a user gesture may invalidate the current layout between frames.
            // The next tracking pass will re-anchor from live playback position.
        }
    }

    LaunchedEffect(
        isVisible,
        isAppMinimized,
        lines,
        mediaMetadata?.id,
        lineSyncLeadMs,
    ) {
        // The lyrics surface remains mounted while its close animation runs. Stop owning the list
        // the instant it becomes hidden, otherwise an in-flight auto-scroll can be cancelled at an
        // arbitrary pixel and that stale position survives a quick reopen.
        if (!isVisible || isAppMinimized) {
            isReturningToSync = false
            isManualScrolling = false
            isSeeking = false
            lastPreviewTime = 0L
            initialScrollDone = false
            return@LaunchedEffect
        }

        if (!isSynced || lines.isEmpty()) return@LaunchedEffect
        val targetIndex =
            findCurrentLineIndex(
                lines,
                playerConnection.player.currentPosition,
                leadMs = lineSyncLeadMs,
            )
        if (targetIndex >= 0) {
            isManualScrolling = false
            currentLineIndex = targetIndex
            deferredCurrentLineIndex = targetIndex
            previousLineIndex = targetIndex
            // Reopening always performs one authoritative center pass from the live playback
            // position instead of trusting whatever offset the previous transition left behind.
            initialScrollDone = false
            lastPreviewTime = 0L
        }
    }

    LaunchedEffect(
        lyrics,
        lines,
        isAppMinimized,
        wordSyncLeadMs,
        lineSyncLeadMs,
        lyricsAnimationStyle,
        isManualScrolling,
        isReturningToSync,
        isPlaying,
        isVisible,
    ) {
        if (!isVisible || isAppMinimized) return@LaunchedEffect
        if (lyrics.isNullOrEmpty() || (!lyrics.startsWith("[") && !isTtml(lyrics))) {
            currentLineIndex = -1
            currentPlaybackPosition = 0L
            return@LaunchedEffect
        }

        /*
         * Update first, sleep second. Velune did the opposite, so reopening lyrics or returning
         * from the background could show the stale line until the next polling/animation beat.
         *
         * Word motion gets 20 Hz; line-only tracking gets ~12.5 Hz. Those rates are well above the
         * visual bandwidth of these slow lyric effects but substantially reduce wakeups versus the
         * old permanent 25 Hz loop.
         */
        while (isActive) {
            val sliderPosition = sliderPositionProvider()
            val seekingNow = sliderPosition != null
            if (isSeeking != seekingNow) {
                isSeeking = seekingNow
            }

            val position = sliderPosition ?: playerConnection.player.currentPosition
            val newLineIndex =
                findCurrentLineIndex(
                    lines,
                    position,
                    leadMs = lineSyncLeadMs,
                )

            if (currentLineIndex != newLineIndex) {
                currentLineIndex = newLineIndex
            }

            val syncedPosition = (position + wordSyncLeadMs).coerceAtLeast(0L)
            if (currentPlaybackPosition != syncedPosition) {
                currentPlaybackPosition = syncedPosition
            }

            val activeLineHasWords =
                lines.getOrNull(newLineIndex)?.words?.isNotEmpty() == true
            val needsFineProgress =
                lyricsAnimationStyle == LyricsAnimationStyle.KARAOKE ||
                    (
                        lyricsAnimationStyle != LyricsAnimationStyle.NONE &&
                            activeLineHasWords
                    )

            val delayMs =
                when {
                    // While returning, the destination is live: detect a line hand-off quickly so
                    // the old scroll is cancelled and the new current line immediately takes over.
                    isReturningToSync -> 50L
                    // Nothing visual is advancing while paused.
                    !isPlaying && sliderPosition == null -> 420L
                    // Manual scrolling intentionally suppresses lyric motion, so a slower clock is
                    // enough to keep the current-line bookkeeping fresh.
                    isManualScrolling -> 180L
                    // Word-synced motion keeps the only fast clock. 25 Hz is enough to remove the
                    // small stepping visible at 20 Hz, while all non-active lines remain detached
                    // from currentPlaybackPosition and therefore do not pay for these ticks.
                    needsFineProgress -> 40L
                    // Line-only sync does not need a 12.5 Hz poll; 10 Hz still lands well ahead of
                    // the 500ms+ visual hand-off and saves idle CPU time.
                    else -> 100L
                }
            delay(delayMs)
        }
    }

    LaunchedEffect(isSeeking) {
        if (isSeeking) {
            lastPreviewTime = 0L
        }
    }

    LaunchedEffect(
        currentLineIndex,
        lastPreviewTime,
        initialScrollDone,
        isReturningToSync,
        isVisible,
        isAppMinimized,
    ) {
        if (!isVisible || isAppMinimized || !isSynced) return@LaunchedEffect
        if (currentLineIndex !in lines.indices) return@LaunchedEffect

        if (isReturningToSync) {
            deferredCurrentLineIndex = currentLineIndex
            return@LaunchedEffect
        }

        if (!initialScrollDone) {
            shouldScrollToFirstLine = false
            // Opening/foregrounding is state restoration, not choreography: land on the live line
            // immediately so the user never waits for the next lyric transition to regain focus.
            anchorLyricLine(
                targetIndex = currentLineIndex,
                animated = false,
            )
            initialScrollDone = true
        } else {
            deferredCurrentLineIndex = currentLineIndex

            when {
                isSeeking -> {
                    anchorLyricLine(
                        targetIndex = currentLineIndex,
                        animated = true,
                        seek = true,
                    )
                }

                scrollLyrics &&
                    !isManualScrolling &&
                    (
                        lastPreviewTime == 0L ||
                            currentLineIndex != previousLineIndex
                    ) &&
                    currentLineIndex != previousLineIndex -> {
                    anchorLyricLine(
                        targetIndex = currentLineIndex,
                        animated = true,
                    )
                }
            }
        }

        if (currentLineIndex > 0) {
            shouldScrollToFirstLine = true
        }
        previousLineIndex = currentLineIndex
    }

    /*
     * One coroutine owns "return to sync". currentLineIndex is a key on purpose: if playback
     * advances while the list is flying, Compose cancels the old scroll (animateScrollBy is
     * cancellable) and starts again toward the newest live line. No stale target can win after it.
     */
    LaunchedEffect(
        isReturningToSync,
        currentLineIndex,
        isVisible,
        isAppMinimized,
    ) {
        if (!isReturningToSync || !isVisible || isAppMinimized || !isSynced) {
            return@LaunchedEffect
        }

        val targetIndex = currentLineIndex
        if (targetIndex !in lines.indices) {
            isReturningToSync = false
            isManualScrolling = false
            return@LaunchedEffect
        }

        deferredCurrentLineIndex = targetIndex

        /*
         * Usually one pass is enough. Extra passes only compensate for rows remeasuring while the
         * scroll is moving; every pass still starts from the list's current physical position and
         * uses animateScrollBy, so there is no hidden jump between them.
         */
        repeat(4) {
            anchorLyricLine(
                targetIndex = targetIndex,
                animated = true,
                returnToSync = true,
            )

            // A line hand-off restarts this effect with the new target.
            if (currentLineIndex != targetIndex) {
                return@LaunchedEffect
            }

            val info =
                lazyListState.layoutInfo.visibleItemsInfo
                    .firstOrNull { it.index == targetIndex }
            val layout = lazyListState.layoutInfo
            val viewportHeight = layout.viewportEndOffset - layout.viewportStartOffset
            val centered =
                if (info != null && viewportHeight > 0) {
                    val anchorY =
                        layout.viewportStartOffset + viewportHeight / 2
                    val itemCenter = info.offset + info.size / 2
                    abs(itemCenter - anchorY) <= 4
                } else {
                    false
                }

            if (centered) {
                deferredCurrentLineIndex = targetIndex
                previousLineIndex = targetIndex
                lastPreviewTime = 0L
                isManualScrolling = false
                isReturningToSync = false
                return@LaunchedEffect
            }

            withFrameNanos { }
        }

        /*
         * Pathological layout fallback: release ownership but force the normal tracker to perform
         * one authoritative correction on the next effect pass. This is only reachable after four
         * fully animated attempts and prevents the UI from ever remaining input-locked.
         */
        if (currentLineIndex == targetIndex) {
            previousLineIndex = -1
            lastPreviewTime = 0L
            isManualScrolling = false
            isReturningToSync = false
        }
    }

    BoxWithConstraints(
        contentAlignment = Alignment.TopCenter,
        modifier = modifier
            .fillMaxSize()
            .padding(bottom = 12.dp)
    ) {

        if (lyrics == LYRICS_NOT_FOUND) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.lyrics_not_found),
                    fontSize = 20.sp,
                    color = MaterialTheme.colorScheme.secondary,
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.alpha(0.5f)
                )
            }
        } else {
            LazyColumn(
            state = lazyListState,
            userScrollEnabled = !isReturningToSync,
            contentPadding = WindowInsets.systemBarsIgnoringVisibility
                .only(WindowInsetsSides.Top)
                .add(WindowInsets(top = maxHeight / 2, bottom = maxHeight / 2))
                .asPaddingValues(),
            modifier = Modifier
                .smoothFadingEdge(top = 128.dp, bottom = 104.dp)
                .nestedScroll(
                    remember(
                        manualScrollThresholdPx,
                        scrollLyrics,
                        isReturningToSync,
                        mediaMetadata?.id,
                    ) {
                        var accumulatedDragPx = 0f
                        var enteredManualMode = false
                        var lastScrollTime = 0L

                        object : NestedScrollConnection {
                            override fun onPostScroll(
                                consumed: Offset,
                                available: Offset,
                                source: NestedScrollSource,
                            ): Offset {
                                if (
                                    !isSelectionModeActive &&
                                    !isReturningToSync &&
                                    source == NestedScrollSource.UserInput
                                ) {
                                    accumulatedDragPx += abs(consumed.y)
                                    val currentTime = System.currentTimeMillis()

                                    // A tiny touch used to detach tracking immediately. Require a
                                    // deliberate drag before entering free-scroll mode.
                                    if (
                                        !enteredManualMode &&
                                        accumulatedDragPx >= manualScrollThresholdPx
                                    ) {
                                        enteredManualMode = true
                                        isManualScrolling = true
                                        lastPreviewTime = currentTime
                                        lastScrollTime = currentTime
                                    } else if (
                                        enteredManualMode &&
                                        currentTime - lastScrollTime > 80L
                                    ) {
                                        lastPreviewTime = currentTime
                                        lastScrollTime = currentTime
                                    }
                                }
                                return super.onPostScroll(consumed, available, source)
                            }

                            override suspend fun onPostFling(
                                consumed: Velocity,
                                available: Velocity,
                            ): Velocity {
                                if (!isSelectionModeActive && !isReturningToSync) {
                                    if (enteredManualMode) {
                                        lastPreviewTime = System.currentTimeMillis()
                                        isManualScrolling = true
                                    } else if (
                                        scrollLyrics &&
                                        currentLineIndex in lines.indices
                                    ) {
                                        // Small accidental drags are elastic: return the live line
                                        // to the one canonical anchor instead of preserving drift.
                                        scope.launch {
                                            anchorLyricLine(
                                                targetIndex = currentLineIndex,
                                                animated = true,
                                            )
                                        }
                                    }
                                }

                                accumulatedDragPx = 0f
                                enteredManualMode = false
                                return super.onPostFling(consumed, available)
                            }
                        }
                    },
                )
        ) {
            val displayedCurrentLineIndex =
                if (isSeeking || isSelectionModeActive) deferredCurrentLineIndex else currentLineIndex

            if (lyrics == null) {
                item {
                    ShimmerHost {
                        repeat(10) {
                            Box(
                                contentAlignment = when (lyricsTextPosition) {
                                    LyricsPosition.LEFT -> Alignment.CenterStart
                                    LyricsPosition.CENTER -> Alignment.Center
                                    LyricsPosition.RIGHT -> Alignment.CenterEnd
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp, vertical = 4.dp)
                            ) {
                                TextPlaceholder()
                            }
                        }
                    }
                }
            } else {
                itemsIndexed(
                    items = lines,
                    key = { index, item -> "${index}_${item.time}_${item.text.hashCode()}" }, // Stable keys for better recycling
                    contentType = { _, _ -> "lyric_line" } // Enables better item recycling
                ) { index, item ->
                    val isSelected = selectedIndices.contains(index)

                    val distance = abs(index - displayedCurrentLineIndex)

                    val archiveTuneStyle =
                        lyricsAnimationStyle == LyricsAnimationStyle.ARCHIVE_TUNE
                    val targetAlpha = when {
                        !isSynced || (isSelectionModeActive && isSelected) -> 1f
                        isReturningToSync && index == currentLineIndex -> 1f
                        isReturningToSync -> 0.38f
                        isManualScrolling && archiveTuneStyle -> when {
                            index == displayedCurrentLineIndex -> 1f
                            distance == 1 -> 0.72f
                            distance == 2 -> 0.56f
                            distance == 3 -> 0.40f
                            else -> 0.28f
                        }
                        isManualScrolling -> when {
                            index == displayedCurrentLineIndex -> 1f
                            distance == 1 -> 0.85f
                            distance == 2 -> 0.70f
                            distance == 3 -> 0.55f
                            else -> 0.45f
                        }
                        archiveTuneStyle && index == displayedCurrentLineIndex -> 1f

                        // Do not let already-passed lines turn into empty layout space while they
                        // are still physically crossing the viewport. Two tall outgoing rows can
                        // occupy a large amount of height; if both are dimmed by logical index
                        // distance first, the eye reads the resulting blank band as a frame hitch.
                        // Keep the trailing side alive and let the actual top fading edge perform
                        // the final disappearance.
                        archiveTuneStyle &&
                            index < displayedCurrentLineIndex &&
                            distance == 1 -> 0.70f
                        archiveTuneStyle &&
                            index < displayedCurrentLineIndex &&
                            distance == 2 -> 0.50f
                        archiveTuneStyle &&
                            index < displayedCurrentLineIndex &&
                            distance == 3 -> 0.30f
                        archiveTuneStyle &&
                            index < displayedCurrentLineIndex -> 0.12f

                        // Incoming lines remain quieter so focus still clearly travels forward.
                        archiveTuneStyle && distance == 1 -> 0.46f
                        archiveTuneStyle && distance == 2 -> 0.22f
                        archiveTuneStyle && distance == 3 -> 0.08f
                        archiveTuneStyle -> 0.02f

                        // Every animated style now keeps the outgoing side physically present until
                        // the list itself carries it into the edge fade. Previously Fade/Glow/Slide
                        // dimmed two tall rows by logical index first, which looked like a light
                        // snap and could expose a large blank band during scroll.
                        lyricsAnimationStyle != LyricsAnimationStyle.NONE &&
                            index == displayedCurrentLineIndex -> 1f
                        lyricsAnimationStyle != LyricsAnimationStyle.NONE &&
                            index < displayedCurrentLineIndex &&
                            distance == 1 -> 0.68f
                        lyricsAnimationStyle != LyricsAnimationStyle.NONE &&
                            index < displayedCurrentLineIndex &&
                            distance == 2 -> 0.48f
                        lyricsAnimationStyle != LyricsAnimationStyle.NONE &&
                            index < displayedCurrentLineIndex &&
                            distance == 3 -> 0.28f
                        lyricsAnimationStyle != LyricsAnimationStyle.NONE &&
                            index < displayedCurrentLineIndex -> 0.10f
                        lyricsAnimationStyle != LyricsAnimationStyle.NONE &&
                            distance == 1 -> 0.55f
                        lyricsAnimationStyle != LyricsAnimationStyle.NONE &&
                            distance == 2 -> 0.28f
                        lyricsAnimationStyle != LyricsAnimationStyle.NONE &&
                            distance == 3 -> 0.10f
                        lyricsAnimationStyle != LyricsAnimationStyle.NONE -> 0.03f

                        index == displayedCurrentLineIndex -> 1f
                        distance == 1 -> 0.58f
                        distance == 2 -> 0.30f
                        distance == 3 -> 0.12f
                        else -> 0.04f
                    }

                    val archiveLineIsFocused =
                        archiveTuneStyle &&
                            isSynced &&
                            index == displayedCurrentLineIndex
                    val animatedLineIsFocused =
                        !archiveTuneStyle &&
                            lyricsAnimationStyle != LyricsAnimationStyle.NONE &&
                            isSynced &&
                            index == displayedCurrentLineIndex

                    val animatedAlphaState =
                        animateFloatAsState(
                            targetValue = targetAlpha,
                            animationSpec =
                                tween(
                                    durationMillis =
                                        when {
                                            isReturningToSync -> 320
                                            archiveTuneStyle ->
                                                if (archiveLineIsFocused) 500 else 1_100
                                            lyricsAnimationStyle != LyricsAnimationStyle.NONE ->
                                                if (animatedLineIsFocused) 500 else 1_050
                                            else ->
                                                520
                                        },
                                    easing =
                                        if (isReturningToSync) {
                                            ReturnToSyncSettleEasing
                                        } else if (lyricsAnimationStyle != LyricsAnimationStyle.NONE) {
                                            AppleMusicEasing
                                        } else {
                                            SmoothDecelerateEasing
                                        },
                                ),
                            label = "lyricAlpha",
                        )

                    /*
                     * A tiny scale lift makes the live row feel magnetically collected by the
                     * centre anchor. Only one row changes scale, and the value is consumed directly
                     * by graphicsLayer below so it does not recompose the lyric subtree every frame.
                     */
                    val returnFocusScaleState =
                        animateFloatAsState(
                            targetValue =
                                if (isReturningToSync && index == currentLineIndex) {
                                    1.012f
                                } else {
                                    1f
                                },
                            animationSpec =
                                tween(
                                    durationMillis = 380,
                                    easing = ReturnToSyncSettleEasing,
                                ),
                            label = "lyricReturnFocusScale",
                        )

                    // The light hand-off is intentionally asymmetric: the new line lights up
                    // quickly, while the old one leaves a long soft tail.
                    val archiveLineFocus by animateFloatAsState(
                        targetValue = if (archiveLineIsFocused) 1f else 0f,
                        animationSpec =
                            tween(
                                durationMillis = if (archiveLineIsFocused) 500 else 1_100,
                                easing = AppleMusicEasing,
                            ),
                        label = "archiveLineFocus",
                    )

                    val itemModifier = Modifier
                        .fillMaxWidth()
                        // Removed .clip() to prevent glow clipping
                        .combinedClickable(
                            enabled = !isReturningToSync,
                            onClick = {
                                if (isSelectionModeActive) {
                                    if (isSelected) {
                                        selectedIndices.remove(index)
                                        if (selectedIndices.isEmpty()) {
                                            isSelectionModeActive = false
                                        }
                                    } else {
                                        if (selectedIndices.size < maxSelectionLimit) {
                                            selectedIndices.add(index)
                                        } else {
                                            showMaxSelectionToast = true
                                        }
                                    }
                                } else if (isSynced && changeLyrics) {
                                    isManualScrolling = false
                                    lastPreviewTime = 0L
                                    currentLineIndex = index
                                    deferredCurrentLineIndex = index
                                    previousLineIndex = index
                                    initialScrollDone = true
                                    playerConnection.player.seekTo(item.time)
                                    scope.launch {
                                        anchorLyricLine(
                                            targetIndex = index,
                                            animated = true,
                                            seek = true,
                                        )
                                        isManualScrolling = false
                                    }
                                    lastPreviewTime = 0L
                                }
                            },
                            onLongClick = {
                                if (!isSelectionModeActive) {
                                    isSelectionModeActive = true
                                    selectedIndices.add(index)
                                } else if (!isSelected && selectedIndices.size < maxSelectionLimit) {
                                    selectedIndices.add(index)
                                } else if (!isSelected) {
                                    showMaxSelectionToast = true
                                }
                            }
                        )
                        .background(
                            color = if (isSelected && isSelectionModeActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                            else Color.Transparent,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .padding(
                            horizontal = 24.dp,
                            vertical = 8.dp
                        )
                        /*
                         * One layer for both opacity and the tiny return-focus scale. Reading the
                         * animation State inside graphicsLayer keeps those frame-by-frame updates in
                         * the layer invalidation path instead of recomposing the entire text row.
                         */
                        .graphicsLayer {
                            alpha = animatedAlphaState.value
                            val returnScale = returnFocusScaleState.value
                            scaleX = returnScale
                            scaleY = returnScale
                            transformOrigin = TransformOrigin.Center
                        }

                    val baseLayoutDirection = LocalLayoutDirection.current
                    val lineIsRtl = remember(item.text) { isRtlText(item.text) }
                    val lineLayoutDirection = remember(lineIsRtl, baseLayoutDirection) {
                        if (lineIsRtl) LayoutDirection.Rtl else baseLayoutDirection
                    }

                    CompositionLocalProvider(LocalLayoutDirection provides lineLayoutDirection) {
                        CompositionLocalProvider(
                            LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = lyricsFontFamily)
                        ) {
                            Column(
                                modifier = itemModifier,
                                horizontalAlignment = when (lyricsTextPosition) {
                                    LyricsPosition.LEFT -> Alignment.Start
                                    LyricsPosition.CENTER -> Alignment.CenterHorizontally
                                    LyricsPosition.RIGHT -> Alignment.End
                                }
                            ) {
                        val isActiveLine = index == displayedCurrentLineIndex && isSynced
                        // Distance/focus dimming lives on the row's single animated alpha layer.
                        // Keeping a second alpha in the text color caused a brief two-phase flash
                        // whenever a line changed from upcoming -> active -> passed.
                        val lineColor = lyricsBaseColor
                        val alignment = remember(lyricsTextPosition) {
                            when (lyricsTextPosition) {
                                LyricsPosition.LEFT -> TextAlign.Start
                                LyricsPosition.CENTER -> TextAlign.Center
                                LyricsPosition.RIGHT -> TextAlign.End
                            }
                        }

                        val hasWordTimings = remember(item.words) { item.words?.isNotEmpty() == true }
                        val romanizedText: String? =
                            if (romanizeJapaneseLyrics || romanizeKoreanLyrics) {
                                val value by item.romanizedTextFlow.collectAsState()
                                value
                            } else {
                                null
                            }
                        val hasRomanization = remember(romanizedText) { romanizedText != null }

                        val effectiveAnimationStyle = lyricsAnimationStyle

                        val reduceMotionDuringScroll =
                            isSelectionModeActive || isManualScrolling

                        /*
                         * Apple-style emphasis used to measure the glyphs at 96% and then enlarge
                         * that raster layer past 100% (1 / .96). On some Android renderers the
                         * offscreen Text layer keeps its original tight glyph bounds, so stems and
                         * bowls on letters such as Cyrillic п/р/б were shaved by a pixel while the
                         * layer grew. Render at the full font size instead and animate only from
                         * 96% -> 100%. The visual sizes are identical, but the layer never expands
                         * beyond the bitmap it was measured for.
                         */
                        val appleLayoutFontSize = lyricsTextSize.sp
                        val appleScaleTarget =
                            if (
                                effectiveAnimationStyle == LyricsAnimationStyle.APPLE &&
                                isActiveLine &&
                                !reduceMotionDuringScroll
                            ) {
                                1f
                            } else if (effectiveAnimationStyle == LyricsAnimationStyle.APPLE) {
                                0.96f
                            } else {
                                1f
                            }
                        val appleVisualScale by animateFloatAsState(
                            targetValue = appleScaleTarget,
                            animationSpec = tween(
                                durationMillis = 320,
                                easing = AppleMusicEasing,
                            ),
                            label = "appleLyricScale",
                        )

                        if (effectiveAnimationStyle == LyricsAnimationStyle.KARAOKE) {
                            val isCjk = remember(item.text) {
                                isChinese(item.text) || isJapanese(item.text) || isKorean(item.text)
                            }

                            val wordsToRender = remember(item.words, item.text, item.time, lines.size, index, lineIsRtl, isCjk) {
                                if (hasWordTimings && item.words != null) {
                                    val baseWords = item.words.filter { it.text.isNotBlank() }
                                    baseWords.flatMapIndexed { idx, word ->
                                        val prevText = baseWords.getOrNull(idx - 1)?.text
                                        val nextText = baseWords.getOrNull(idx + 1)?.text
                                        val includeSpace =
                                            if (isCjk) {
                                                // Add space at CJK↔Latin boundaries
                                                val currEdge = if (lineIsRtl) word.text.firstOrNull() else word.text.lastOrNull()
                                                val neighborEdge = if (lineIsRtl) prevText?.lastOrNull() else nextText?.firstOrNull()
                                                val neighbor = if (lineIsRtl) prevText else nextText
                                                currEdge != null && neighborEdge != null && neighbor != null &&
                                                    (currEdge.code < 0x3000 || neighborEdge.code < 0x3000) &&
                                                    shouldAppendWordSpace(
                                                        if (lineIsRtl) neighbor else word.text,
                                                        if (lineIsRtl) word.text else neighbor
                                                    )
                                            } else if (lineIsRtl) {
                                                prevText != null && shouldAppendWordSpace(prevText, word.text)
                                            } else {
                                                nextText != null && shouldAppendWordSpace(word.text, nextText)
                                            }

                                        val wordStartMs = (word.startTime * 1000).toLong()
                                        val wordEndMs = (word.endTime * 1000).toLong()
                                        val wordDuration = wordEndMs - wordStartMs

                                        if (isCjk && word.text.length > 3) {
                                            // Split long CJK phrases into individual characters for FlowRow wrapping
                                            // Short entries (1-3 chars) are kept intact — TTML already provides granular timing
                                            val chars = word.text.toList()
                                            chars.mapIndexed { charIdx, char ->
                                                val charStartMs = wordStartMs + (wordDuration * charIdx / chars.size)
                                                val charEndMs = wordStartMs + (wordDuration * (charIdx + 1) / chars.size)
                                                // Add space only at the last/first character boundary with next/prev word
                                                val charText = when {
                                                    includeSpace && !lineIsRtl && charIdx == chars.lastIndex -> "${char} "
                                                    includeSpace && lineIsRtl && charIdx == 0 -> " ${char}"
                                                    else -> char.toString()
                                                }
                                                Triple(charText, charStartMs to charEndMs, word.isBackground)
                                            }
                                        } else {
                                            val displayText = when {
                                                !includeSpace -> word.text
                                                lineIsRtl -> " ${word.text}"
                                                else -> "${word.text} "
                                            }
                                            listOf(Triple(
                                                displayText,
                                                wordStartMs to wordEndMs,
                                                word.isBackground,
                                            ))
                                        }
                                    }
                                } else {
                                    // Simulate word timings - cache this computation
                                    val nextLineTime = lines.getOrNull(index + 1)?.time ?: (item.time + 5000L).coerceAtLeast(item.time + 1000L)
                                    val lineDuration = (nextLineTime - item.time).coerceAtLeast(100L)
                                    
                                    val splitWords = if (isCjk) {
                                        item.text.map { it.toString() }
                                    } else {
                                        item.text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
                                    }
                                    val lengths = splitWords.mapIndexed { idx, wordText ->
                                        val prevText = splitWords.getOrNull(idx - 1)
                                        val nextText = splitWords.getOrNull(idx + 1)
                                        val includeSpace =
                                            if (isCjk) {
                                                val currEdge = if (lineIsRtl) wordText.firstOrNull() else wordText.lastOrNull()
                                                val neighborEdge = if (lineIsRtl) prevText?.lastOrNull() else nextText?.firstOrNull()
                                                val neighbor = if (lineIsRtl) prevText else nextText
                                                currEdge != null && neighborEdge != null && neighbor != null &&
                                                    (currEdge.code < 0x3000 || neighborEdge.code < 0x3000) &&
                                                    shouldAppendWordSpace(
                                                        if (lineIsRtl) neighbor else wordText,
                                                        if (lineIsRtl) wordText else neighbor
                                                    )
                                            } else if (lineIsRtl) {
                                                prevText != null && shouldAppendWordSpace(prevText, wordText)
                                            } else {
                                                nextText != null && shouldAppendWordSpace(wordText, nextText)
                                            }
                                        wordText.length + if (includeSpace) 1 else 0
                                    }
                                    val totalLength = lengths.sum().coerceAtLeast(1)
                                    
                                    var currentOffset = 0L
                                    splitWords.mapIndexed { idx, wordText ->
                                        val wordDuration = (lineDuration * (lengths[idx].toDouble() / totalLength)).toLong()
                                        
                                        val startTime = item.time + currentOffset
                                        val endTime = startTime + wordDuration
                                        currentOffset += wordDuration
                                        
                                        val prevText = splitWords.getOrNull(idx - 1)
                                        val nextText = splitWords.getOrNull(idx + 1)
                                        val includeSpace =
                                            if (isCjk) {
                                                val currEdge = if (lineIsRtl) wordText.firstOrNull() else wordText.lastOrNull()
                                                val neighborEdge = if (lineIsRtl) prevText?.lastOrNull() else nextText?.firstOrNull()
                                                val neighbor = if (lineIsRtl) prevText else nextText
                                                currEdge != null && neighborEdge != null && neighbor != null &&
                                                    (currEdge.code < 0x3000 || neighborEdge.code < 0x3000) &&
                                                    shouldAppendWordSpace(
                                                        if (lineIsRtl) neighbor else wordText,
                                                        if (lineIsRtl) wordText else neighbor
                                                    )
                                            } else if (lineIsRtl) {
                                                prevText != null && shouldAppendWordSpace(prevText, wordText)
                                            } else {
                                                nextText != null && shouldAppendWordSpace(wordText, nextText)
                                            }
                                        val displayText =
                                            when {
                                                !includeSpace -> wordText
                                                lineIsRtl -> " $wordText"
                                                else -> "$wordText "
                                            }
                                        
                                        Triple(displayText, startTime to endTime, false)
                                    }
                                }
                            }

                            val horizontalSpacing = 0.dp // Reduced to 0dp, relying on padding and text spaces
                            val karaokeCurrentTimeProvider: () -> Long = {
                                if (isActiveLine && !reduceMotionDuringScroll) currentPlaybackPosition else Long.MIN_VALUE
                            }

                            FlowRow(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp), 
                                horizontalArrangement = when (lyricsTextPosition) {
                                    LyricsPosition.LEFT -> Arrangement.spacedBy(horizontalSpacing, Alignment.Start)
                                    LyricsPosition.CENTER -> Arrangement.spacedBy(horizontalSpacing, Alignment.CenterHorizontally)
                                    LyricsPosition.RIGHT -> Arrangement.spacedBy(horizontalSpacing, Alignment.End)
                                },
                                verticalArrangement = Arrangement.spacedBy(verticalLineSpacing),
                            ) {
                                wordsToRender.forEach { (text, timings, isBg) ->
                                    val (wordStartMs, wordEndMs) = timings
                                    
                                    KaraokeWord(
                                        text = text,
                                        startTime = wordStartMs,
                                        endTime = wordEndMs,
                                        currentTimeProvider = karaokeCurrentTimeProvider,
                                        isRtl = lineIsRtl,
                                        fontSize = lyricsTextSize.sp,
                                        textColor = lyricsBaseColor,
                                        inactiveAlpha = if (isActiveLine) 0.35f else 0.7f,
                                        fontWeight = FontWeight.Bold,
                                        isBackground = isBg,
                                        nudgeEnabled = isActiveLine && !reduceMotionDuringScroll,
                                    )
                                }
                            }
                        } else if (effectiveAnimationStyle == LyricsAnimationStyle.ARCHIVE_TUNE) {
                            if (hasWordTimings && item.words != null) {
                                val archiveTuneMaxFontSize = lyricsTextSize.sp
                                FlowRow(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp),
                                    horizontalArrangement =
                                        when (lyricsTextPosition) {
                                            LyricsPosition.LEFT -> Arrangement.Start
                                            LyricsPosition.CENTER -> Arrangement.Center
                                            LyricsPosition.RIGHT -> Arrangement.End
                                        },
                                    verticalArrangement = Arrangement.spacedBy(verticalLineSpacing),
                                ) {
                                    item.words.forEachIndexed { wordIndex, word ->
                                        val nextText = item.words.getOrNull(wordIndex + 1)?.text
                                        val displayText =
                                            if (
                                                nextText != null &&
                                                shouldAppendWordSpace(word.text, nextText)
                                            ) {
                                                if (lineIsRtl) " ${word.text}" else "${word.text} "
                                            } else {
                                                word.text
                                            }

                                        ArchiveTuneWord(
                                            text = displayText,
                                            startTime = (word.startTime * 1000).toLong(),
                                            endTime = (word.endTime * 1000).toLong(),
                                            currentTime =
                                                if (isActiveLine) {
                                                    currentPlaybackPosition
                                                } else {
                                                    Long.MIN_VALUE
                                                },
                                            isRtl = lineIsRtl,
                                            fontSize = archiveTuneMaxFontSize,
                                            textColor = lyricsBaseColor,
                                            isBackground = word.isBackground,
                                            lineFocus = archiveLineFocus,
                                            motionEnabled = isActiveLine && !reduceMotionDuringScroll,
                                            // Keep the old line's reveal layer alive during the
                                            // focus tail without subscribing every visible line to
                                            // the 20 Hz playback clock.
                                            releaseCompleted =
                                                !isActiveLine && archiveLineFocus > 0.001f,
                                        )
                                    }
                                }
                            } else {
                                Text(
                                    text = item.text,
                                    fontSize = lyricsTextSize.sp * 0.96f,
                                    color = if (isActiveLine) lyricsBaseColor else lineColor,
                                    textAlign = alignment,
                                    fontWeight = FontWeight.Bold,
                                    lineHeight = (lyricsTextSize * lyricsLineSpacing).sp,
                                )
                            }
                        } else if (hasWordTimings && item.words != null && effectiveAnimationStyle == LyricsAnimationStyle.APPLE) {
                            val styledText = buildAnnotatedString {
                                item.words.forEachIndexed { wordIndex, word ->
                                    val wordStartMs = (word.startTime * 1000).toLong()
                                    val wordEndMs = (word.endTime * 1000).toLong()
                                    val wordDuration = wordEndMs - wordStartMs

                                    val isWordActive = isActiveLine && currentPlaybackPosition >= wordStartMs && currentPlaybackPosition <= wordEndMs
                                    val hasWordPassed = isActiveLine && currentPlaybackPosition > wordEndMs

                                    val transitionProgress = when {
                                        !isActiveLine -> 0f
                                        hasWordPassed -> 1f
                                        isWordActive && wordDuration > 0 -> {
                                            val elapsed = currentPlaybackPosition - wordStartMs

                                            val linear = (elapsed.toFloat() / wordDuration).coerceIn(0f, 1f)

                                            linear * linear * (3f - 2f * linear) 
                                        }
                                        else -> 0f
                                    }

                                    val wordAlpha = when {
                                        !isActiveLine && index < displayedCurrentLineIndex ->
                                            1f
                                        !isActiveLine -> 0.62f
                                        hasWordPassed -> 1f
                                        isWordActive -> 0.5f + (0.5f * transitionProgress)
                                        else -> 0.35f
                                    }

                                    // Apply background vocal styling
                                    val effectiveAlpha = if (word.isBackground) wordAlpha * 0.6f else wordAlpha
                                    val wordColor = lyricsBaseColor.copy(alpha = effectiveAlpha)

                                    val wordWeight = FontWeight.Bold

                                    withStyle(
                                        style = SpanStyle(
                                            color = wordColor,
                                            fontWeight = wordWeight,
                                            fontSize = if (word.isBackground) appleLayoutFontSize * 0.7f else TextUnit.Unspecified
                                        )
                                    ) {
                                        append(word.text)
                                    }

                                    if (wordIndex < item.words.size - 1) {
                                        append(" ")
                                    }
                                }
                            }

                            Text(
                                text = styledText,
                                fontSize = appleLayoutFontSize,
                                textAlign = alignment,
                                lineHeight = (lyricsTextSize * lyricsLineSpacing).sp,
                                modifier =
                                    Modifier
                                        .padding(horizontal = 4.dp, vertical = 2.dp)
                                        .graphicsLayer {
                                            clip = false
                                            scaleX = appleVisualScale
                                            scaleY = appleVisualScale
                                        },
                            )
                        } else if (hasWordTimings && item.words != null && effectiveAnimationStyle == LyricsAnimationStyle.FADE) {
                            /*
                             * Keep one typography/layout path for the whole lifetime of the row.
                             * The old implementation swapped inactive Medium plain Text for active
                             * Bold annotated Text, so glyph widths and wrapping could change at the
                             * exact moment the line became current.
                             */
                            val styledText =
                                buildAnnotatedString {
                                    item.words.forEachIndexed { wordIndex, word ->
                                        val wordStartMs = (word.startTime * 1000).toLong()
                                        val wordEndMs = (word.endTime * 1000).toLong()
                                        val wordDuration = (wordEndMs - wordStartMs).coerceAtLeast(1L)

                                        val isWordActive =
                                            isActiveLine &&
                                                !reduceMotionDuringScroll &&
                                                currentPlaybackPosition in wordStartMs..wordEndMs
                                        val hasWordPassed =
                                            isActiveLine &&
                                                currentPlaybackPosition > wordEndMs

                                        val fadeProgress =
                                            when {
                                                hasWordPassed -> 1f
                                                isWordActive -> {
                                                    val linear =
                                                        (
                                                            (currentPlaybackPosition - wordStartMs)
                                                                .toFloat() /
                                                                wordDuration.toFloat()
                                                        ).coerceIn(0f, 1f)
                                                    linear * linear * (3f - 2f * linear)
                                                }
                                                else -> 0f
                                            }

                                        val wordAlpha =
                                            when {
                                                !isActiveLine &&
                                                    index < displayedCurrentLineIndex ->
                                                    1f
                                                !isActiveLine -> 0.65f
                                                reduceMotionDuringScroll -> 0.65f
                                                else -> 0.35f + (0.65f * fadeProgress)
                                            }
                                        val effectiveAlpha =
                                            if (word.isBackground) wordAlpha * 0.6f else wordAlpha

                                        withStyle(
                                            style =
                                                SpanStyle(
                                                    color =
                                                        lyricsBaseColor.copy(
                                                            alpha = effectiveAlpha,
                                                        ),
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize =
                                                        if (word.isBackground) {
                                                            lyricsTextSize.sp * 0.85f
                                                        } else {
                                                            TextUnit.Unspecified
                                                        },
                                                ),
                                        ) {
                                            append(word.text)
                                        }

                                        if (wordIndex < item.words.size - 1) append(" ")
                                    }
                                }

                            Text(
                                text = styledText,
                                fontSize = lyricsTextSize.sp,
                                textAlign = alignment,
                                lineHeight = (lyricsTextSize * lyricsLineSpacing).sp,
                            )
                        } else if (hasWordTimings && item.words != null && effectiveAnimationStyle == LyricsAnimationStyle.GLOW) {
                            /*
                             * Same measured text in active/inactive states. Glow is paint-only now:
                             * no Medium -> Bold layout swap when focus changes.
                             */
                            val styledText =
                                buildAnnotatedString {
                                    item.words.forEachIndexed { wordIndex, word ->
                                        val wordStartMs = (word.startTime * 1000).toLong()
                                        val wordEndMs = (word.endTime * 1000).toLong()
                                        val wordDuration = (wordEndMs - wordStartMs).coerceAtLeast(1L)

                                        val isWordActive =
                                            isActiveLine &&
                                                !reduceMotionDuringScroll &&
                                                currentPlaybackPosition in wordStartMs..wordEndMs
                                        val hasWordPassed =
                                            isActiveLine &&
                                                currentPlaybackPosition > wordEndMs

                                        val fillProgress =
                                            when {
                                                hasWordPassed -> 1f
                                                isWordActive -> {
                                                    val linear =
                                                        (
                                                            (currentPlaybackPosition - wordStartMs)
                                                                .toFloat() /
                                                                wordDuration.toFloat()
                                                        ).coerceIn(0f, 1f)
                                                    linear * linear * (3f - 2f * linear)
                                                }
                                                else -> 0f
                                            }

                                        val brightness =
                                            when {
                                                !isActiveLine &&
                                                    index < displayedCurrentLineIndex ->
                                                    1f
                                                !isActiveLine -> 0.58f
                                                reduceMotionDuringScroll -> 0.58f
                                                isWordActive || hasWordPassed ->
                                                    0.45f + (0.55f * fillProgress)
                                                else -> 0.35f
                                            }
                                        val effectiveAlpha =
                                            if (word.isBackground) brightness * 0.6f else brightness

                                        // Keep the expensive blur only on the word that is actually
                                        // moving. Completed words stay bright but do not carry a
                                        // shadow that would disappear abruptly at line hand-off.
                                        val glowIntensity = fillProgress * fillProgress
                                        val wordShadow =
                                            if (isWordActive && glowIntensity > 0.05f) {
                                                val floatAmount =
                                                    sin(fillProgress * Math.PI).toFloat() * 0.5f
                                                Shadow(
                                                    color =
                                                        lyricsGlowColor.copy(
                                                            alpha =
                                                                if (hasRomanization) {
                                                                    0.55f + (0.25f * glowIntensity)
                                                                } else {
                                                                    0.45f + (0.25f * glowIntensity)
                                                                },
                                                        ),
                                                    offset = Offset(0f, -floatAmount),
                                                    blurRadius =
                                                        if (hasRomanization) {
                                                            16f + (8f * glowIntensity)
                                                        } else {
                                                            13f + (8f * glowIntensity)
                                                        },
                                                )
                                            } else {
                                                null
                                            }

                                        withStyle(
                                            style =
                                                SpanStyle(
                                                    color =
                                                        lyricsBaseColor.copy(
                                                            alpha = effectiveAlpha,
                                                        ),
                                                    fontWeight = FontWeight.Bold,
                                                    shadow = wordShadow,
                                                    fontSize =
                                                        if (word.isBackground) {
                                                            lyricsTextSize.sp * 0.7f
                                                        } else {
                                                            TextUnit.Unspecified
                                                        },
                                                ),
                                        ) {
                                            append(word.text)
                                        }

                                        if (wordIndex < item.words.size - 1) append(" ")
                                    }
                                }

                            Text(
                                text = styledText,
                                fontSize = lyricsTextSize.sp,
                                textAlign = alignment,
                                lineHeight = (lyricsTextSize * lyricsLineSpacing).sp,
                            )
                        } else if (hasWordTimings && item.words != null && effectiveAnimationStyle == LyricsAnimationStyle.SLIDE) {
                            val firstWordStartMs =
                                (item.words.firstOrNull()?.startTime?.times(1000))?.toLong() ?: 0L
                            val lastWordEndMs =
                                (item.words.lastOrNull()?.endTime?.times(1000))?.toLong() ?: 0L
                            val lineDuration = (lastWordEndMs - firstWordStartMs).coerceAtLeast(1L)
                            val isLineActive =
                                isActiveLine &&
                                    !reduceMotionDuringScroll &&
                                    currentPlaybackPosition in firstWordStartMs..lastWordEndMs
                            val hasLinePassed =
                                isActiveLine &&
                                    currentPlaybackPosition > lastWordEndMs

                            val fillProgress =
                                when {
                                    hasLinePassed -> 1f
                                    isLineActive -> {
                                        (
                                            (currentPlaybackPosition - firstWordStartMs).toFloat() /
                                                lineDuration.toFloat()
                                        ).coerceIn(0f, 1f)
                                    }
                                    else -> 0f
                                }

                            val styledText =
                                buildAnnotatedString {
                                    val stableStyle =
                                        when {
                                            isLineActive -> {
                                                SpanStyle(
                                                    brush =
                                                        rtlAwareHorizontalGradient(
                                                            isRtl = lineIsRtl,
                                                            0.0f to lyricsBaseColor,
                                                            (fillProgress * 0.95f)
                                                                .coerceIn(0f, 1f) to lyricsBaseColor,
                                                            fillProgress to
                                                                lyricsBaseColor.copy(alpha = 0.9f),
                                                            (fillProgress + 0.02f)
                                                                .coerceIn(0f, 1f) to
                                                                lyricsBaseColor.copy(alpha = 0.5f),
                                                            (fillProgress + 0.08f)
                                                                .coerceIn(0f, 1f) to
                                                                lyricsBaseColor.copy(alpha = 0.35f),
                                                            1.0f to
                                                                lyricsBaseColor.copy(alpha = 0.35f),
                                                        ),
                                                    fontWeight = FontWeight.Bold,
                                                )
                                            }
                                            !isActiveLine &&
                                                index < displayedCurrentLineIndex ->
                                                SpanStyle(
                                                    color =
                                                        lyricsBaseColor.copy(
                                                            alpha =
                                                                1f,
                                                        ),
                                                    fontWeight = FontWeight.Bold,
                                                )
                                            hasLinePassed ->
                                                SpanStyle(
                                                    color = lyricsBaseColor,
                                                    fontWeight = FontWeight.Bold,
                                                )
                                            else ->
                                                SpanStyle(
                                                    color =
                                                        if (isActiveLine) {
                                                            lyricsBaseColor.copy(alpha = 0.35f)
                                                        } else {
                                                            lineColor
                                                        },
                                                    fontWeight = FontWeight.Bold,
                                                )
                                        }

                                    withStyle(stableStyle) {
                                        append(item.text)
                                    }
                                }

                            Text(
                                text = styledText,
                                fontSize = lyricsTextSize.sp,
                                textAlign = alignment,
                                lineHeight = (lyricsTextSize * lyricsLineSpacing).sp,
                            )
                        } else if (hasWordTimings && item.words != null && effectiveAnimationStyle == LyricsAnimationStyle.KARAOKE) {
                            val styledText = buildAnnotatedString {
                                item.words.forEachIndexed { wordIndex, word ->
                                    val wordStartMs = (word.startTime * 1000).toLong()
                                    val wordEndMs = (word.endTime * 1000).toLong()
                                    val wordDuration = (wordEndMs - wordStartMs).coerceAtLeast(1L)

                                    val isWordActive = isActiveLine && currentPlaybackPosition >= wordStartMs && currentPlaybackPosition < wordEndMs
                                    val hasWordPassed = (isActiveLine && currentPlaybackPosition >= wordEndMs) || (!isActiveLine && index < displayedCurrentLineIndex)
                                    val isUpcoming = isActiveLine && currentPlaybackPosition < wordStartMs

                                    if (isWordActive && wordDuration > 0) {
                                        val timeElapsed = currentPlaybackPosition - wordStartMs
                                        val linearProgress = (timeElapsed.toFloat() / wordDuration.toFloat()).coerceIn(0f, 1f)
                                        
                                        val fillProgress = linearProgress * linearProgress * (3f - 2f * linearProgress)
                                        
                                        val breatheCycleDuration = wordDuration.toFloat().coerceIn(400f, 2000f)
                                        val breathePhase = (timeElapsed % breatheCycleDuration) / breatheCycleDuration
                                        val breatheEffect = (sin(breathePhase * Math.PI.toFloat()) * 0.05f).coerceIn(0f, 0.05f)
                                        
                                        val glowIntensity = (fillProgress + breatheEffect).coerceIn(0f, 1.0f)

                                        val wordBrush = rtlAwareHorizontalGradient(
                                            isRtl = lineIsRtl,
                                            0.0f to lyricsBaseColor,
                                            (fillProgress * 0.85f).coerceIn(0f, 0.99f) to lyricsBaseColor,
                                            fillProgress.coerceIn(0.01f, 0.99f) to lyricsBaseColor.copy(alpha = 0.85f),
                                            (fillProgress + 0.02f).coerceIn(0.01f, 1f) to lyricsBaseColor.copy(alpha = 0.45f),
                                            (fillProgress + 0.08f).coerceIn(0.01f, 1f) to lyricsBaseColor.copy(alpha = 0.3f),
                                            1.0f to lyricsBaseColor.copy(alpha = 0.3f)
                                        )

                                        withStyle(
                                            style = SpanStyle(
                                                brush = wordBrush,
                                                fontWeight = FontWeight.Bold
                                            )
                                        ) {
                                            append(word.text)
                                        }
                                    } else if (hasWordPassed) {
                                        withStyle(
                                            style = SpanStyle(
                                                color =
                                                    lyricsBaseColor.copy(
                                                        alpha =
                                                            if (!isActiveLine) {
                                                                1f
                                                            } else {
                                                                1f
                                                            },
                                                    ),
                                                fontWeight = FontWeight.Bold
                                            )
                                        ) {
                                            append(word.text)
                                        }
                                    } else if (isUpcoming && isActiveLine) {
                                        withStyle(
                                            style = SpanStyle(
                                                color = lyricsBaseColor.copy(alpha = 0.3f),
                                                fontWeight = FontWeight.Bold
                                            )
                                        ) {
                                            append(word.text)
                                        }
                                    } else {
                                        val wordColor = if (!isActiveLine) lineColor else lyricsBaseColor.copy(alpha = 0.3f)

                                        withStyle(
                                            style = SpanStyle(
                                                color = wordColor,
                                                fontWeight = FontWeight.Bold
                                            )
                                        ) {
                                            append(word.text)
                                        }
                                    }

                                    if (wordIndex < item.words.size - 1) {
                                        append(" ")
                                    }
                                }
                            }

                            Text(
                                text = styledText,
                                fontSize = lyricsTextSize.sp,
                                textAlign = alignment,
                                lineHeight = (lyricsTextSize * lyricsLineSpacing).sp
                            )
                        } else if (hasWordTimings && item.words != null && effectiveAnimationStyle == LyricsAnimationStyle.APPLE) {

                            val styledText = buildAnnotatedString {
                                item.words.forEachIndexed { wordIndex, word ->
                                    val wordStartMs = (word.startTime * 1000).toLong()
                                    val wordEndMs = (word.endTime * 1000).toLong()
                                    val wordDuration = wordEndMs - wordStartMs

                                    val isWordActive = isActiveLine && currentPlaybackPosition >= wordStartMs && currentPlaybackPosition < wordEndMs
                                    val hasWordPassed = (isActiveLine && currentPlaybackPosition >= wordEndMs) || (!isActiveLine && index < displayedCurrentLineIndex)

                                    val rawProgress = if (isWordActive && wordDuration > 0) {
                                        val elapsed = currentPlaybackPosition - wordStartMs
                                        (elapsed.toFloat() / wordDuration).coerceIn(0f, 1f)
                                    } else if (hasWordPassed) {
                                        1f
                                    } else {
                                        0f
                                    }

                                    val smoothProgress = rawProgress * rawProgress * (3f - 2f * rawProgress)

                                    val wordAlpha = when {
                                        !isActiveLine && index < displayedCurrentLineIndex ->
                                            1f
                                        !isActiveLine -> 0.55f
                                        hasWordPassed -> 1f
                                        isWordActive -> 0.55f + (0.45f * smoothProgress)
                                        else -> 0.35f
                                    }

                                    val wordColor = lyricsBaseColor.copy(alpha = wordAlpha)

                                    val wordWeight = FontWeight.Bold

                                    withStyle(
                                        style = SpanStyle(
                                            color = wordColor,
                                            fontWeight = wordWeight
                                        )
                                    ) {
                                        append(word.text)
                                    }

                                    if (wordIndex < item.words.size - 1) {
                                        append(" ")
                                    }
                                }
                            }

                            Text(
                                text = styledText,
                                fontSize = appleLayoutFontSize,
                                textAlign = alignment,
                                lineHeight = (lyricsTextSize * lyricsLineSpacing).sp,
                                modifier =
                                    Modifier
                                        .padding(horizontal = 4.dp, vertical = 2.dp)
                                        .graphicsLayer {
                                            clip = false
                                            scaleX = appleVisualScale
                                            scaleY = appleVisualScale
                                        },
                            )
                        } else if (isActiveLine && effectiveAnimationStyle == LyricsAnimationStyle.GLOW && !reduceMotionDuringScroll) {

                            val styledText = buildAnnotatedString {
                                withStyle(
                                    style = SpanStyle(
                                        shadow = Shadow(
                                            color = lyricsGlowColor.copy(alpha = if (hasRomanization) 0.9f else 0.8f),
                                            offset = Offset(0f, 0f),
                                            blurRadius = if (hasRomanization) 36f else 30f
                                        )
                                    )
                                ) {
                                    append(item.text)
                                }
                            }

                            Text(
                                text = styledText,
                                fontSize = lyricsTextSize.sp,
                                color = lyricsBaseColor,
                                textAlign = alignment,
                                fontWeight = FontWeight.Bold,
                                lineHeight = (lyricsTextSize * lyricsLineSpacing).sp
                            )
                        } else if (isActiveLine && effectiveAnimationStyle == LyricsAnimationStyle.SLIDE && !reduceMotionDuringScroll) {

                            val fillProgress = remember { Animatable(0f) }

                            LaunchedEffect(index) {
                                fillProgress.snapTo(0f)
                                fillProgress.animateTo(
                                    targetValue = 1f,
                                    animationSpec = tween(
                                        durationMillis = 1200,
                                        easing = CapsuleStandardEasing
                                    )
                                )
                            }

                            val fill = fillProgress.value

                            val slideBrush = rtlAwareHorizontalGradient(
                                isRtl = lineIsRtl,
                                0.0f to lyricsBaseColor.copy(alpha = 0.3f),
                                (fill * 0.7f).coerceIn(0f, 1f) to lyricsBaseColor.copy(alpha = 0.9f),
                                fill to lyricsBaseColor,
                                (fill + 0.1f).coerceIn(0f, 1f) to lyricsBaseColor.copy(alpha = 0.7f),
                                1.0f to lyricsBaseColor.copy(alpha = if (fill >= 1f) 1f else 0.3f)
                            )

                            val styledText = buildAnnotatedString {
                                withStyle(
                                    style = SpanStyle(
                                        brush = slideBrush
                                    )
                                ) {
                                    append(item.text)
                                }
                            }

                            Text(
                                text = styledText,
                                fontSize = lyricsTextSize.sp,
                                textAlign = alignment,
                                fontWeight = FontWeight.Bold,
                                lineHeight = (lyricsTextSize * lyricsLineSpacing).sp,
                                modifier = Modifier
                            )
                        } else if (isActiveLine && effectiveAnimationStyle == LyricsAnimationStyle.APPLE && !reduceMotionDuringScroll) {

                            val styledText = if (item.words != null) {
                                buildAnnotatedString {
                                    item.words.forEachIndexed { idx, word ->
                                        if (word.isBackground) {
                                            withStyle(SpanStyle(fontSize = appleLayoutFontSize * 0.7f)) {
                                                append(word.text)
                                            }
                                        } else {
                                            append(word.text)
                                        }
                                        if (idx < item.words.size - 1) append(" ")
                                    }
                                }
                            } else AnnotatedString(item.text)

                            Text(
                                text = styledText,
                                fontSize = appleLayoutFontSize,
                                color = lyricsBaseColor,
                                textAlign = alignment,
                                fontWeight = FontWeight.Bold,
                                lineHeight = (lyricsTextSize * lyricsLineSpacing).sp,
                                modifier =
                                    Modifier
                                        .padding(horizontal = 4.dp, vertical = 2.dp)
                                        .graphicsLayer {
                                            clip = false
                                            scaleX = appleVisualScale
                                            scaleY = appleVisualScale
                                        },
                            )
                        } else {

                            val styledText = if (item.words != null) {
                                buildAnnotatedString {
                                    item.words.forEachIndexed { idx, word ->
                                        if (word.isBackground) {
                                            withStyle(SpanStyle(fontSize = lyricsTextSize.sp * 0.7f)) {
                                                append(word.text)
                                            }
                                        } else {
                                            append(word.text)
                                        }
                                        if (idx < item.words.size - 1) append(" ")
                                    }
                                }
                            } else AnnotatedString(item.text)

                            Text(
                                text = styledText,
                                fontSize = lyricsTextSize.sp,
                                color = lineColor,
                                textAlign = alignment,
                                fontWeight = FontWeight.Bold,
                                lineHeight = (lyricsTextSize * lyricsLineSpacing).sp,
                                modifier = Modifier
                            )
                        }
                        if (romanizeJapaneseLyrics || romanizeKoreanLyrics) {

                            val romanizedFontSize = 16.sp
                            romanizedText?.let { romanized ->

                                if (hasWordTimings && item.words != null && isActiveLine && effectiveAnimationStyle != LyricsAnimationStyle.NONE && !reduceMotionDuringScroll) {

                                    val romanizedWords = romanized.split(" ")
                                    val mainWords = item.words

                                    val romanizedStyledText = buildAnnotatedString {
                                        romanizedWords.forEachIndexed { romIndex, romWord ->

                                            val wordIndex = (romIndex.toFloat() / romanizedWords.size * mainWords.size).toInt().coerceIn(0, mainWords.size - 1)
                                            val word = mainWords.getOrNull(wordIndex)

                                            if (word != null) {
                                                val wordStartMs = (word.startTime * 1000).toLong()
                                                val wordEndMs = (word.endTime * 1000).toLong()
                                                val wordDuration = wordEndMs - wordStartMs
                                                val isWordActive = currentPlaybackPosition in wordStartMs..wordEndMs
                                                val hasWordPassed = currentPlaybackPosition > wordEndMs

                                                when (effectiveAnimationStyle) {
                                                    LyricsAnimationStyle.APPLE,
                                                    LyricsAnimationStyle.KARAOKE,
                                                    LyricsAnimationStyle.ARCHIVE_TUNE -> {
                                                        val rawProgress = if (isWordActive && wordDuration > 0) {
                                                            val elapsed = currentPlaybackPosition - wordStartMs
                                                            (elapsed.toFloat() / wordDuration).coerceIn(0f, 1f)
                                                        } else if (hasWordPassed) {
                                                            1f
                                                        } else {
                                                            0f
                                                        }
                                                        val smoothProgress = rawProgress * rawProgress * (3f - 2f * rawProgress)

                                                        val romAlpha = when {
                                                            hasWordPassed -> 0.8f
                                                            isWordActive -> 0.4f + (0.4f * smoothProgress)
                                                            else -> 0.3f
                                                        }

                                                        withStyle(
                                                            style = SpanStyle(
                                                                color = lyricsBaseColor.copy(alpha = romAlpha),
                                                                fontWeight = FontWeight.Medium
                                                            )
                                                        ) {
                                                            append(romWord)
                                                        }
                                                    }
                                                    LyricsAnimationStyle.FADE -> {
                                                        val fadeProgress = if (isWordActive && wordDuration > 0) {
                                                            val timeElapsed = currentPlaybackPosition - wordStartMs
                                                            (timeElapsed.toFloat() / wordDuration.toFloat()).coerceIn(0f, 1f)
                                                        } else if (hasWordPassed) {
                                                            1f
                                                        } else {
                                                            0f
                                                        }
                                                        val romAlpha = 0.3f + (0.5f * fadeProgress)

                                                        withStyle(
                                                            style = SpanStyle(
                                                                color = lyricsBaseColor.copy(alpha = romAlpha),
                                                                fontWeight = FontWeight.Medium
                                                            )
                                                        ) {
                                                            append(romWord)
                                                        }
                                                    }
                                                    LyricsAnimationStyle.SLIDE -> {
                                                        if (isWordActive && wordDuration > 0) {
                                                            val timeElapsed = currentPlaybackPosition - wordStartMs
                                                            val fillProgress = (timeElapsed.toFloat() / wordDuration.toFloat()).coerceIn(0f, 1f)

                                                            val romBrush = rtlAwareHorizontalGradient(
                                                                isRtl = lineIsRtl,
                                                                0.0f to lyricsBaseColor.copy(alpha = 0.8f),
                                                                (fillProgress * 0.95f).coerceIn(0f, 1f) to lyricsBaseColor.copy(alpha = 0.8f),
                                                                fillProgress to lyricsBaseColor.copy(alpha = 0.5f),
                                                                (fillProgress + 0.05f).coerceIn(0f, 1f) to lyricsBaseColor.copy(alpha = 0.3f),
                                                                1.0f to lyricsBaseColor.copy(alpha = 0.3f)
                                                            )

                                                            withStyle(
                                                                style = SpanStyle(
                                                                    brush = romBrush,
                                                                    fontWeight = FontWeight.Medium
                                                                )
                                                            ) {
                                                                append(romWord)
                                                            }
                                                        } else {
                                                            val romColor = when {
                                                                hasWordPassed -> lyricsBaseColor.copy(alpha = 0.7f)
                                                                else -> lyricsBaseColor.copy(alpha = 0.3f)
                                                            }
                                                            withStyle(
                                                                style = SpanStyle(
                                                                    color = romColor,
                                                                    fontWeight = FontWeight.Medium
                                                                )
                                                            ) {
                                                                append(romWord)
                                                            }
                                                        }
                                                    }
                                                    LyricsAnimationStyle.GLOW -> {
                                                        val fillProgress = if (isWordActive && wordDuration > 0) {
                                                            val timeElapsed = currentPlaybackPosition - wordStartMs
                                                            (timeElapsed.toFloat() / wordDuration.toFloat()).coerceIn(0f, 1f)
                                                        } else if (hasWordPassed) {
                                                            1f
                                                        } else {
                                                            0f
                                                        }

                                                        val romAlpha = 0.4f + (0.4f * fillProgress)
                                                        val romShadow = if (isWordActive && fillProgress > 0.05f) {
                                                            Shadow(
                                                                color = lyricsGlowColor.copy(alpha = 0.5f * fillProgress),
                                                                offset = Offset.Zero,
                                                                blurRadius = 10f * fillProgress
                                                            )
                                                        } else null

                                                        withStyle(
                                                            style = SpanStyle(
                                                                color = lyricsBaseColor.copy(alpha = romAlpha),
                                                                fontWeight = FontWeight.Medium,
                                                                shadow = romShadow
                                                            )
                                                        ) {
                                                            append(romWord)
                                                        }
                                                    }
                                                    else -> {

                                                        val romColor = when {
                                                            !isActiveLine -> lyricsBaseColor.copy(alpha = 0.5f)
                                                            isWordActive -> lyricsBaseColor.copy(alpha = 0.8f)
                                                            hasWordPassed -> lyricsBaseColor.copy(alpha = 0.7f)
                                                            else -> lyricsBaseColor.copy(alpha = 0.4f)
                                                        }

                                                        withStyle(
                                                            style = SpanStyle(
                                                                color = romColor,
                                                                fontWeight = FontWeight.Medium
                                                            )
                                                        ) {
                                                            append(romWord)
                                                        }
                                                    }
                                                }
                                            } else {
                                                withStyle(
                                                    style = SpanStyle(
                                                        color = lyricsBaseColor.copy(alpha = 0.5f),
                                                        fontWeight = FontWeight.Normal
                                                    )
                                                ) {
                                                    append(romWord)
                                                }
                                            }

                                            if (romIndex < romanizedWords.size - 1) {
                                                append(" ")
                                            }
                                        }
                                    }

                                    Text(
                                        text = romanizedStyledText,
                                        fontSize = romanizedFontSize,
                                        textAlign = when (lyricsTextPosition) {
                                            LyricsPosition.LEFT -> TextAlign.Start
                                            LyricsPosition.CENTER -> TextAlign.Center
                                            LyricsPosition.RIGHT -> TextAlign.End
                                        },
                                        modifier = Modifier.padding(top = 2.dp)
                                    )
                                } else {

                                    Text(
                                        text = romanized,
                                        fontSize = romanizedFontSize,
                                        color = lyricsBaseColor.copy(alpha = if (isActiveLine) 0.6f else 0.5f),
                                        textAlign = when (lyricsTextPosition) {
                                            LyricsPosition.LEFT -> TextAlign.Start
                                            LyricsPosition.CENTER -> TextAlign.Center
                                            LyricsPosition.RIGHT -> TextAlign.End
                                        },
                                        fontWeight = FontWeight.Normal,
                                        modifier = Modifier.padding(top = 2.dp)
                                    )
                                }
                            }
                            }
                        }
                    }
                    }
                }
            }
        }

            AnimatedVisibility(
                visible =
                    isManualScrolling &&
                        scrollLyrics &&
                        !isSelectionModeActive &&
                        !isReturningToSync,
                enter = slideInVertically(
                    animationSpec = tween(durationMillis = 300, easing = CapsuleStandardEasing),
                    initialOffsetY = { it * 2 }
                ) + fadeIn(
                    animationSpec = tween(durationMillis = 300, easing = CapsuleStandardEasing)
                ),
                exit = slideOutVertically(
                    animationSpec = tween(durationMillis = 200, easing = CapsuleStandardEasing),
                    targetOffsetY = { it * 2 }
                ) + fadeOut(
                    animationSpec = tween(durationMillis = 200, easing = CapsuleStandardEasing)
                ),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .background(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(24.dp)
                        )
                        .clickable {
                            if (
                                !isReturningToSync &&
                                currentLineIndex in lines.indices
                            ) {
                                /*
                                 * Keep manual mode set during the flight. That prevents the normal
                                 * auto-scroll path from becoming a second owner before the live row
                                 * is actually centered.
                                 */
                                deferredCurrentLineIndex = currentLineIndex
                                lastPreviewTime = 0L
                                isReturningToSync = true
                            }
                        }
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.play),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = stringResource(R.string.resume_autoscroll),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

        if (isSelectionModeActive) {
            mediaMetadata?.let { metadata ->
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp), 
                    contentAlignment = Alignment.Center
                ) {

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {

                        Box(
                            modifier = Modifier
                                .size(48.dp) 
                                .background(
                                    color = Color.Black.copy(alpha = 0.3f),
                                    shape = CircleShape
                                )
                                .clickable {
                                    isSelectionModeActive = false
                                    selectedIndices.clear()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.close),
                                contentDescription = stringResource(R.string.cancel),
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Row(
                            modifier = Modifier
                                .background(
                                    color = if (selectedIndices.isNotEmpty()) 
                                        Color.White.copy(alpha = 0.9f) 
                                    else 
                                        Color.White.copy(alpha = 0.5f), 
                                    shape = RoundedCornerShape(24.dp)
                                )
                                .clickable(enabled = selectedIndices.isNotEmpty()) {
                                    if (selectedIndices.isNotEmpty()) {
                                        val sortedIndices = selectedIndices.sorted()
                                        val selectedLyricsText = sortedIndices
                                            .mapNotNull { lines.getOrNull(it)?.text }
                                            .joinToString("\n")

                                        if (selectedLyricsText.isNotBlank()) {
                                            shareDialogData = Triple(
                                                selectedLyricsText,
                                                metadata.title,
                                                metadata.artists.joinToString { it.name }
                                            )
                                            showShareDialog = true
                                        }
                                        isSelectionModeActive = false
                                        selectedIndices.clear()
                                    }
                                }
                                .padding(horizontal = 24.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.share),
                                contentDescription = stringResource(R.string.share_selected),
                                tint = Color.Black, 
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = stringResource(R.string.share),
                                color = Color.Black, 
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }

    }

    if (showProgressDialog) {
        BasicAlertDialog(onDismissRequest = {  }) {
            Card( 
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Box(modifier = Modifier.padding(32.dp)) {
                    Text(
                        text = stringResource(R.string.generating_image) + "\n" + stringResource(R.string.please_wait),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }

    if (showShareDialog && shareDialogData != null) {
        val (lyricsText, songTitle, artists) =
            requireNotNull(shareDialogData) { "Lyrics share dialog data is missing" }
        BasicAlertDialog(onDismissRequest = { showShareDialog = false }) {
            Card(
                shape = MaterialTheme.shapes.medium,
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth(0.85f)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = stringResource(R.string.share_lyrics),
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val shareIntent = Intent().apply {
                                    action = Intent.ACTION_SEND
                                    type = "text/plain"
                                    val songLink = "https://music.youtube.com/watch?v=${mediaMetadata?.id}"

                                    putExtra(Intent.EXTRA_TEXT, "\"$lyricsText\"\n\n$songTitle - $artists\n$songLink")
                                }
                                context.startActivity(Intent.createChooser(shareIntent, resources.getString(R.string.share_lyrics)))
                                showShareDialog = false
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.share), 
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.share_as_text),
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {

                                shareDialogData = Triple(lyricsText, songTitle, artists)
                                showColorPickerDialog = true
                                showShareDialog = false
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.share), 
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.share_as_image),
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp, bottom = 4.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        Text(
                            text = stringResource(R.string.cancel),
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .clickable { showShareDialog = false }
                                .padding(vertical = 8.dp, horizontal = 12.dp)
                        )
                    }
                }
            }
        }
    }

    if (showColorPickerDialog && shareDialogData != null) {
        val (lyricsText, songTitle, artists) =
            requireNotNull(shareDialogData) { "Lyrics image dialog data is missing" }
        val coverUrl = mediaMetadata?.thumbnailUrl

        LaunchedEffect(coverUrl) {
            if (coverUrl != null) {
                withContext(Dispatchers.IO) {
                    try {
                        val loader = ImageLoader(context)
                        val req = ImageRequest.Builder(context).data(coverUrl).allowHardware(false).build()
                        val result = loader.execute(req)
                        val bmp = result.image?.toBitmap()
                        if (bmp != null) {
                            val palette = Palette.from(bmp).generate()
                            paletteGlassStyle = LyricsGlassStyle.fromPalette(palette)
                        }
                    } catch (error: Exception) {
                        reportRecoverableException("Lyrics", "derive lyrics palette", error)
                    }
                }
            }
        }

        val availableStyles = remember(paletteGlassStyle) {
            val base = LyricsGlassStyle.allPresets.toMutableList()
            paletteGlassStyle?.let { base.add(0, it) }
            base
        }

        BasicAlertDialog(onDismissRequest = { showColorPickerDialog = false }) {
            Card(
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 24.dp)
                ) {
                    Text(
                        text = stringResource(id = R.string.customize_colors),
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.02).em
                        ),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(340.dp)
                            .clip(RoundedCornerShape(20.dp))
                    ) {
                        LyricsImageCard(
                            lyricText = lyricsText,
                            mediaMetadata = mediaMetadata ?: return@Box,
                            glassStyle = selectedGlassStyle,
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    Text(
                        text = stringResource(id = R.string.customize_colors),
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 10.dp)
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                    ) {
                        availableStyles.forEach { style ->
                            val isSelected = selectedGlassStyle == style
                            Box(
                                modifier = Modifier
                                    .size(width = 72.dp, height = 72.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .then(
                                        if (isSelected) {
                                            Modifier.border(
                                                2.5.dp,
                                                MaterialTheme.colorScheme.primary,
                                                RoundedCornerShape(16.dp)
                                            )
                                        } else {
                                            Modifier.border(
                                                1.dp,
                                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                                                RoundedCornerShape(16.dp)
                                            )
                                        }
                                    )
                                    .clickable { selectedGlassStyle = style },
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(
                                            Brush.verticalGradient(
                                                colors = listOf(
                                                    style.surfaceTint.copy(alpha = 0.6f),
                                                    style.overlayColor.copy(alpha = 0.4f),
                                                )
                                            ),
                                            shape = RoundedCornerShape(16.dp)
                                        )
                                )

                                Box(
                                    modifier = Modifier
                                        .padding(6.dp)
                                        .fillMaxSize()
                                        .background(
                                            style.surfaceTint.copy(alpha = style.surfaceAlpha),
                                            RoundedCornerShape(10.dp)
                                        )
                                        .border(
                                            0.5.dp,
                                            Color.White.copy(alpha = 0.15f),
                                            RoundedCornerShape(10.dp)
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "Aa",
                                        color = style.textColor,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    Button(
                        onClick = {
                            showColorPickerDialog = false
                            showProgressDialog = true
                            scope.launch {
                                try {
                                    val exportSize = 1080
                                    val image = ComposeToImage.createLyricsImage(
                                        context = context,
                                        coverArtUrl = coverUrl,
                                        songTitle = songTitle,
                                        artistName = artists,
                                        lyrics = lyricsText,
                                        width = exportSize,
                                        height = exportSize,
                                        glassStyle = selectedGlassStyle,
                                    )
                                    val timestamp = System.currentTimeMillis()
                                    val filename = "lyrics_$timestamp"
                                    val uri = ComposeToImage.saveBitmapAsFile(context, image, filename)
                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "image/png"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(shareIntent, "Share Lyrics"))
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Failed to create image: ${e.message}", Toast.LENGTH_SHORT).show()
                                } finally {
                                    showProgressDialog = false
                                }
                            }
                        },
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                    ) {
                        Text(
                            text = stringResource(id = R.string.share),
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp
                        )
                    }
                }
            }
        }
        } 
    }
}

private const val VELUNE_AUTO_SCROLL_DURATION = 1500L 
private const val VELUNE_INITIAL_SCROLL_DURATION = 1000L 
private const val VELUNE_SEEK_DURATION = 800L 
private const val VELUNE_FAST_SEEK_DURATION = 600L 

private const val LyricsWordSyncLeadMs = 300L

private val NoSpaceAfterChars: Set<Char> = setOf('(', '[', '{', '«', '‹', '“', '‘')

private fun shouldAppendWordSpace(current: String, next: String): Boolean {
    if (current.isEmpty() || next.isEmpty()) return false
    val last = current.last()
    val first = next.first()
    if (last.isWhitespace() || first.isWhitespace()) return false
    if (!first.isLetterOrDigit()) return false
    return last !in NoSpaceAfterChars
}
