package com.nuvio.app.features.watchprogress

import com.nuvio.app.features.watching.domain.isReleasedBy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The regression these cover: "Stuart Fails to Save the Universe" S1E7 counted down as either
 * "In 4 days" or "In 5 days" on the same afternoon, depending on whether the TMDB enrichment
 * request had timed out. TMDB said `2026-09-03` (the US network date); the addon said
 * `2026-09-04T01:00:00.000Z` (the same 9pm-ET drop, as an instant).
 */
class ReleaseInstantTest {

    @Test
    fun `date-only release pins to local midnight`() {
        val resolved = resolveReleaseInstant("2026-09-03")

        assertEquals("2026-09-03", resolved?.localIsoDate)
        assertEquals(false, resolved?.hasTimeOfDay)
        assertEquals(CurrentDateProvider.startOfLocalDayEpochMs("2026-09-03"), resolved?.epochMs)
    }

    @Test
    fun `timestamped release resolves to the local day it lands on`() {
        val resolved = resolveReleaseInstant("2026-09-04T01:00:00.000Z")

        assertEquals(1788483600000L, resolved?.epochMs)
        assertEquals(true, resolved?.hasTimeOfDay)
        // Whatever the test machine's zone, the local date must be the one that instant falls on —
        // the old code took substringBefore('T') and called it Sept 4 everywhere.
        assertEquals(CurrentDateProvider.localIsoDateAt(1788483600000L), resolved?.localIsoDate)
    }

    @Test
    fun `noon-utc placeholder is read as the network's date with no time of day`() {
        // AIOMetadata pins the date to 12:00Z when it cannot resolve the origin timezone (every UK
        // show, via TVDB's `gbr`). Counting down to it put a 9pm BST premiere at 8am ET.
        for (raw in listOf("2026-09-17T12:00:00.000Z", "2026-09-17T12:00:00Z")) {
            val resolved = resolveReleaseInstant(raw)

            assertEquals("2026-09-17", resolved?.localIsoDate, raw)
            assertEquals(false, resolved?.hasTimeOfDay, raw)
            assertEquals(CurrentDateProvider.startOfLocalDayEpochMs("2026-09-17"), resolved?.epochMs, raw)
        }
    }

    @Test
    fun `noon in an offset form or off by a second is a real air time`() {
        assertEquals(true, resolveReleaseInstant("2026-09-17T13:00:00+01:00")?.hasTimeOfDay)
        assertEquals(true, resolveReleaseInstant("2026-09-17T12:00:01.000Z")?.hasTimeOfDay)
        assertEquals(true, resolveReleaseInstant("2026-09-17T12:30:00.000Z")?.hasTimeOfDay)
    }

    @Test
    fun `tmdb air date wins over the noon-utc placeholder`() {
        assertEquals(
            "2026-09-17",
            preferPreciseReleaseDate(
                addonReleased = "2026-09-17T12:00:00.000Z",
                tmdbAirDate = "2026-09-17",
            ),
        )
    }

    @Test
    fun `placeholder is rebuilt from the tvdb slot in the origin country's zone`() {
        // All Creatures Great & Small: Channel 5, Thursdays 21:00, TVDB country "gbr". Sept is BST.
        assertEquals(
            "2026-09-17T20:00:00Z",
            repairPlaceholderAirTime("2026-09-17T12:00:00.000Z", airsTime = "21:00", originCountry = "gbr"),
        )
        // Same slot in January is GMT.
        assertEquals(
            "2026-01-15T21:00:00Z",
            repairPlaceholderAirTime("2026-01-15T12:00:00.000Z", airsTime = "21:00", originCountry = "GBR"),
        )
        // A US slot crosses midnight UTC, and the calendar date is the network's, not UTC's.
        assertEquals(
            "2026-09-18T01:00:00Z",
            repairPlaceholderAirTime("2026-09-17T12:00:00.000Z", airsTime = "9:00 PM", originCountry = "usa"),
        )
        assertEquals(true, resolveReleaseInstant("2026-09-17T20:00:00Z")?.hasTimeOfDay)
    }

    @Test
    fun `placeholder stays when tvdb cannot place the slot`() {
        assertNull(repairPlaceholderAirTime("2026-09-17T12:00:00.000Z", airsTime = null, originCountry = "gbr"))
        assertNull(repairPlaceholderAirTime("2026-09-17T12:00:00.000Z", airsTime = "", originCountry = "gbr"))
        assertNull(repairPlaceholderAirTime("2026-09-17T12:00:00.000Z", airsTime = "21:00", originCountry = null))
        assertNull(repairPlaceholderAirTime("2026-09-17T12:00:00.000Z", airsTime = "21:00", originCountry = "xyz"))
        // Only the placeholder is ever touched — a real timestamp or a bare date is left alone.
        assertNull(repairPlaceholderAirTime("2026-09-17T20:00:00.000Z", airsTime = "21:00", originCountry = "gbr"))
        assertNull(repairPlaceholderAirTime("2026-09-17", airsTime = "21:00", originCountry = "gbr"))
        assertNull(repairPlaceholderAirTime(null, airsTime = "21:00", originCountry = "gbr"))
    }

    @Test
    fun `tvdb airsTime spellings`() {
        assertEquals(21 to 0, parseAirsTime("21:00"))
        assertEquals(21 to 0, parseAirsTime("9:00 PM"))
        assertEquals(21 to 30, parseAirsTime("9:30pm"))
        assertEquals(0 to 0, parseAirsTime("12:00 AM"))
        assertEquals(12 to 0, parseAirsTime("12:00 PM"))
        assertEquals(8 to 5, parseAirsTime("08:05"))
        assertNull(parseAirsTime(null))
        assertNull(parseAirsTime(""))
        assertNull(parseAirsTime("25:00"))
        assertNull(parseAirsTime("13:00 PM"))
        assertNull(parseAirsTime("evening"))
    }

    @Test
    fun `unusable values resolve to nothing`() {
        assertNull(resolveReleaseInstant(null))
        assertNull(resolveReleaseInstant("   "))
        assertNull(resolveReleaseInstant("invalid-date"))
        assertNull(resolveReleaseInstant("2026"))
        // isoCalendarDateOrNull accepts day 31 in any month, so this reaches the local-midnight
        // conversion and has to fail there rather than throw.
        assertNull(resolveReleaseInstant("2026-02-31"))
    }

    @Test
    fun `tmdb air date does not overwrite the addon's timestamp for the same airing`() {
        assertEquals(
            "2026-09-04T01:00:00.000Z",
            preferPreciseReleaseDate(
                addonReleased = "2026-09-04T01:00:00.000Z",
                tmdbAirDate = "2026-09-03",
            ),
        )
    }

    @Test
    fun `tmdb air date wins when it is a real reschedule`() {
        assertEquals(
            "2026-09-17",
            preferPreciseReleaseDate(
                addonReleased = "2026-09-04T01:00:00.000Z",
                tmdbAirDate = "2026-09-17",
            ),
        )
    }

    @Test
    fun `tmdb air date wins when the addon has nothing more precise`() {
        assertEquals("2026-09-03", preferPreciseReleaseDate(null, "2026-09-03"))
        assertEquals("2026-09-03", preferPreciseReleaseDate("2026-09-04", "2026-09-03"))
        assertEquals("2026-09-03", preferPreciseReleaseDate("garbage", "2026-09-03"))
    }

    @Test
    fun `missing tmdb air date leaves the addon value alone`() {
        assertEquals("2026-09-04T01:00:00.000Z", preferPreciseReleaseDate("2026-09-04T01:00:00.000Z", null))
        assertEquals("2026-09-04T01:00:00.000Z", preferPreciseReleaseDate("2026-09-04T01:00:00.000Z", "  "))
    }

    @Test
    fun `local release date is what has-it-aired checks compare against`() {
        val raw = "2026-09-04T01:00:00.000Z"
        val localDay = CurrentDateProvider.localIsoDateAt(1788483600000L)

        assertEquals(localDay, localReleaseDateOrNull(raw))
        assertTrue(isReleasedBy(todayIsoDate = localDay, releasedDate = raw))

        // Date-only and non-date values keep their old string behaviour exactly.
        assertEquals("2026-09-03", localReleaseDateOrNull("2026-09-03"))
        assertEquals("2026-02-31", localReleaseDateOrNull("2026-02-31"))
        assertNull(localReleaseDateOrNull("2026"))
        assertNull(localReleaseDateOrNull("2026-"))
        assertTrue(isReleasedBy(todayIsoDate = "2026-08-30", releasedDate = "2026-"))
    }

    @Test
    fun `days between iso dates counts calendar days`() {
        assertEquals(0, isoDaysBetween("2026-08-30", "2026-08-30"))
        assertEquals(5, isoDaysBetween("2026-08-30", "2026-09-04"))
        assertEquals(-1, isoDaysBetween("2026-09-01", "2026-08-31"))
        assertNull(isoDaysBetween("nonsense", "2026-09-04"))
    }

    @Test
    fun `a timestamped release stays on one countdown all day`() {
        // Both forms of the same airing must now produce the same number of days, whichever
        // source answered — that is the whole point of the fix.
        val fromAddon = resolveReleaseInstant("2026-09-04T01:00:00.000Z")!!
        val chosen = preferPreciseReleaseDate("2026-09-04T01:00:00.000Z", "2026-09-03")
        assertEquals(fromAddon.localIsoDate, resolveReleaseInstant(chosen)!!.localIsoDate)
        assertTrue(resolveReleaseInstant(chosen)!!.hasTimeOfDay)
    }
}
