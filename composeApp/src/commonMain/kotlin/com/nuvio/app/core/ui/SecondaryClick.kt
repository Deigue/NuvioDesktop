package com.nuvio.app.core.ui

import androidx.compose.ui.Modifier

internal expect fun Modifier.secondaryClick(onClick: (() -> Unit)?): Modifier

/**
 * Right-click that nothing underneath claimed. Runs on the final pass and skips any press a child
 * consumed — every right-click action in the app goes through [secondaryClick] or consumes the
 * same way — so wrapping a whole screen hands it only right-clicks on empty space.
 * [enabled] is read at click time, not composition time.
 */
internal expect fun Modifier.unclaimedSecondaryClick(enabled: () -> Boolean, onClick: () -> Unit): Modifier
