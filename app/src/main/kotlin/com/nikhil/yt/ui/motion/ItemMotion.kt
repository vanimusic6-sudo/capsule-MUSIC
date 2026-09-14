package com.nikhil.yt.ui.motion

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridItemScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset

/*
 * Item motion for the library's lists and grids.
 *
 * `animateItem()`'s default specs are what made entering a library read as a grid of tiles sliding
 * about. Room emits a library list after the first frame, so every row counts as newly added: the
 * default fade played across the whole grid at once while a medium-stiffness spring shuffled
 * everything into position behind it.
 *
 * Appearance is silent here — content that was always going to be there should simply be there.
 * Placement and removal stay animated, because those follow a real edit the user made: a reorder,
 * a delete. Both are critically damped, so a row settles once instead of springing past its slot
 * and coming back, which is what made fast switching look like it was glitching.
 */

private val CalmPlacementSpec: FiniteAnimationSpec<IntOffset> =
    spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = 260f,
        visibilityThreshold = IntOffset.VisibilityThreshold,
    )

private val CalmFadeOutSpec: FiniteAnimationSpec<Float> =
    spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 420f)

/*
 * These are scope functions rather than Modifier extensions because `animateItem` only resolves with
 * a lazy item scope as its receiver, so `ModifierFactoryExtensionFunction` cannot be satisfied here.
 */

@Suppress("ModifierFactoryExtensionFunction")
fun LazyItemScope.calmItemMotion(): Modifier =
    Modifier.animateItem(
        fadeInSpec = null,
        placementSpec = CalmPlacementSpec,
        fadeOutSpec = CalmFadeOutSpec,
    )

@Suppress("ModifierFactoryExtensionFunction")
fun LazyGridItemScope.calmItemMotion(): Modifier =
    Modifier.animateItem(
        fadeInSpec = null,
        placementSpec = CalmPlacementSpec,
        fadeOutSpec = CalmFadeOutSpec,
    )

@Suppress("ModifierFactoryExtensionFunction")
fun LazyStaggeredGridItemScope.calmItemMotion(): Modifier =
    Modifier.animateItem(
        fadeInSpec = null,
        placementSpec = CalmPlacementSpec,
        fadeOutSpec = CalmFadeOutSpec,
    )
