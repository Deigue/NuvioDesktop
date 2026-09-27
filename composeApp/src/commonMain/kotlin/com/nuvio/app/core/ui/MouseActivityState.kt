package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset

/**
 * Tracks whether mouse-hover-driven focus should be honored. Keyboard navigation marks the
 * mouse inactive so a stationary cursor (or content scrolling under it) can't fight the
 * keyboard's focus; any subsequent physical mouse movement re-activates hover.
 */
internal class MouseActivityState {
    var isMouseActive by mutableStateOf(true)
        private set

    private var lastPosition: Offset? = null
    private var ignoreNextMouseMove = false

    fun onKeyboardNavigation(ignoreNextMouseMove: Boolean = false) {
        isMouseActive = false
        if (ignoreNextMouseMove) this.ignoreNextMouseMove = true
    }

    /**
     * Called on every pointer "move" event, including synthetic ones fired when content
     * scrolls under a stationary cursor. Only re-activates hover if the cursor's actual
     * position changed.
     */
    fun onMouseMoved(position: Offset) {
        if (ignoreNextMouseMove) {
            // Removing a native Swing/WebView surface emits a synthetic pointer re-entry at
            // the stationary cursor. Record it, but do not let it steal focus back from the
            // keyboard selection that caused the surface to disappear.
            ignoreNextMouseMove = false
            lastPosition = position
            return
        }
        val last = lastPosition
        lastPosition = position
        if (last == null || last != position) {
            isMouseActive = true
        }
    }
}

/**
 * [startInactive] is for screens that mount under a stationary cursor (navigating in or back):
 * the first synthetic re-entry is recorded but ignored, so hover can't move focus off the
 * starting or restored position until the mouse genuinely moves.
 */
@Composable
internal fun rememberMouseActivityState(startInactive: Boolean = false): MouseActivityState = remember {
    MouseActivityState().apply {
        if (startInactive) onKeyboardNavigation(ignoreNextMouseMove = true)
    }
}
