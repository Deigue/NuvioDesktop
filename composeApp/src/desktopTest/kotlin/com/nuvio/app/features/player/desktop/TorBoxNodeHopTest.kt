package com.nuvio.app.features.player.desktop

import com.nuvio.app.features.player.DesktopRateLimitRecoveryMode
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Shapes measured on 2026-09-26: a 429 from `nexus-133.neur.tb-cdn.st` banned our IP on that node
 * for ~75 min, while the same `/dld/<id>?token=` path answered 206 on `nexus-130` / `nexus-272`.
 */
class TorBoxNodeHopTest {

    private val link = "https://nexus-133.neur.tb-cdn.st/dld/0f1e2d3c-cb02-4be1-9b3e-aabbccddeeff?token=e5%2Babc"

    /** Measured 2026-10-03: this link's token answered 206 on store-042/044/045/076/078 too. */
    private val storeLink = "https://store-046.wnam.tb-cdn.io/dld/0f1e2d3c-cb02-4be1-9b3e-aabbccddeeff?token=e5%2Babc"

    @BeforeTest
    @AfterTest
    fun reset() = TorBoxNodeHop.resetForTest()

    @Test
    fun recognisesOnlyTorBoxNodes() {
        assertEquals(TorBoxNodeHop.Node(TorBoxNodeHop.Family.Nexus, 133, "neur"), TorBoxNodeHop.nodeOf(link))
        assertEquals(
            TorBoxNodeHop.Node(TorBoxNodeHop.Family.Store, 46, "wnam"),
            TorBoxNodeHop.nodeOf(storeLink),
        )
        assertNull(TorBoxNodeHop.nodeOf("https://store-1.torbox.app/dld/x"))
        assertNull(TorBoxNodeHop.nodeOf("https://nexus-133.neur.tb-cdn.st.evil.example/dld/x"))
        assertNull(TorBoxNodeHop.nodeOf("https://stremthru.example/playback/x"))
        assertNull(TorBoxNodeHop.nodeOf("https://store-046.wnam.tb-cdn.io.evil.example/dld/x"))
        assertNull(TorBoxNodeHop.nodeOf("https://nexus-133.neur.tb-cdn.io/dld/x"))
        assertNull(TorBoxNodeHop.nodeOf("https://store-046.wnam.tb-cdn.st/dld/x"))
    }

    @Test
    fun hopKeepsPathAndTokenByteIdentical() {
        assertEquals(
            "https://nexus-130.neur.tb-cdn.st/dld/0f1e2d3c-cb02-4be1-9b3e-aabbccddeeff?token=e5%2Babc",
            TorBoxNodeHop.withHost(link, "nexus-130.neur.tb-cdn.st"),
        )
    }

    @Test
    fun candidatesSkipTheCurrentAndBannedNodesAndPreferKnownGoodOnes() {
        val now = 1_000_000L
        TorBoxNodeHop.markWorking("https://nexus-272.neur.tb-cdn.st/dld/y", nowMs = now)
        TorBoxNodeHop.markWorking("https://nexus-9.weur.tb-cdn.st/dld/y", nowMs = now) // other region
        TorBoxNodeHop.markBanned("https://nexus-132.neur.tb-cdn.st/dld/z", nowMs = now)

        val candidates = TorBoxNodeHop.candidates(link, nowMs = now)

        assertEquals("nexus-272.neur.tb-cdn.st", candidates.first())
        assertFalse("nexus-133.neur.tb-cdn.st" in candidates)
        assertFalse("nexus-132.neur.tb-cdn.st" in candidates)
        assertFalse(candidates.any { "weur" in it })
        assertTrue("nexus-134.neur.tb-cdn.st" in candidates)
        assertTrue(candidates.size <= TorBoxNodeHop.MAX_PROBES)
    }

    @Test
    fun storeCandidatesKeepTheirPaddingFamilyAndRegion() {
        val now = 2_000_000L
        TorBoxNodeHop.markWorking("https://store-076.wnam.tb-cdn.io/dld/y", nowMs = now)
        TorBoxNodeHop.markWorking("https://nexus-46.wnam.tb-cdn.st/dld/y", nowMs = now) // other family
        TorBoxNodeHop.markWorking("https://store-045.weur.tb-cdn.io/dld/y", nowMs = now) // other region

        val candidates = TorBoxNodeHop.candidates(storeLink, nowMs = now)

        assertEquals("store-076.wnam.tb-cdn.io", candidates.first())
        assertTrue("store-045.wnam.tb-cdn.io" in candidates)
        assertTrue("store-047.wnam.tb-cdn.io" in candidates)
        assertTrue("store-042.wnam.tb-cdn.io" in candidates)
        assertFalse("store-046.wnam.tb-cdn.io" in candidates)
        assertTrue(candidates.all { it.matches(Regex("""store-\d{3}\.wnam\.tb-cdn\.io""")) })
        assertEquals(
            "https://store-045.wnam.tb-cdn.io/dld/0f1e2d3c-cb02-4be1-9b3e-aabbccddeeff?token=e5%2Babc",
            TorBoxNodeHop.withHost(storeLink, "store-045.wnam.tb-cdn.io"),
        )
    }

    @Test
    fun stallBanIsShorterThanARateLimitBanAndNeverShortensOne() {
        val now = 7_000_000L
        TorBoxNodeHop.markStalled(storeLink, nowMs = now)
        assertTrue(TorBoxNodeHop.isBanned(storeLink, nowMs = now + TorBoxNodeHop.STALL_BAN_MS - 1))
        assertFalse(TorBoxNodeHop.isBanned(storeLink, nowMs = now + TorBoxNodeHop.STALL_BAN_MS))

        TorBoxNodeHop.markBanned(link, nowMs = now)
        TorBoxNodeHop.markStalled(link, nowMs = now + 1)
        assertTrue(TorBoxNodeHop.isBanned(link, nowMs = now + TorBoxNodeHop.STALL_BAN_MS + 60_000L))
    }

    /** The bridge's wording when FFmpeg gave up without ever seeing an HTTP status. */
    @Test
    fun onlyStatuslessLoadAndSeekFailuresCountAsStalls() {
        assertTrue(TorBoxNodeHop.looksLikeNodeStall("Playback loading failed: loading failed"))
        assertTrue(TorBoxNodeHop.looksLikeNodeStall("Playback loading failed"))
        assertTrue(TorBoxNodeHop.looksLikeNodeStall("Playback seek failed: Seek failed (to 1493062008, size -40)"))
        assertFalse(TorBoxNodeHop.looksLikeNodeStall("https: HTTP error 429 Too Many Requests"))
        assertFalse(TorBoxNodeHop.looksLikeNodeStall("https: HTTP error 403 Forbidden; Seek failed (to 1, size -1)"))
        assertFalse(TorBoxNodeHop.looksLikeNodeStall("Failed to recognize file format."))
        assertFalse(TorBoxNodeHop.looksLikeNodeStall(null))
    }

    /** Nothing is written off for good: a ban lapses after its window. */
    @Test
    fun banExpires() {
        val now = 5_000_000L
        TorBoxNodeHop.markBanned(link, nowMs = now)
        assertTrue(TorBoxNodeHop.isBanned(link, nowMs = now + 60_000L))
        assertFalse(TorBoxNodeHop.isBanned(link, nowMs = now + TorBoxNodeHop.BAN_MS))
    }

    @Test
    fun nonTorBoxHostsAreNeverBanned() {
        val other = "https://cdn.real-debrid.example/d/abc"
        TorBoxNodeHop.markBanned(other)
        assertFalse(TorBoxNodeHop.isBanned(other))
    }

    @Test
    fun recoveryModeDecidesWhoHandlesTheIncident() {
        assertFalse(SeekRateLimitRecovery.shouldReconnect(DesktopRateLimitRecoveryMode.Off, streamFailoverEnabled = false))
        assertFalse(SeekRateLimitRecovery.shouldReconnect(DesktopRateLimitRecoveryMode.Off, streamFailoverEnabled = true))
        assertFalse(SeekRateLimitRecovery.shouldReconnect(DesktopRateLimitRecoveryMode.PreferFailover, streamFailoverEnabled = true))
        assertTrue(SeekRateLimitRecovery.shouldReconnect(DesktopRateLimitRecoveryMode.PreferFailover, streamFailoverEnabled = false))
        assertTrue(SeekRateLimitRecovery.shouldReconnect(DesktopRateLimitRecoveryMode.PreferReconnect, streamFailoverEnabled = true))
    }
}
