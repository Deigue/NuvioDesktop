package com.nuvio.app.features.player.desktop

import com.nuvio.app.features.player.isPlaybackPlaceholderUrl
import com.nuvio.app.features.player.isProviderStatusClipUrl
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The pin/don't-pin decision mirrors libavformat/http.c (`parse_cache_control` + `parse_expires`,
 * FFmpeg 8): a redirect FFmpeg would remember is left to FFmpeg, one it would re-request on every
 * seek gets pinned. Getting this backwards either re-introduces the per-seek resolve (a 429 loop
 * at the debrid API) or pins a CDN token FFmpeg would have refreshed for free.
 */
class PlaybackRedirectResolverTest {

    private val now: ZonedDateTime = ZonedDateTime.of(2026, 9, 14, 19, 0, 0, 0, ZoneOffset.UTC)

    /** Debridio's real header: `s-maxage` is consulted before `max-age`, and 300 > 0 caches. */
    @Test
    fun sharedMaxAgeIsCacheable() {
        assertTrue(
            PlaybackRedirectResolver.ffmpegWouldCacheRedirect(
                cacheControlHeader = "public, max-age=900, s-maxage=300",
                expiresHeader = null,
                now = now,
            ),
        )
    }

    /** AIOStreams' own resolver: `no-store` means FFmpeg re-resolves on every seek — pin it. */
    @Test
    fun noStoreIsNotCacheable() {
        assertFalse(
            PlaybackRedirectResolver.ffmpegWouldCacheRedirect(
                cacheControlHeader = "no-store",
                expiresHeader = null,
                now = now,
            ),
        )
    }

    /** A bare 302 with no cache headers: FFmpeg sets `expires = -1` and never caches it. */
    @Test
    fun missingHeadersAreNotCacheable() {
        assertFalse(PlaybackRedirectResolver.ffmpegWouldCacheRedirect(null, null, now))
        assertFalse(PlaybackRedirectResolver.ffmpegWouldCacheRedirect("public", null, now))
        assertFalse(PlaybackRedirectResolver.ffmpegWouldCacheRedirect("max-age=0", null, now))
    }

    /** `Expires` wins over `Cache-Control`, exactly as in http.c. */
    @Test
    fun expiresTakesPriorityOverCacheControl() {
        assertTrue(
            PlaybackRedirectResolver.ffmpegWouldCacheRedirect(
                cacheControlHeader = "no-store",
                expiresHeader = "Mon, 14 Sep 2026 20:00:00 GMT",
                now = now,
            ),
        )
        assertFalse(
            PlaybackRedirectResolver.ffmpegWouldCacheRedirect(
                cacheControlHeader = "max-age=900",
                expiresHeader = "Mon, 14 Sep 2026 18:00:00 GMT",
                now = now,
            ),
        )
    }

    /** Known byte-serving hosts are never probed: that open would count against their throttle. */
    @Test
    fun directMediaHostsAreSkipped() {
        assertTrue(PlaybackRedirectResolver.isDirectMediaHost("https://nexus-179.neur.tb-cdn.st/dld/abc?token=x"))
        assertTrue(PlaybackRedirectResolver.isDirectMediaHost("https://store-046.wnam.tb-cdn.io/dld/abc?token=x"))
        assertTrue(PlaybackRedirectResolver.isDirectMediaHost("https://sgp1-4.download.real-debrid.com/d/ABC/file.mkv"))
        assertFalse(PlaybackRedirectResolver.isDirectMediaHost("https://addon.debridio.com/play/series/torbox/a/b/c/d"))
        assertFalse(PlaybackRedirectResolver.isDirectMediaHost("https://aio.example.xyz/api/v1/debrid/playback/a/b/c"))
        // Suffix match is on label boundaries: a look-alike host does not qualify.
        assertFalse(PlaybackRedirectResolver.isDirectMediaHost("https://nottb-cdn.st/dld/abc"))
    }

    @Test
    fun hostOfHandlesNonUrls() {
        assertEquals("addon.debridio.com", PlaybackRedirectResolver.hostOf("https://addon.debridio.com/play/x"))
        assertEquals("", PlaybackRedirectResolver.hostOf("C:\\Videos\\file.mkv"))
        assertEquals("", PlaybackRedirectResolver.hostOf("magnet:?xt=urn:btih:abc"))
    }

    /** AIOStreams' status clips (usenet failure, and every `/static/` StaticFiles clip) must be classified, not pinned. */
    @Test
    fun providerStatusClipsAreRecognised() {
        assertTrue(isProviderStatusClipUrl("https://aio.example/api/v1/usenet/abc/download_failed.mp4"))
        assertTrue(isProviderStatusClipUrl("https://aio.example/static/error.mp4?x=1"))
        assertTrue(isProviderStatusClipUrl("https://aio.example/static/429.mp4"))
        assertTrue(isProviderStatusClipUrl("https://aio.example/static/content_proxy_limit_reached.mp4"))
        assertTrue(isProviderStatusClipUrl("https://aio.example/static/downloading.mp4"))
        assertTrue(isPlaybackPlaceholderUrl("https://aio.example/static/500.mp4"))
        assertFalse(isProviderStatusClipUrl("https://aio.example/api/v1/usenet/abc/Friends.S01E03.mkv"))
        assertFalse(isProviderStatusClipUrl("https://cdn.example/dld/abc?token=failed"))
        // A numeric name is only a status clip in AIOStreams' own /static/ directory.
        assertFalse(isProviderStatusClipUrl("https://cdn.example/files/500.mp4"))
        assertFalse(isPlaybackPlaceholderUrl("https://aio.example/api/v1/debrid/playback/a/b/c/d/Show.S01E01.mkv"))
    }

    /** Verdicts are remembered per resolver endpoint, never per link. */
    @Test
    fun endpointKeyIsHostPlusThreeSegments() {
        assertEquals(
            "addon.debridio.com/play/series/torbox",
            PlaybackRedirectResolver.endpointKey("https://addon.debridio.com/play/series/torbox/AAA/BBB/CCC/file.mkv"),
        )
        assertEquals(
            "aio.example/api/v1/debrid",
            PlaybackRedirectResolver.endpointKey("https://aio.example/api/v1/debrid/playback/AAA/BBB"),
        )
        assertEquals(
            "aio.example/api/v1/proxy",
            PlaybackRedirectResolver.endpointKey("https://aio.example/api/v1/proxy/AAA"),
        )
    }
}
