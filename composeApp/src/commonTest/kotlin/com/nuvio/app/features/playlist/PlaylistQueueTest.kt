package com.nuvio.app.features.playlist

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class PlaylistQueueTest {

    @Test
    fun `next index walks forward and stops at the end`() {
        assertEquals(1, PlaylistQueue.nextIndex(size = 3, currentIndex = 0, loop = false))
        assertEquals(2, PlaylistQueue.nextIndex(size = 3, currentIndex = 1, loop = false))
        assertNull(PlaylistQueue.nextIndex(size = 3, currentIndex = 2, loop = false))
    }

    @Test
    fun `loop wraps the end back to the start`() {
        assertEquals(0, PlaylistQueue.nextIndex(size = 3, currentIndex = 2, loop = true))
    }

    @Test
    fun `a single entry never loops onto itself`() {
        assertNull(PlaylistQueue.nextIndex(size = 1, currentIndex = 0, loop = true))
    }

    @Test
    fun `an unknown index has no next`() {
        assertNull(PlaylistQueue.nextIndex(size = 3, currentIndex = -1, loop = true))
        assertNull(PlaylistQueue.nextIndex(size = 0, currentIndex = 0, loop = false))
    }

    @Test
    fun `finishing the last entry resets play to the start`() {
        assertEquals(0, PlaylistQueue.resumeIndexAfterFinishing(size = 3, finishedIndex = 2, loop = false))
        assertEquals(2, PlaylistQueue.resumeIndexAfterFinishing(size = 3, finishedIndex = 1, loop = false))
    }

    @Test
    fun `resume pointer follows the entry it points at through a move`() {
        // Moving the pointed-at entry carries the pointer with it.
        assertEquals(4, PlaylistQueue.resumeIndexAfterMove(resumeIndex = 1, fromIndex = 1, toIndex = 4))
        // An earlier entry moving past it shifts it up one.
        assertEquals(2, PlaylistQueue.resumeIndexAfterMove(resumeIndex = 3, fromIndex = 0, toIndex = 5))
        // A later entry moving in front of it shifts it down one.
        assertEquals(4, PlaylistQueue.resumeIndexAfterMove(resumeIndex = 3, fromIndex = 5, toIndex = 1))
        // Moves entirely on one side leave it alone.
        assertEquals(3, PlaylistQueue.resumeIndexAfterMove(resumeIndex = 3, fromIndex = 5, toIndex = 4))
        assertEquals(3, PlaylistQueue.resumeIndexAfterMove(resumeIndex = 3, fromIndex = 0, toIndex = 2))
    }

    @Test
    fun `move result matches the pointer rule`() {
        val list = listOf("a", "b", "c", "d", "e")
        for (resume in list.indices) for (from in list.indices) for (to in list.indices) {
            val moved = PlaylistQueue.move(list, from, to)
            val newResume = PlaylistQueue.resumeIndexAfterMove(resume, from, to)
            assertEquals(list[resume], moved[newResume], "resume=$resume from=$from to=$to")
        }
    }

    @Test
    fun `removal keeps the pointer on the same entry or its successor`() {
        assertEquals(1, PlaylistQueue.resumeIndexAfterRemoval(resumeIndex = 2, removedIndex = 0, newSize = 4))
        assertEquals(2, PlaylistQueue.resumeIndexAfterRemoval(resumeIndex = 2, removedIndex = 2, newSize = 4))
        assertEquals(2, PlaylistQueue.resumeIndexAfterRemoval(resumeIndex = 2, removedIndex = 3, newSize = 4))
        // Removing the last entry while it was up next clamps back into range.
        assertEquals(2, PlaylistQueue.resumeIndexAfterRemoval(resumeIndex = 3, removedIndex = 3, newSize = 3))
        assertEquals(0, PlaylistQueue.resumeIndexAfterRemoval(resumeIndex = 0, removedIndex = 0, newSize = 0))
    }

    @Test
    fun `an out of range move returns the same list`() {
        val list = listOf(1, 2, 3)
        assertSame(list, PlaylistQueue.move(list, 0, 7))
        assertSame(list, PlaylistQueue.move(list, 1, 1))
    }

    @Test
    fun `finishing a removed entry plays whatever slid into its slot`() {
        assertEquals(1, PlaylistQueue.resumeIndexAfterFinishingRemoved(removedIndex = 1, newSize = 3))
        // The last one: back to the start.
        assertEquals(0, PlaylistQueue.resumeIndexAfterFinishingRemoved(removedIndex = 3, newSize = 3))
        assertEquals(0, PlaylistQueue.resumeIndexAfterFinishingRemoved(removedIndex = 0, newSize = 0))
    }

    @Test
    fun `shuffle reorders the whole list and resumes at the front`() {
        val list = (0 until 20).toList()
        val (order, resume) = PlaylistQueue.shuffle(list, random = Random(1))
        assertEquals(0, resume)
        assertEquals(list.sorted(), order.sorted())
        assertNotEquals(list, order)
    }

    @Test
    fun `unshuffle restores the original order and appends new entries`() {
        val original = listOf("a", "b", "c", "d")
        // Shuffled, then "c" removed and "x", "y" added.
        val current = listOf("y", "d", "a", "x", "b")
        assertEquals(listOf("a", "b", "d", "y", "x"), PlaylistQueue.unshuffle(current, original) { it })
    }
}
