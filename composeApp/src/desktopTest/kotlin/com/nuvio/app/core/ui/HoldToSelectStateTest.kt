package com.nuvio.app.core.ui

import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tap versus hold on the select key. Real time rather than a virtual clock (the module has no
 * coroutines-test): a tap releases well inside the timeout, a hold waits well past it.
 */
class HoldToSelectStateTest {
    private var selects = 0
    private var holds = 0
    private val onSelect: () -> Unit = { selects++ }
    private val onHold: () -> Unit = { holds++ }

    @AfterTest
    fun reset() {
        HoldToSelect.enabled = true
        HoldToSelect.stopSwallowingRelease()
    }

    @Test
    fun `a tap selects on release and never holds`() = runBlocking {
        val state = HoldToSelectState(this)
        assertTrue(state.onPress(onSelect, onHold))
        assertEquals(0, selects, "select must wait for the release to rule out a hold")
        assertTrue(state.onRelease())
        delay(HoldToSelect.TIMEOUT_MS + 100)
        assertEquals(1, selects)
        assertEquals(0, holds)
        assertFalse(HoldToSelect.swallowSelectUntilRelease)
    }

    @Test
    fun `a hold opens actions once and never selects`() = runBlocking {
        val state = HoldToSelectState(this)
        state.onPress(onSelect, onHold)
        // Key-repeats while held must not restart the timer or fire anything.
        delay(100)
        state.onPress(onSelect, onHold)
        delay(HoldToSelect.TIMEOUT_MS)
        assertEquals(1, holds)
        assertEquals(0, selects)
        assertTrue(HoldToSelect.swallowSelectUntilRelease, "the rest of the press belongs to the hold")
        // The release is the desktop dispatcher's to swallow; the state has nothing left to do.
        assertFalse(state.onRelease())
        assertEquals(0, selects)
    }

    @Test
    fun `an item without hold actions selects on the press`() = runBlocking {
        val state = HoldToSelectState(this)
        state.onPress(onSelect, onHold = null)
        assertEquals(1, selects)
        assertFalse(state.onRelease(), "nothing pending, so the release is not this state's")
    }

    @Test
    fun `turning the setting off puts select back on the press`() = runBlocking {
        HoldToSelect.enabled = false
        val state = HoldToSelectState(this)
        state.onPress(onSelect, onHold)
        assertEquals(1, selects)
        delay(HoldToSelect.TIMEOUT_MS + 100)
        assertEquals(0, holds)
    }

    @Test
    fun `a press arriving after the swallow went stale is a new press`() {
        HoldToSelect.startSwallowingRelease()
        assertTrue(HoldToSelect.isLikelyRepeat())
        HoldToSelect.stopSwallowingRelease()
        assertFalse(HoldToSelect.isLikelyRepeat())
    }

    @Test
    fun `a menu knows whether a held key or a right-click opened it`() {
        ContextMenuInvocation.recordKeyboardInvocation(IntOffset(10, 20))
        assertEquals(IntOffset(10, 20), ContextMenuInvocation.consume())
        assertTrue(ContextMenuInvocation.lastConsumedFromKeyboard, "keyboard menus focus their first row")

        ContextMenuInvocation.recordSecondaryPress(IntOffset(30, 40))
        assertEquals(IntOffset(30, 40), ContextMenuInvocation.consume())
        assertFalse(ContextMenuInvocation.lastConsumedFromKeyboard, "right-click menus follow the cursor")

        // Nothing pending: a menu opened with no position is not a keyboard menu either.
        ContextMenuInvocation.consume()
        assertFalse(ContextMenuInvocation.lastConsumedFromKeyboard)
    }
}
