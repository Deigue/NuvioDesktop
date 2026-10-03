package com.nuvio.app.core.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot

/**
 * Keeps a keyed lazy list at its top while its items are still arriving out of display order.
 *
 * A keyed lazy list anchors on its first visible item's key. When items load in completion order
 * — collection rows, stream providers — the first to answer lands at the top, and everything meant
 * to sit above it is then inserted out of view: the list opens scrolled to wherever the fastest
 * item ended up. While the list is still exactly at its top, this asks it to stay at index 0
 * instead of following that key. Once the user has scrolled, the default anchoring is what they want.
 *
 * [items] is whatever identifies the current item list; each new value re-checks.
 */
@Composable
fun KeepListAtTopWhileItemsArrive(state: LazyListState, items: Any?) {
    remember(state, items) {
        // Unobserved: this runs in the caller's composition, and reading the scroll position
        // normally would recompose the whole screen on every scroll frame.
        Snapshot.withoutReadObservation {
            if (state.firstVisibleItemIndex == 0 && state.firstVisibleItemScrollOffset == 0) {
                state.requestScrollToItem(0)
            }
        }
    }
}
