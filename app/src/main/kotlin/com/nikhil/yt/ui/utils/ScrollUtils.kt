/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */



package com.nikhil.yt.ui.utils

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.collect
import androidx.compose.runtime.remember

/**
 * Track direction without writing snapshot state from inside derivedStateOf.
 * Direction changes only on actual movement: stationary fingers / unchanged
 * offsets must not retrigger FAB visibility and animations.
 */
@Composable
fun LazyListState.isScrollingUp(): Boolean {
    var up by remember(this) { mutableStateOf(true) }
    androidx.compose.runtime.LaunchedEffect(this) {
        var previousIndex = firstVisibleItemIndex
        var previousOffset = firstVisibleItemScrollOffset
        androidx.compose.runtime.snapshotFlow { firstVisibleItemIndex to firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                if (index != previousIndex || offset != previousOffset) {
                    up = if (index != previousIndex) index < previousIndex else offset < previousOffset
                    previousIndex = index
                    previousOffset = offset
                }
            }
    }
    return up
}

@Composable
fun LazyGridState.isScrollingUp(): Boolean {
    var up by remember(this) { mutableStateOf(true) }
    androidx.compose.runtime.LaunchedEffect(this) {
        var previousIndex = firstVisibleItemIndex
        var previousOffset = firstVisibleItemScrollOffset
        androidx.compose.runtime.snapshotFlow { firstVisibleItemIndex to firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                if (index != previousIndex || offset != previousOffset) {
                    up = if (index != previousIndex) index < previousIndex else offset < previousOffset
                    previousIndex = index
                    previousOffset = offset
                }
            }
    }
    return up
}

@Composable
fun ScrollState.isScrollingUp(): Boolean {
    var up by remember(this) { mutableStateOf(true) }
    androidx.compose.runtime.LaunchedEffect(this) {
        var previousOffset = value
        androidx.compose.runtime.snapshotFlow { value }.collect { offset ->
            if (offset != previousOffset) {
                up = offset < previousOffset
                previousOffset = offset
            }
        }
    }
    return up
}
