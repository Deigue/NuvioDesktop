package com.nuvio.app.core.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput

@OptIn(ExperimentalComposeUiApi::class)
internal actual fun Modifier.secondaryClick(onClick: (() -> Unit)?): Modifier {
    if (onClick == null) return this

    return pointerInput(onClick) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                if (event.type == PointerEventType.Press && event.button == PointerButton.Secondary) {
                    event.changes.forEach { change -> change.consume() }
                    onClick()
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
internal actual fun Modifier.unclaimedSecondaryClick(enabled: () -> Boolean, onClick: () -> Unit): Modifier =
    pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Final)
                if (event.type != PointerEventType.Press || event.button != PointerButton.Secondary) continue
                if (event.changes.any { it.isConsumed }) continue
                if (!enabled()) continue
                event.changes.forEach { change -> change.consume() }
                onClick()
            }
        }
    }
