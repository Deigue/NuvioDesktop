package com.nuvio.app.features.simkl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class SimklDeltaSyncTest {
    @Test
    fun aliasDeltaKeepsUntouchedTitlesAndReplacesChangedOnes() {
        val existing = listOf(setOf("tt1", "tmdb:1"), setOf("tt2", "tmdb:2"))
        val delta = listOf(setOf("tt2", "tmdb:22"), setOf("tt3", "tmdb:3"))

        val merged = mergeContentIdAliasGroups(existing, delta)

        assertEquals(
            setOf(setOf("tt1", "tmdb:1"), setOf("tt2", "tmdb:22"), setOf("tt3", "tmdb:3")),
            merged.toSet(),
        )
    }

    @Test
    fun emptyAliasDeltaLeavesTheStoredSetAlone() {
        val existing = listOf(setOf("tt1", "tmdb:1"))
        assertEquals(existing, mergeContentIdAliasGroups(existing, emptyList()))
    }

    @Test
    fun removedFromListStampMovesWithAnyCategory() {
        fun activities(shows: String?, anime: String?) = SimklActivities(
            all = "2026-10-03T10:00:00Z",
            tvShows = SimklCategoryActivity(removedFromList = shows),
            anime = SimklCategoryActivity(removedFromList = anime),
        )
        val before = simklRemovedFromListStamp(activities("2026-10-01T00:00:00Z", "2026-09-01T00:00:00Z"))
        val after = simklRemovedFromListStamp(activities("2026-10-01T00:00:00Z", "2026-10-03T09:00:00Z"))
        assertNotEquals(before, after)
        assertEquals(before, simklRemovedFromListStamp(activities("2026-10-01T00:00:00Z", "2026-09-01T00:00:00Z")))
    }

    @Test
    fun removedFromListStampIsNullWhenSimklReportsNone() {
        assertNull(simklRemovedFromListStamp(null))
        assertNull(simklRemovedFromListStamp(SimklActivities(all = "2026-10-03T10:00:00Z")))
    }

    @Test
    fun activitiesParseRemovedFromList() {
        val parsed = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString<SimklActivities>(
                """{"all":"a","tv_shows":{"all":"b","removed_from_list":"c"}}""",
            )
        assertEquals("c", parsed.tvShows?.removedFromList)
    }
}
