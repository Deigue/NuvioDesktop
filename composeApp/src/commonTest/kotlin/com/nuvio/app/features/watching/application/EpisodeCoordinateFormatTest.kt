package com.nuvio.app.features.watching.application

import kotlin.test.Test
import kotlin.test.assertEquals

class EpisodeCoordinateFormatTest {
    @Test
    fun `consecutive episodes collapse into ranges per season`() {
        val coordinates = (1..8).map { 1 to it } + listOf(1 to 10, 2 to 3, 2 to 1, 2 to 2)
        assertEquals("S1:E1-8,10 S2:E1-3", formatEpisodeCoordinates(coordinates))
    }

    @Test
    fun `single episode and duplicates`() {
        assertEquals("S3:E5", formatEpisodeCoordinates(listOf(3 to 5, 3 to 5)))
    }

    @Test
    fun `unknown coordinates are shown rather than dropped`() {
        assertEquals("S?:E? S1:E2,?", formatEpisodeCoordinates(listOf(null to null, 1 to 2, 1 to null)))
    }
}
