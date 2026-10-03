package com.nuvio.app.features.player.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DirectMediaNetworkTimeoutTest {

    @Test
    fun debridCdnLinksGetTheShortTimeout() {
        val expected = "network-timeout=$DIRECT_MEDIA_NETWORK_TIMEOUT_SECONDS"
        assertEquals(expected, directMediaNetworkTimeoutOption("https://store-046.wnam.tb-cdn.io/dld/abc?token=x"))
        assertEquals(expected, directMediaNetworkTimeoutOption("https://nexus-133.neur.tb-cdn.st/dld/abc?token=x"))
        assertEquals(expected, directMediaNetworkTimeoutOption("https://sgp1-4.download.real-debrid.com/d/ABC/file.mkv"))
    }

    /** A resolver can take 10 s or more to answer, so it keeps mpv's default. */
    @Test
    fun resolversAddonsAndLocalFilesKeepTheDefault() {
        assertNull(directMediaNetworkTimeoutOption("https://stremthru.example/stremio/torz/_/strem/abc"))
        assertNull(directMediaNetworkTimeoutOption("https://aio.example.xyz/api/v1/debrid/playback/a/b/c"))
        assertNull(directMediaNetworkTimeoutOption("C:\\Videos\\episode.mkv"))
        assertNull(directMediaNetworkTimeoutOption("file:///C:/Videos/episode.mkv"))
    }
}
