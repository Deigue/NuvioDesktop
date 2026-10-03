package com.nuvio.app.features.simkl

import kotlin.test.Test
import kotlin.test.assertEquals

class SimklWatchedParsingTest {
    @Test
    fun extendedHistoryIncludesOnlyEpisodesWithWatchedTimestamps() {
        val response = SimklAllItemsResponse(
            shows = listOf(
                SimklAllItemsEntry(
                    show = SimklShowMedia(title = "Show", ids = SimklMediaIds(imdb = "tt1")),
                    seasons = listOf(
                        SimklWatchedSeason(
                            number = 1,
                            episodes = listOf(
                                SimklWatchedEpisode(1, "2026-01-01T00:00:00Z"),
                                SimklWatchedEpisode(2, null),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val watched = response.toWatchedItems()

        assertEquals(1, watched.size)
        assertEquals(1, watched.single().episode)
    }

    @Test
    fun historyResetsCarryWatchedEpisodesAndSkipRewatchAndSeasonlessEntries() {
        val response = SimklAllItemsResponse(
            shows = listOf(
                SimklAllItemsEntry(
                    addedToWatchlistAt = "2026-09-14T20:19:56Z",
                    show = SimklShowMedia(title = "Friends", ids = SimklMediaIds(imdb = "tt0108778")),
                    seasons = listOf(
                        SimklWatchedSeason(
                            number = 1,
                            episodes = listOf(
                                SimklWatchedEpisode(1, null),
                                SimklWatchedEpisode(2, "2026-09-28T23:24:22Z"),
                            ),
                        ),
                    ),
                ),
                // A bare count (backfilled elsewhere) says nothing about which episodes remain.
                SimklAllItemsEntry(
                    addedToWatchlistAt = "2026-01-01T00:00:00Z",
                    watchedEpisodesCount = 10,
                    show = SimklShowMedia(title = "Seasonless", ids = SimklMediaIds(imdb = "tt2")),
                ),
                SimklAllItemsEntry(
                    addedToWatchlistAt = "2026-01-01T00:00:00Z",
                    isRewatch = true,
                    show = SimklShowMedia(title = "Rewatch", ids = SimklMediaIds(imdb = "tt3")),
                    seasons = listOf(SimklWatchedSeason(1, listOf(SimklWatchedEpisode(1, "2026-01-02T00:00:00Z")))),
                ),
            ),
        )

        val resets = response.toHistoryResets()

        assertEquals(1, resets.size)
        val reset = resets.single()
        assertEquals("tt0108778", reset.id)
        assertEquals(setOf(1 to 2), reset.remoteEpisodes)
        assertEquals(parseSimklTimestamp("2026-09-14T20:19:56Z"), reset.resetAtEpochMs)
    }
}
