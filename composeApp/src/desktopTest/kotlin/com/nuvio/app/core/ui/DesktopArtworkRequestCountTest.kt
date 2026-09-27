package com.nuvio.app.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import coil3.PlatformContext
import coil3.request.ImageRequest
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The size resolver publishes its bucket as snapshot state the request model can be keyed on. Keyed
 * naively, the first composition builds a request layout has not answered yet and the first layout
 * pass then rebuilds it - two starts and a cancel for a card that never moved. These pin both ends:
 * a settled card loads once, and a card that actually changes bucket still re-requests.
 */
@OptIn(ExperimentalTestApi::class)
class DesktopArtworkRequestCountTest {
    @Test
    fun `one settled card starts one load`() = runComposeUiTest {
        val file = Files.createTempFile("nuvio-request-count", ".png").toFile()
        ImageIO.write(BufferedImage(800, 1200, BufferedImage.TYPE_INT_RGB), "png", file)
        val starts = AtomicInteger()
        val cancels = AtomicInteger()
        val successes = AtomicInteger()
        val decoded = Collections.synchronizedList(mutableListOf<String>())
        try {
            val request = ImageRequest.Builder(PlatformContext.INSTANCE)
                .data(file)
                .listener(
                    onStart = { starts.incrementAndGet() },
                    onCancel = { cancels.incrementAndGet() },
                    onSuccess = { _, result ->
                        successes.incrementAndGet()
                        decoded += "${result.image.width}x${result.image.height}/${result.dataSource}"
                    },
                )
                .build()
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    Box(Modifier.requiredSize(210.dp, 315.dp)) {
                        NuvioAsyncImage(
                            model = request,
                            contentDescription = null,
                            modifier = Modifier.requiredSize(210.dp, 315.dp),
                            contentScale = ContentScale.Crop,
                        )
                    }
                }
            }
            waitUntil(timeoutMillis = 10_000) { successes.get() >= 1 }
            repeat(5) { waitForIdle() }
            println("NUVIO-PROBE starts=${starts.get()} cancels=${cancels.get()} successes=${successes.get()} decoded=$decoded")
            assertEquals(1, starts.get(), "requests started for one settled card")
            assertEquals(0, cancels.get(), "requests cancelled for one settled card")
        } finally {
            file.delete()
        }
    }

    @Test
    fun `a later bucket change still re-requests at the new size`() = runComposeUiTest {
        val file = Files.createTempFile("nuvio-request-resize", ".png").toFile()
        ImageIO.write(BufferedImage(800, 1200, BufferedImage.TYPE_INT_RGB), "png", file)
        val successes = AtomicInteger()
        val decoded = Collections.synchronizedList(mutableListOf<String>())
        try {
            val request = ImageRequest.Builder(PlatformContext.INSTANCE)
                .data(file)
                .listener(onSuccess = { _, result ->
                    successes.incrementAndGet()
                    decoded += "${result.image.width}x${result.image.height}"
                })
                .build()
            val cardWidth = mutableStateOf(210)
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    NuvioAsyncImage(
                        model = request,
                        contentDescription = null,
                        modifier = Modifier.requiredSize(cardWidth.value.dp, (cardWidth.value * 1.5f).dp),
                        contentScale = ContentScale.Crop,
                    )
                }
            }
            waitUntil(timeoutMillis = 10_000) { successes.get() >= 1 }
            runOnIdle { cardWidth.value = 420 }
            waitUntil(timeoutMillis = 10_000) { successes.get() >= 2 }
            println("NUVIO-PROBE-RESIZE decoded=$decoded")
            assertEquals(listOf("210x315", "420x630"), decoded.toList())
        } finally {
            file.delete()
        }
    }
}
