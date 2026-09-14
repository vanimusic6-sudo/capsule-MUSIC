package com.nikhil.yt.ui.utils

import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridItemScope
import androidx.compose.ui.Modifier
import com.nikhil.yt.constants.CalmItemFadeOutSpec
import com.nikhil.yt.constants.CalmItemPlacementSpec

/*
 * Entering a destination has to read as one canvas settling, not as a grid of tiles arriving
 * separately.
 *
 * `animateItem()` with its default specs is what produced the tile slide people recognise from the
 * old redesign. Room emits a library list after the first frame, so every row counts as newly added
 * and the default fade plays across the whole grid at once, while the placement spring shuffles
 * everything into position on top of the destination entrance.
 *
 * Appearance is therefore silent: the destination entrance is the only entrance motion in the app.
 * Placement and removal stay animated, because those are real edits the user made — a reorder, a
 * delete — and they are paced to match the entrance instead of the default spring.
 */

/*
 * These are scope functions rather than Modifier extensions because `animateItem` only resolves with
 * a lazy item scope as its receiver, so `ModifierFactoryExtensionFunction` cannot be satisfied here.
 */
@Suppress("ModifierFactoryExtensionFunction")
fun LazyItemScope.calmItemMotion(): Modifier =
    Modifier.animateItem(
        fadeInSpec = null,
        placementSpec = CalmItemPlacementSpec,
        fadeOutSpec = CalmItemFadeOutSpec,
    )

@Suppress("ModifierFactoryExtensionFunction")
fun LazyGridItemScope.calmItemMotion(): Modifier =
    Modifier.animateItem(
        fadeInSpec = null,
        placementSpec = CalmItemPlacementSpec,
        fadeOutSpec = CalmItemFadeOutSpec,
    )

@Suppress("ModifierFactoryExtensionFunction")
fun LazyStaggeredGridItemScope.calmItemMotion(): Modifier =
    Modifier.animateItem(
        fadeInSpec = null,
        placementSpec = CalmItemPlacementSpec,
        fadeOutSpec = CalmItemFadeOutSpec,
    )
