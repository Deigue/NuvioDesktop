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

    @BeforeTest
    @AfterTest
    fun reset() = TorBoxNodeHop.resetForTest()

    @Test
    fun recognisesOnlyTorBoxNodes() {
        assertEquals(TorBoxNodeHop.Node(133, "neur"), TorBoxNodeHop.nodeOf(link))
        assertNull(TorBoxNodeHop.nodeOf("https://store-1.torbox.app/dld/x"))
        assertNull(TorBoxNodeHop.nodeOf("https://nexus-133.neur.tb-cdn.st.evil.example/dld/x"))
        assertNull(TorBoxNodeHop.nodeOf("https://stremthru.example/playback/x"))
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
