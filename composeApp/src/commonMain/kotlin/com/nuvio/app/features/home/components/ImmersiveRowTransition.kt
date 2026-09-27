package com.nuvio.app.features.home.components

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.home.HomeTvRowTransition
import com.nuvio.app.features.home.IMMERSIVE_SHELF_BOTTOM_PADDING_DP

/**
 * Shelf-level transition for a TV Mode row change, per the user's [HomeTvRowTransition]: a
 * cross-fade of the whole row (title, dots, posters), or a hard cut. Off returns
 * `EnterTransition.None` so `AnimatedContent` swaps in the same frame it would have without the
 * wrapper. Both fades are gradual, so the swap never presents a one-frame luminance step to a VRR
 * panel.
 *
 * The nudge is deliberately *not* here. It is applied to the row body alone via
 * [immersiveRowBodyEnter] / [immersiveRowBodyExit] and `Modifier.animateEnterExit`, so the posters
 * move and the title stays put — a title that shifts while you are reading it is the one thing the
 * motion must not do.
 */
internal fun immersiveRowTransition(mode: HomeTvRowTransition): ContentTransform {
    if (mode == HomeTvRowTransition.Off) {
        return ContentTransform(EnterTransition.None, ExitTransition.None, sizeTransform = null)
    }
    // No size transform at all: the shelf is a fixed-height box, so there is no size to animate,
    // and a size modifier would clip a focused poster's scale-up at the row bounds mid-transition.
    //
    // A dip rather than a cross-fade: the incoming row waits until the outgoing one is mostly gone.
    // Two poster rows in identical slots cross-faded into each other so evenly that the change
    // barely registered as a transition at all.
    return ContentTransform(
        targetContentEnter = fadeIn(
            tween(
                durationMillis = ImmersiveRowFadeInMs,
                delayMillis = ImmersiveRowFadeInDelayMs,
                easing = LinearOutSlowInEasing,
            ),
        ),
        initialContentExit = fadeOut(tween(ImmersiveRowFadeOutMs, easing = FastOutLinearInEasing)),
        sizeTransform = null,
    )
}

/**
 * Root modifier for each row inside the shelf's `AnimatedContent`. Carries the shelf's bottom
 * padding, which the shelf box itself therefore no longer applies.
 *
 * The fade composites each row through an offscreen layer the size of the row, and a translucent
 * offscreen layer clips. Continue Watching and collection cards are sized from an estimated header
 * height and overhang their row by a few pixels, so every fade shaved their bottom edge off until
 * it finished. Moving the padding inside the row changes nothing about where anything sits, but
 * grows the layer over that overhang.
 *
 * Not `CompositingStrategy.ModulateAlpha` to avoid the layer: on desktop that alpha does not reach
 * the cards' own graphics layers (focus scale, artwork), so rows never faded and the outgoing and
 * incoming rows drew over each other at full strength.
 */
internal fun Modifier.immersiveRowFadeBounds(): Modifier =
    padding(bottom = IMMERSIVE_SHELF_BOTTOM_PADDING_DP.dp)

/**
 * The incoming row body's slide, for the Fade + nudge mode. Rises into place when [forward]
 * (moving to a later row, i.e. "down" the list), drops in otherwise. The shelf's fade is shorter
 * than this slide on purpose: a body that is still transparent while it travels shows no direction
 * at all, only a settle at the end.
 */
internal fun immersiveRowBodyEnter(forward: Boolean): EnterTransition =
    slideInVertically(
        animationSpec = tween(ImmersiveRowSlideMs, easing = ImmersiveRowSlideEasing),
        initialOffsetY = { if (forward) slideDistance(it) else -slideDistance(it) },
    )

/** The outgoing row body's slide: the mirror of [immersiveRowBodyEnter], over the fade-out. */
internal fun immersiveRowBodyExit(forward: Boolean): ExitTransition =
    slideOutVertically(
        animationSpec = tween(ImmersiveRowFadeOutMs, easing = ImmersiveRowSlideEasing),
        targetOffsetY = { if (forward) -exitSlideDistance(it) else exitSlideDistance(it) },
    )

/**
 * Which way the shelf is travelling, derived from successive row indices. `AnimatedContent`'s own
 * `initialState`/`targetState` are only in scope inside `transitionSpec`; the body nudge is applied
 * inside the content lambda, which sees only its own row index, so the direction is tracked here
 * and read from both places.
 */
internal class ImmersiveRowDirection {
    private var lastIndex: Int? = null

    /** True when the most recent change moved to a later row. Defaults to forward. */
    var forward: Boolean = true
        private set

    /** Call with the current row index on every composition; updates [forward] on a change. */
    fun observe(index: Int) {
        val last = lastIndex
        if (last != null && index != last) forward = index > last
        lastIndex = index
    }
}

private fun slideDistance(fullHeight: Int): Int = (fullHeight * ImmersiveRowSlideFraction).toInt()

/**
 * The outgoing row travels half as far as the incoming one. Moving down a row it slides *up*, toward
 * the header, and at the full distance its posters crossed the title text while fading out.
 */
private fun exitSlideDistance(fullHeight: Int): Int = slideDistance(fullHeight) / 2

/** Fraction of the row body height the posters travel during a nudge — a short hop, not a page turn. */
private const val ImmersiveRowSlideFraction = 0.035f
private const val ImmersiveRowSlideMs = 480
private const val ImmersiveRowFadeInMs = 260
// The incoming row starts once the outgoing one is mostly faded; see [immersiveRowTransition].
private const val ImmersiveRowFadeInDelayMs = 120
private const val ImmersiveRowFadeOutMs = 170

/**
 * Gentle S-curve: a soft start, most of the travel in the middle, and a long settle. Softer at both
 * ends than FastOutSlowIn, which still launches quickly enough to feel like a snap at this size.
 */
private val ImmersiveRowSlideEasing = CubicBezierEasing(0.33f, 0f, 0.1f, 1f)
