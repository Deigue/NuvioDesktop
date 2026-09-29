package com.nuvio.app.core.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AddonUrlRedactionTest {
    @Test
    fun `AIOStreams config and uuid are elided`() {
        val url = "https://aio.example.xyz/stremio/23a8766f-5800-4c1b-9a4e-0b7d3c1f2e11/eyJjb25maWciOiJzZWNyZXQifQ/stream/series/tt0121955:1:1.json"
        assertEquals("https://aio.example.xyz/…/stream/series/tt0121955:1:1.json", redactAddonUrl(url))
    }

    @Test
    fun `Torrentio debrid key is elided from a manifest url`() {
        val url = "https://torrentio.strem.fun/providers=yts|realdebrid=ABCDEF123456/manifest.json"
        val redacted = redactAddonUrl(url)
        assertEquals("https://torrentio.strem.fun/…/manifest.json", redacted)
        assertFalse("ABCDEF" in redacted)
    }

    @Test
    fun `query, fragment and userinfo are dropped`() {
        assertEquals(
            "https://host.example/…/meta/movie/tt1.json",
            redactAddonUrl("https://user:pw@host.example/cfg/meta/movie/tt1.json?key=secret#x"),
        )
        assertEquals("https://host.example/manifest.json", redactAddonUrl("https://host.example/manifest.json"))
    }

    @Test
    fun `addon and plugin ids keep their readable parts`() {
        assertEquals(
            "addon:com.aiostreams:https://aio.example/…/manifest.json",
            redactAddonId("addon:com.aiostreams:https://aio.example/stremio/uuid/cfg/manifest.json"),
        )
        assertEquals(
            "https://repo.example/…/manifest.json:scraper-id",
            redactAddonId("https://repo.example/user/secret/manifest.json:scraper-id"),
        )
        assertEquals("plain-id", redactAddonId("plain-id"))
    }
}
