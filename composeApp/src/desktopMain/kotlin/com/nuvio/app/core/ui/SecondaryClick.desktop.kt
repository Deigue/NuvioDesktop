package com.nuvio.app.core.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.node.requireLayoutCoordinates
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.round

internal actual fun Modifier.secondaryClick(onClick: (() -> Unit)?): Modifier {
    if (onClick == null) return this
    return this then SecondaryClickElement(onClick)
}

/**
 * A node rather than `pointerInput` so it can read its own layout coordinates at press time: the
 * cursor's window position is recorded in [ContextMenuInvocation] before [onClick] runs, which is
 * how a right-click-opened action menu knows where to appear.
 */
private data class SecondaryClickElement(val onClick: () -> Unit) : ModifierNodeElement<SecondaryClickNode>() {
    override fun create() = SecondaryClickNode(onClick)

    override fun update(node: SecondaryClickNode) {
        node.onClick = onClick
    }
}

private class SecondaryClickNode(var onClick: () -> Unit) : Modifier.Node(), PointerInputModifierNode {
    @OptIn(ExperimentalComposeUiApi::class)
    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) {
        if (pass != PointerEventPass.Main) return
        if (pointerEvent.type != PointerEventType.Press || pointerEvent.button != PointerButton.Secondary) return
        pointerEvent.changes.firstOrNull()?.let { change ->
            runCatching { requireLayoutCoordinates().localToWindow(change.position).round() }
                .getOrNull()
                ?.let(ContextMenuInvocation::recordSecondaryPress)
        }
        pointerEvent.changes.forEach { change -> change.consume() }
        onClick()
    }

    override fun onCancelPointerInput() = Unit
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
