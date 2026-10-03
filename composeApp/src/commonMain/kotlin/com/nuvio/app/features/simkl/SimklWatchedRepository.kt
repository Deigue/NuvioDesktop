package com.nuvio.app.features.simkl

import co.touchlab.kermit.Logger
import com.nuvio.app.features.tracking.WatchedHistoryReset
import com.nuvio.app.features.watched.WatchedItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json

/**
 * Full SIMKL watched history, including per-episode timestamps.
 *
 * A failed fetch throws rather than degrading to the last good snapshot. The one consumer merges
 * this additively into the local watched store, so handing it a stale snapshot re-adds every tick
 * the user has removed since — an unmark that undoes itself at the next sync. Skipping the merge
 * loses nothing: the local store is already the authority between pulls.
 */
internal object SimklWatchedRepository {
    private const val BASE_URL = "https://api.simkl.com"
    private val log = Logger.withTag("SimklWatched")
    private val json = Json { ignoreUnknownKeys = true }

    private var cachedStampProfileId: Int? = null
    private var cachedStamp: String? = null

    /** See [invalidate]; also called when the pulled profile is not the one the stamp belongs to. */
    fun invalidate() {
        cachedStampProfileId = null
        cachedStamp = null
    }

    suspend fun watchedItems(profileId: Int): List<WatchedItem> {
        if (!SimklAuthRepository.hasUsableToken()) return emptyList()
        // This is the most expensive read in the app — the full history, ~14k rows on a real
        // account, plus an episode-catalog backfill pass over every season-less show. It used to
        // run once per launch, so its cost never mattered; it is now on a five-minute poll, where
        // it very much does. SIMKL's own change stamp answers "is any of that worth downloading"
        // in one small request, the same way the Continue Watching seeds and the library already
        // gate themselves.
        //
        // An empty list is the correct "nothing changed" answer, not a degraded one: the caller
        // merges additively, so it leaves the local store exactly as it is. Handing back a cached
        // snapshot instead would re-add every tick the user has removed since — see the class
        // comment.
        val stamp = simklWatchedHistoryActivitiesStamp(SimklAuthRepository.fetchActivities())
        if (stamp != null && stamp == cachedStamp && profileId == cachedStampProfileId) {
            log.d { "SIMKL watched history: activities unchanged, skipping full fetch" }
            return emptyList()
        }
        // `extended=full` supplies seasons/episodes and `episode_watched_at=yes` distinguishes
        // watched episodes from the unwatched episode rows included in that extended response.
        val url = SimklAuthRepository.appendParams(
            "$BASE_URL/sync/all-items/all?extended=full&episode_watched_at=yes",
        )
        val response = simklRequest(method = "GET", url = url, body = "")
        if (response.status !in 200..299) {
            error("SIMKL watched-history fetch failed: HTTP ${response.status}")
        }
        val payload = runCatching {
            json.decodeFromString<SimklAllItemsResponse>(response.body)
        }.getOrElse { failure ->
            if (failure is CancellationException) throw failure
            error("SIMKL watched-history payload could not be parsed: ${failure.message}")
        }
        val items = payload.toWatchedItems() + backfillSeasonlessEntries(payload)
        // Stamped only after a fully successful read, so a failed backfill cannot mark a partial
        // history as current and suppress the retry.
        cachedStamp = stamp
        cachedStampProfileId = profileId
        pendingResets = payload.toHistoryResets()
        return items
    }

    // Set by each full read, taken by the importer; see consumeHistoryResets.
    private var pendingResets: List<WatchedHistoryReset> = emptyList()

    /** The show resets seen by the last full read, once. See [SimklAllItemsResponse.toHistoryResets]. */
    fun consumeHistoryResets(): List<WatchedHistoryReset> =
        pendingResets.also { pendingResets = emptyList() }

    /**
     * Recovers the watch state of shows SIMKL reports as a bare count.
     *
     * See [SimklEpisodeCatalog] for why this is necessary at all. One extra request per affected
     * show, so it is chunked rather than fanned out: an import touches every completed show at once,
     * and 145 simultaneous requests is how an account earns a rate limit.
     */
    private suspend fun backfillSeasonlessEntries(
        payload: SimklAllItemsResponse,
    ): List<WatchedItem> {
        val targets = payload.episodeBackfillTargets()
        if (targets.isEmpty()) return emptyList()
        log.i { "SIMKL: ${targets.size} shows arrived without episodes; recovering from the episode catalog" }

        val recovered = mutableListOf<WatchedItem>()
        var unresolved = 0
        for (chunk in targets.chunked(BACKFILL_CONCURRENCY)) {
            coroutineScope {
                chunk.map { target ->
                    async {
                        target to SimklEpisodeCatalog.episodesFor(target.simklId)
                    }
                }.awaitAll()
            }.forEach { (target, episodes) ->
                val items = buildBackfilledWatchedItems(target, episodes)
                if (items.isEmpty()) unresolved++
                recovered += items
            }
        }
        log.i {
            "SIMKL: recovered ${recovered.size} episode rows from ${targets.size - unresolved} shows" +
                if (unresolved > 0) " ($unresolved could not be resolved)" else ""
        }
        return recovered
    }

    /** Episode-list requests in flight at once. See [backfillSeasonlessEntries]. */
    private const val BACKFILL_CONCURRENCY = 6
}

/**
 * The activities stamp that decides whether the full watched history is worth re-downloading.
 *
 * Deliberately the account-wide `all` rather than the per-category stamps used by the Continue
 * Watching seed gate: watched history spans every list and every type, including the `dropped` and
 * `hold` states [SimklCategoryActivity] does not model, so anything narrower risks missing a change
 * — and the failure mode of this gate is silence, exactly the one that made the original bug so
 * hard to see. Over-fetching when an unrelated setting changes is the cheaper mistake.
 */
internal fun simklWatchedHistoryActivitiesStamp(activities: SimklActivities?): String? {
    if (activities == null) return null
    activities.all?.takeIf { it.isNotBlank() }?.let { return "all=$it" }
    val parts = listOf(
        "shows" to activities.tvShows?.all,
        "movies" to activities.movies?.all,
        "anime" to activities.anime?.all,
    )
    if (parts.all { (_, value) -> value.isNullOrBlank() }) return null
    return parts.joinToString("|") { (name, value) -> "$name=${value.orEmpty()}" }
}

private val watchedImportLog = Logger.withTag("SimklWatchedImport")

/**
 * Every non-anime show SIMKL states an explicit episode list for, as the episodes it still has and
 * when the entry (re)entered the account's lists.
 *
 * The import is additive, so a show the user reset on SIMKL — removed and re-added, which restarts
 * `added_to_watchlist_at` and clears the history — kept every old tick locally, and a fully watched
 * show that SIMKL now calls "1 of 236" still read as finished here. Each entry becomes a candidate;
 * the importer decides what, if anything, is stale (see `pruneResetWatchedItems`).
 *
 * Anime is left out on purpose: its episode coordinates are remapped between SIMKL's per-entry
 * numbering and the franchise one, so "not in SIMKL's list" does not reliably mean "not watched".
 * Season-less entries (bare counts backfilled from the catalog) and rewatch rows say nothing about
 * which episodes are in the main history, so they are skipped too.
 */
internal fun SimklAllItemsResponse.toHistoryResets(): List<WatchedHistoryReset> = shows.mapNotNull { entry ->
    if (entry.isRewatch || entry.seasons.isEmpty()) return@mapNotNull null
    val show = entry.showMedia ?: return@mapNotNull null
    if (show.ids.isKnownAnime()) return@mapNotNull null
    val id = show.ids.toBestContentId() ?: return@mapNotNull null
    val resetAt = entry.addedToWatchlistAt?.let(::parseSimklTimestamp) ?: return@mapNotNull null
    val remote = buildSet {
        entry.seasons.forEach { season ->
            val seasonNumber = season.number ?: return@forEach
            season.episodes.forEach { episode ->
                if (episode.watchedAt == null) return@forEach
                val number = episode.number ?: return@forEach
                add(seasonNumber to number)
            }
        }
    }
    WatchedHistoryReset(type = "series", id = id, resetAtEpochMs = resetAt, remoteEpisodes = remote)
}

internal fun SimklAllItemsResponse.toWatchedItems(): List<WatchedItem> = buildList {
    movies.forEach { entry ->
        if (entry.isRewatch) return@forEach
        val movie = entry.movie ?: return@forEach
        val watchedAt = entry.lastWatchedAt ?: return@forEach
        val id = if (movie.ids.isKnownAnime()) {
            movie.ids.toBestAnimeMovieContentId()
        } else {
            movie.ids.toBestContentId()
        } ?: return@forEach
        add(
            WatchedItem(
                id = id,
                type = "movie",
                name = movie.title ?: id,
                poster = movie.poster?.takeIf(String::isNotBlank)?.simklPosterUrl(),
                releaseInfo = movie.year?.toString(),
                markedAtEpochMs = parseSimklTimestamp(watchedAt) ?: 0L,
            ),
        )
    }

    fun addEpisodes(entry: SimklAllItemsEntry, anime: Boolean) {
        if (entry.isRewatch) return
        val show = entry.showMedia ?: return
        val id = (if (anime) show.ids.toBestAnimeContentId() else show.ids.toBestContentId())
            ?: run {
                // A show whose id will not resolve contributes no watched rows at all, and did so
                // without a word — the store simply never gains a tick for it, which reads as "the
                // client says unwatched" and sends you looking at the sync path instead of the id
                // mapping. Named, with its ids, so the mapping gap is the first thing you see.
                watchedImportLog.i {
                    "SIMKL history: no usable content id for ${show.title ?: "<untitled>"} " +
                        "(isAnime=$anime ids=${show.ids}); its episodes import as nothing"
                }
                return
            }
        entry.seasons.forEach { season ->
            val rawSeason = season.number ?: return@forEach
            season.episodes.forEach { episode ->
                val watchedAt = episode.watchedAt ?: return@forEach
                val rawEpisode = episode.number ?: return@forEach
                val (seasonNumber, episodeNumber) = show.ids.episodeCoordinatesFor(
                    contentId = id,
                    isAnime = anime,
                    entrySeason = rawSeason,
                    entryEpisode = rawEpisode,
                    // SIMKL's own franchise (TVDB) coordinates — see SimklEpisodeTvdbMapping.
                    franchiseSeason = episode.tvdb?.season,
                    franchiseEpisode = episode.tvdb?.episode,
                )
                add(
                    WatchedItem(
                        id = id,
                        type = "series",
                        name = show.title ?: id,
                        poster = show.poster?.takeIf(String::isNotBlank)?.simklPosterUrl(),
                        releaseInfo = show.year?.toString(),
                        season = seasonNumber,
                        episode = episodeNumber,
                        markedAtEpochMs = parseSimklTimestamp(watchedAt) ?: 0L,
                    ),
                )
            }
        }
    }

    shows.forEach { addEpisodes(it, anime = false) }
    anime.forEach { addEpisodes(it, anime = true) }
}
