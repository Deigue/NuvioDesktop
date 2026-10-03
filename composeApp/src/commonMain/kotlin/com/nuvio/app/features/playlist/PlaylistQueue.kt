package com.nuvio.app.features.playlist

import kotlin.random.Random

/**
 * Pure ordering rules for playlists, kept apart from the repository so they can be tested without
 * storage.
 */
internal object PlaylistQueue {

    /**
     * The index after [currentIndex], or null when the playlist has run out. With [loop] the end
     * wraps back to the start — unless the playlist is a single entry, which would otherwise replay
     * itself forever with no way out but the back button.
     */
    fun nextIndex(size: Int, currentIndex: Int, loop: Boolean): Int? {
        if (size <= 0 || currentIndex !in 0 until size) return null
        val next = currentIndex + 1
        return when {
            next < size -> next
            loop && size > 1 -> 0
            else -> null
        }
    }

    /**
     * Where Play should pick up once the entry at [finishedIndex] has been watched through. A
     * playlist that has been finished (and does not loop) goes back to the start, so pressing Play
     * again replays it rather than doing nothing.
     */
    fun resumeIndexAfterFinishing(size: Int, finishedIndex: Int, loop: Boolean): Int =
        nextIndex(size, finishedIndex, loop) ?: 0

    /**
     * [resumeIndex] after the entry at [fromIndex] was dragged to [toIndex]. The resume pointer
     * follows the entry it points at, not the slot, so reordering never silently changes what Play
     * starts with.
     */
    fun resumeIndexAfterMove(resumeIndex: Int, fromIndex: Int, toIndex: Int): Int = when {
        resumeIndex == fromIndex -> toIndex
        fromIndex < resumeIndex && toIndex >= resumeIndex -> resumeIndex - 1
        fromIndex > resumeIndex && toIndex <= resumeIndex -> resumeIndex + 1
        else -> resumeIndex
    }

    /**
     * [resumeIndex] after the entry at [removedIndex] was deleted from a list that now has
     * [newSize] entries. Removing the entry being pointed at hands the pointer to whatever took
     * its place.
     */
    fun resumeIndexAfterRemoval(resumeIndex: Int, removedIndex: Int, newSize: Int): Int {
        if (newSize <= 0) return 0
        val shifted = if (removedIndex < resumeIndex) resumeIndex - 1 else resumeIndex
        return shifted.coerceIn(0, newSize - 1)
    }

    fun <T> move(list: List<T>, fromIndex: Int, toIndex: Int): List<T> {
        if (fromIndex == toIndex || fromIndex !in list.indices || toIndex !in list.indices) return list
        val mutable = list.toMutableList()
        val item = mutable.removeAt(fromIndex)
        mutable.add(toIndex, item)
        return mutable
    }

    /**
     * [resumeIndex] after the finished entry at [removedIndex] was dropped by "remove when watched",
     * leaving [newSize] entries. Whatever slid into its slot plays next; finishing the last entry
     * goes back to the start, as [resumeIndexAfterFinishing] does.
     */
    fun resumeIndexAfterFinishingRemoved(removedIndex: Int, newSize: Int): Int =
        if (removedIndex in 0 until newSize) removedIndex else 0

    /**
     * Shuffles [list] whole, so the first entry is a fresh pick every time rather than always the
     * one Play would have resumed at. Returns the new order and the resume index (always the front).
     */
    fun <T> shuffle(list: List<T>, random: Random = Random.Default): Pair<List<T>, Int> =
        list.shuffled(random) to 0

    /**
     * Puts [list] back in [originalOrder] (keys from [keyOf]). Anything not in the original order —
     * added while shuffled — follows, in its current relative order.
     */
    fun <T> unshuffle(list: List<T>, originalOrder: List<String>, keyOf: (T) -> String): List<T> {
        val rank = originalOrder.withIndex().associate { (i, key) -> key to i }
        return list.withIndex()
            .sortedWith(compareBy({ rank[keyOf(it.value)] ?: Int.MAX_VALUE }, { it.index }))
            .map { it.value }
    }
}
