package com.nuvio.app.features.home.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.home.HomeTvRowTransition
import kotlin.test.Test

/**
 * The TV row change is a dip: the incoming row's fade-in is delayed behind the outgoing row's
 * fade-out. Pinned so a timing change cannot silently turn Fade into a hard cut, or leave the
 * outgoing row on screen.
 */
@OptIn(ExperimentalTestApi::class)
class ImmersiveRowFadeTest {

    private fun rowsDuringChange(mode: HomeTvRowTransition, advanceMs: Long): Int {
        var count = -1
        runComposeUiTest {
            var row by mutableIntStateOf(0)
            mainClock.autoAdvance = false
            setContent {
                AnimatedContent(
                    targetState = row,
                    transitionSpec = { immersiveRowTransition(mode) },
                    label = "test_row",
                ) { index ->
                    Box(modifier = Modifier.immersiveRowFadeBounds().testTag("row").size(10.dp)) {
                        Box(Modifier.testTag("row$index"))
                    }
                }
            }
            mainClock.advanceTimeBy(100)
            row = 1
            mainClock.advanceTimeBy(advanceMs)
            count = onAllNodesWithTag("row").fetchSemanticsNodes().size
        }
        return count
    }

    @Test
    fun `fade keeps the outgoing row while it fades out`() {
        kotlin.test.assertEquals(2, rowsDuringChange(HomeTvRowTransition.Fade, advanceMs = 80))
    }

    @Test
    fun `fade removes the outgoing row once finished`() {
        kotlin.test.assertEquals(1, rowsDuringChange(HomeTvRowTransition.Fade, advanceMs = 1_000))
    }

    @Test
    fun `off swaps immediately`() {
        kotlin.test.assertEquals(1, rowsDuringChange(HomeTvRowTransition.Off, advanceMs = 32))
    }

    @Test
    fun `a card with its own graphics layer fades with the row`() {
        // Every poster card draws inside its own graphicsLayer (focus scale, artwork). A fade that
        // does not reach child layers leaves both rows drawn at full strength over each other.
        var red = -1f
        runComposeUiTest {
            var row by mutableIntStateOf(0)
            mainClock.autoAdvance = false
            setContent {
                Box(Modifier.testTag("stage").size(20.dp).background(Color.Black)) {
                    AnimatedContent(
                        targetState = row,
                        transitionSpec = { immersiveRowTransition(HomeTvRowTransition.Fade) },
                        label = "test_row",
                    ) { index ->
                        Box(Modifier.immersiveRowFadeBounds().size(20.dp)) {
                            if (index == 0) {
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .graphicsLayer { scaleX = 1.001f }
                                        .background(Color.White),
                                )
                            }
                        }
                    }
                }
            }
            mainClock.advanceTimeBy(100)
            row = 1
            // Mid fade-out; the incoming (empty) row is still held back by its delay.
            mainClock.advanceTimeBy(96)
            val pixels = onNodeWithTag("stage").captureToImage().toPixelMap()
            red = pixels[pixels.width / 2, 2].red
        }
        kotlin.test.assertTrue(red in 0.1f..0.9f, "card should be part-faded mid-transition, red=$red")
    }
}
