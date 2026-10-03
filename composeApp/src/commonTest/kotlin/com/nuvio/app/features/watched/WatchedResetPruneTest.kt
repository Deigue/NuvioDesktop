package com.nuvio.app.features.watched

import com.nuvio.app.features.tracking.WatchedHistoryReset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WatchedResetPruneTest {
    private val day = 24L * 60L * 60L * 1000L
    private val resetAt = 1_790_000_000_000L
    private val now = resetAt + 30 * day

    private fun episode(season: Int, episode: Int, markedAt: Long, id: String = "tt1") =
        WatchedItem(id = id, type = "series", name = "Show", season = season, episode = episode, markedAtEpochMs = markedAt)

    private fun storeOf(vararg items: WatchedItem): Map<String, WatchedItem> =
        items.associateBy { watchedItemKey(it.type, it.id, it.season, it.episode) }

    @Test
    fun resetShowLosesOldTicksTheProviderNoLongerHas() {
        // The Friends case: fully watched long ago, reset on SIMKL, one episode watched since.
        val store = storeOf(
            episode(1, 1, resetAt - 40 * day),
            episode(1, 2, resetAt - 40 * day),
            episode(1, 3, resetAt - 40 * day),
            WatchedItem(id = "tt1", type = "series", name = "Show", markedAtEpochMs = resetAt - 40 * day),
            episode(1, 1, resetAt - 40 * day, id = "tt2"),
        )
        val reset = WatchedHistoryReset("series", "tt1", resetAt, remoteEpisodes = setOf(1 to 2))

        val pruned = pruneResetWatchedItems(store, listOf(reset), now)

        assertEquals(
            setOf(watchedItemKey("series", "tt1", 1, 2), watchedItemKey("series", "tt2", 1, 1)),
            pruned.keys,
        )
    }

    @Test
    fun ticksNewerThanTheResetAreKept() {
        val store = storeOf(
            episode(1, 1, resetAt + day),
            episode(1, 2, resetAt - 40 * day),
            episode(1, 3, resetAt - 40 * day),
        )
        val reset = WatchedHistoryReset("series", "tt1", resetAt, remoteEpisodes = setOf(1 to 2))

        val pruned = pruneResetWatchedItems(store, listOf(reset), now)

        assertTrue(watchedItemKey("series", "tt1", 1, 1) in pruned)
        assertFalse(watchedItemKey("series", "tt1", 1, 3) in pruned)
    }

    @Test
    fun ticksInsideTheGraceWindowAreKept() {
        // A reset reported "now" must not take back a completion the provider has not received yet.
        val recent = now - 60L * 60L * 1000L
        val store = storeOf(episode(1, 1, recent), episode(1, 2, recent))
        val reset = WatchedHistoryReset("series", "tt1", resetAtEpochMs = now, remoteEpisodes = emptySet())

        assertEquals(store, pruneResetWatchedItems(store, listOf(reset), now))
    }

    @Test
    fun showWithAsManyRemoteEpisodesIsLeftAlone() {
        // Different numbering on each side (TMDB vs TVDB) — the same count, nothing was lost.
        val store = storeOf(episode(1, 1, resetAt - 40 * day), episode(1, 2, resetAt - 40 * day))
        val reset = WatchedHistoryReset("series", "tt1", resetAt, remoteEpisodes = setOf(2 to 1, 2 to 2))

        assertEquals(store, pruneResetWatchedItems(store, listOf(reset), now))
    }
}
