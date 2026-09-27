package com.nuvio.app.core.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Right-click-to-go-back wraps whole screens with [unclaimedSecondaryClick]; it must fire only on
 * space nothing else claims. Pinned per claimant because each relies on Compose consuming the press.
 */
@OptIn(ExperimentalTestApi::class)
class UnclaimedSecondaryClickTest {

    private class Counts {
        var back = 0
        var poster = 0
    }

    @androidx.compose.runtime.Composable
    private fun Screen(counts: Counts, enabled: Boolean = true) {
        var text by remember { mutableStateOf("query") }
        Column(
            modifier = Modifier
                .size(300.dp)
                .unclaimedSecondaryClick(enabled = { enabled }, onClick = { counts.back++ }),
        ) {
            Box(
                modifier = Modifier
                    .testTag("poster")
                    .size(80.dp)
                    .combinedClickable(onClick = {}, onLongClick = { counts.poster++ })
                    .secondaryClick { counts.poster++ },
            )
            Box(modifier = Modifier.testTag("empty").size(80.dp))
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.testTag("field").size(120.dp, 40.dp),
            )
        }
    }

    @Test
    fun `right click on empty space goes back`() = runComposeUiTest {
        val counts = Counts()
        setContent { Screen(counts) }

        onNodeWithTag("empty").performMouseInput { rightClick() }
        waitForIdle()

        assertEquals(1, counts.back)
    }

    @Test
    fun `right click on a poster runs its action and does not go back`() = runComposeUiTest {
        val counts = Counts()
        setContent { Screen(counts) }

        onNodeWithTag("poster").performMouseInput { rightClick() }
        waitForIdle()

        assertEquals(1, counts.poster)
        assertEquals(0, counts.back)
    }

    @Test
    fun `right click in a text field does not go back`() = runComposeUiTest {
        val counts = Counts()
        setContent { Screen(counts) }

        onNodeWithTag("field").performMouseInput { rightClick() }
        waitForIdle()

        assertEquals(0, counts.back)
    }

    @Test
    fun `disabled does nothing`() = runComposeUiTest {
        val counts = Counts()
        setContent { Screen(counts, enabled = false) }

        onNodeWithTag("empty").performMouseInput { rightClick() }
        waitForIdle()

        assertEquals(0, counts.back)
    }
}
