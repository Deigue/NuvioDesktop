package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class PlaybackErrorLogTest {

    @Test
    fun urlsKeepOnlyTheirHost() {
        assertEquals(
            "Failed to open https://store-046.wnam.tb-cdn.io/<redacted>.",
            redactPlaybackLogText("Failed to open https://store-046.wnam.tb-cdn.io/dld/0f1e2d3c?token=e5%2Babc."),
        )
        val redacted = redactPlaybackLogText(
            "https: HTTP error 403 Forbidden at https://a.example/x?token=SECRET and http://b.example:8080/p#f",
        )
        assertEquals(
            "https: HTTP error 403 Forbidden at https://a.example/<redacted> and http://b.example/<redacted>",
            redacted,
        )
        assertFalse("SECRET" in redacted)
    }

    @Test
    fun textWithoutUrlsIsUnchanged() {
        assertEquals("Playback loading failed: loading failed", redactPlaybackLogText("Playback loading failed: loading failed"))
        assertEquals(
            "Playback seek failed: Seek failed (to 1493062008, size -40)",
            redactPlaybackLogText("Playback seek failed: Seek failed (to 1493062008, size -40)"),
        )
    }

    @Test
    fun hostIsOnlyTakenFromRealUrls() {
        assertEquals("stremthru.example", playbackLogHost("https://StremThru.example/stremio/torz/x?token=y"))
        assertNull(playbackLogHost("C:\\Videos\\episode.mkv"))
        assertNull(playbackLogHost(null))
    }
}
