package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Hold-to-select: holding the select key (Enter, or a controller's A, which is sent as the same
 * key) on a focused item opens its actions — the keyboard and controller twin of right-click.
 *
 * Telling a tap from a hold means a tap can only be acted on once the key comes back up, so on a
 * screen that uses this, select fires on release instead of on press. That is the whole cost, and
 * it applies only where the focused item actually has a hold action; [enabled] turns it off
 * altogether and puts select back on the press. Mirrored from `ThemeSettingsRepository` so key
 * handlers can read it without depending on the settings layer, the same way [WasdNavigation] is.
 */
object HoldToSelect {
    @Volatile
    var enabled: Boolean = true

    /** How long the key has to stay down. Matches Compose's own long-press timeout on desktop. */
    const val TIMEOUT_MS = 500L

    /**
     * Set when a hold fires. The press that opened the actions is still physically down, and its
     * key-repeats and release must not reach whatever the actions opened — a focused menu row
     * clicks on Enter's release. The desktop key dispatcher swallows the select key until it sees
     * the release, then clears this.
     */
    @Volatile
    var swallowSelectUntilRelease: Boolean = false
        private set

    @Volatile
    private var swallowStartedAt: TimeMark? = null

    internal fun startSwallowingRelease() {
        swallowStartedAt = TimeSource.Monotonic.markNow()
        swallowSelectUntilRelease = true
    }

    fun stopSwallowingRelease() {
        swallowSelectUntilRelease = false
        swallowStartedAt = null
    }

    /**
     * Whether a select-key press arriving now is a key-repeat of the held key rather than a fresh
     * press. A release that went somewhere this app never saw (the window lost focus mid-hold)
     * would otherwise leave the next real press swallowed; repeats arrive well inside this window
     * at any keyboard repeat rate, so anything later is a new press.
     */
    fun isLikelyRepeat(): Boolean {
        val started = swallowStartedAt ?: return false
        return started.elapsedNow().inWholeMilliseconds < STALE_SWALLOW_MS
    }

    /** Called for each swallowed repeat so a long hold keeps counting as one press. */
    fun onRepeatSwallowed() {
        swallowStartedAt = TimeSource.Monotonic.markNow()
    }

    private const val STALE_SWALLOW_MS = 1_500L
}

/**
 * The card that keyboard focus currently highlights, so a hold can open its actions beside it the
 * way a right-click opens them under the cursor. Cards register while highlighted; see
 * `posterCardClickable`.
 */
internal object HighlightedCardAnchor {
    // A stack, not a single slot: TV mode's row transition keeps the outgoing row composed (and
    // its card highlighted) while the incoming one registers, and the outgoing card leaving must
    // not take the incoming card's registration with it. The newest registration wins.
    private val registered = ArrayList<() -> Unit>()

    fun register(prepare: () -> Unit) {
        registered += prepare
    }

    fun unregister(prepare: () -> Unit) {
        registered.removeAll { it === prepare }
    }

    /** Stashes the highlighted card's zoom anchor and menu position for the long-press handler. */
    fun prepareForHold() {
        registered.lastOrNull()?.invoke()
    }
}

/**
 * Per-screen state for [HoldToSelect]. Feed it every select-key event, key down and key up.
 */
class HoldToSelectState internal constructor(private val scope: CoroutineScope) {
    private var holdJob: Job? = null
    private var pendingSelect: (() -> Unit)? = null

    /**
     * Handles one select-key event. [onSelect] is the tap; [onHold] is the hold, or null when the
     * focused item has none, in which case select fires on the press exactly as before. Returns
     * whether the event was consumed.
     */
    fun handle(event: KeyEvent, onSelect: () -> Unit, onHold: (() -> Unit)?): Boolean =
        when (event.type) {
            KeyEventType.KeyDown -> onPress(onSelect, onHold)
            KeyEventType.KeyUp -> onRelease()
            else -> false
        }

    internal fun onPress(onSelect: () -> Unit, onHold: (() -> Unit)?): Boolean {
        when {
            // Key-repeat of a press already being timed.
            pendingSelect != null -> Unit
            onHold == null || !HoldToSelect.enabled -> onSelect()
            else -> {
                pendingSelect = onSelect
                holdJob = scope.launch {
                    delay(HoldToSelect.TIMEOUT_MS)
                    pendingSelect = null
                    holdJob = null
                    HoldToSelect.startSwallowingRelease()
                    HighlightedCardAnchor.prepareForHold()
                    onHold()
                }
            }
        }
        return true
    }

    internal fun onRelease(): Boolean {
        // Not a press this state is timing: leave the release to whoever wants it.
        val select = pendingSelect ?: return false
        holdJob?.cancel()
        holdJob = null
        pendingSelect = null
        select()
        return true
    }

    internal fun cancel() {
        holdJob?.cancel()
        holdJob = null
        pendingSelect = null
    }
}

@Composable
fun rememberHoldToSelectState(): HoldToSelectState {
    val scope = rememberCoroutineScope()
    val state = remember(scope) { HoldToSelectState(scope) }
    DisposableEffect(state) { onDispose { state.cancel() } }
    return state
}
