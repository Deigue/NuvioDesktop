package com.nuvio.app.features.simkl

import co.touchlab.kermit.Logger
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.tracking.WatchedHistoryReset
import com.nuvio.app.features.watched.WatchedIdAliases
import com.nuvio.app.features.watched.WatchedItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * SIMKL watched history, including per-episode timestamps, following SIMKL's two-phase sync.
 *
 * Phase 1 — the first read for a profile, or one after [invalidate] — downloads the whole history,
 * one type at a time (~14k rows and several MB on a real account). Phase 2 is
 * every read after that: `/sync/activities` says whether anything moved, and if it did only the
 * entries that changed since the last read are fetched, with `date_from` set to the `all` stamp
 * that read saw. That watermark is persisted per profile, so a relaunch is a delta too — it used to
 * live in memory only, and every launch and every scrobble paid for the full download again.
 *
 * Returning a delta is safe because the one consumer merges additively: rows absent from the
 * response are left exactly as they are. Removals on SIMKL never travelled through this path
 * anyway; a show reset (removed and re-added) re-enters the delta with its new
 * `added_to_watchlist_at` and its remaining episodes, which is all [toHistoryResets] needs.
 *
 * A failed fetch throws rather than degrading to the last good snapshot. Handing the merge a stale
 * snapshot re-adds every tick the user has removed since — an unmark that undoes itself at the next
 * sync. Skipping the merge loses nothing: the local store is already the authority between pulls.
 */
internal object SimklWatchedRepository {
    private const val BASE_URL = "https://api.simkl.com"
    private val log = Logger.withTag("SimklWatched")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }

    private var loadedProfileId: Int? = null
    private var state = StoredWatchedSyncState()

    /**
     * Drops the watermark, so the next read is a full Phase 1 download. Called for a user-initiated
     * resync, and by the importer when its store no longer holds anything this provider imported —
     * a delta would then have nothing to land on.
     */
    fun invalidate() {
        val profileId = ProfileRepository.activeProfileId
        ensureLoaded(profileId)
        if (state.stamp == null && state.dateFrom == null) return
        state = state.copy(stamp = null, dateFrom = null)
        persist(profileId)
    }

    /** Forgets everything stored for the active profile. Called on disconnect. */
    fun clearLocalState() {
        val profileId = ProfileRepository.activeProfileId
        loadedProfileId = profileId
        state = StoredWatchedSyncState()
        SimklWatchedSyncStorage.savePayload(profileId, null)
        pendingResets = emptyList()
        WatchedIdAliases.clear()
    }

    private fun ensureLoaded(profileId: Int) {
        if (loadedProfileId == profileId) return
        loadedProfileId = profileId
        state = SimklWatchedSyncStorage.loadPayload(profileId)
            ?.takeIf(String::isNotBlank)
            ?.let { payload -> runCatching { json.decodeFromString<StoredWatchedSyncState>(payload) }.getOrNull() }
            ?: StoredWatchedSyncState()
        pendingResets = emptyList()
        // The id aliases come from the history itself. A delta only carries the titles that
        // changed, so the full set has to be remembered rather than rebuilt from each response.
        WatchedIdAliases.replace(state.aliasGroups.map(List<String>::toSet))
    }

    private fun persist(profileId: Int) {
        SimklWatchedSyncStorage.savePayload(profileId, json.encodeToString(state))
    }

    suspend fun watchedItems(profileId: Int): List<WatchedItem> {
        if (!SimklAuthRepository.hasUsableToken()) return emptyList()
        ensureLoaded(profileId)
        // An empty list is the correct "nothing changed" answer, not a degraded one: the caller
        // merges additively, so it leaves the local store exactly as it is. Handing back a cached
        // snapshot instead would re-add every tick the user has removed since — see the class
        // comment.
        // No activities, no all-items: the guide's first rule, and the one that matters most when
        // the failure is a spent daily quota. Thrown, so the importer skips the merge and keeps the
        // watermark for the next attempt instead of losing it to a read it could not gate.
        val activities = SimklAuthRepository.fetchActivities()
            ?: error("SIMKL activity state could not be read; watched history not fetched")
        val stamp = simklWatchedHistoryActivitiesStamp(activities)
        if (stamp != null && stamp == state.stamp) {
            log.d { "SIMKL watched history: activities unchanged, skipping fetch" }
            return emptyList()
        }
        // The watermark for the *next* read is the stamp seen before this one starts, never one
        // read after it: a change landing while this request is in flight is newer than it, so the
        // next delta picks it up instead of it falling between two reads.
        val dateFrom = state.dateFrom?.takeIf(String::isNotBlank)
        // Without an `all` stamp the old watermark stays: an older `date_from` only returns more.
        val nextDateFrom = activities.all?.takeIf(String::isNotBlank) ?: dateFrom
        val payload = if (dateFrom != null) {
            // Phase 2: one bare all-items call covers every type and status.
            fetchAllItems("/sync/all-items?$HISTORY_QUERY&date_from=${simklUrlEncode(dateFrom)}")
        } else {
            // Phase 1: one type at a time, as the guide asks — three full payloads at once is the
            // CPU spike it warns about on both ends.
            val shows = fetchAllItems("/sync/all-items/shows?$HISTORY_QUERY")
            val movies = fetchAllItems("/sync/all-items/movies?$HISTORY_QUERY")
            val anime = fetchAllItems("/sync/all-items/anime?$HISTORY_QUERY")
            SimklAllItemsResponse(shows = shows.shows, movies = movies.movies, anime = anime.anime)
        }
        val items = payload.toWatchedItems() + backfillSeasonlessEntries(payload)
        if (profileId != loadedProfileId) return emptyList()
        log.i {
            val mode = if (dateFrom != null) "delta since $dateFrom" else "full"
            "SIMKL watched history ($mode): ${payload.movies.size} movies, ${payload.shows.size} shows, " +
                "${payload.anime.size} anime -> ${items.size} rows"
        }
        val aliasGroups = payload.toContentIdAliasGroups()
        val nextAliasGroups = if (dateFrom == null) {
            aliasGroups
        } else {
            mergeContentIdAliasGroups(state.aliasGroups.map(List<String>::toSet), aliasGroups)
        }
        // Stamped only after a fully successful read, so a failed backfill cannot mark a partial
        // history as current and suppress the retry.
        state = StoredWatchedSyncState(
            stamp = stamp,
            dateFrom = nextDateFrom,
            aliasGroups = nextAliasGroups.map { it.sorted() },
        )
        persist(profileId)
        pendingResets = payload.toHistoryResets()
        WatchedIdAliases.replace(nextAliasGroups)
        return items
    }

    private suspend fun fetchAllItems(pathAndQuery: String): SimklAllItemsResponse {
        val response = simklRequest(
            method = "GET",
            url = SimklAuthRepository.appendParams("$BASE_URL$pathAndQuery"),
            body = "",
        )
        if (response.status !in 200..299) {
            error("SIMKL watched-history fetch failed: HTTP ${response.status}")
        }
        // An empty body is SIMKL's answer to a delta, or a type, with nothing in it.
        val body = response.body.trim()
        if (body.isEmpty() || body == "null" || body == "[]") return SimklAllItemsResponse()
        return runCatching {
            json.decodeFromString<SimklAllItemsResponse>(body)
        }.getOrElse { failure ->
            if (failure is CancellationException) throw failure
            error("SIMKL watched-history payload could not be parsed: ${failure.message}")
        }
    }

    /**
     * `extended=full` turns on the `seasons[].episodes[]` arrays, `episode_watched_at=yes` stamps
     * each one, and `include_all_episodes=yes` extends both to completed and dropped entries, which
     * otherwise arrive as a bare `watched_episodes_count`. For a show marked complete in one action
     * SIMKL synthesizes the rows, stamped with the show's last-watched time.
     */
    private const val HISTORY_QUERY = "extended=full&episode_watched_at=yes&include_all_episodes=yes"

    // Set by each read, taken by the importer; see consumeHistoryResets.
    private var pendingResets: List<WatchedHistoryReset> = emptyList()

    /** The show resets seen by the last read, once. See [SimklAllItemsResponse.toHistoryResets]. */
    fun consumeHistoryResets(): List<WatchedHistoryReset> =
        pendingResets.also { pendingResets = emptyList() }

    /**
     * Recovers the watch state of shows SIMKL still reports as a bare count.
     *
     * A fallback only: [HISTORY_QUERY] asks SIMKL for the episodes of completed and dropped entries,
     * so this should find nothing to do. It stays for any entry that still arrives season-less —
     * see [SimklEpisodeCatalog]. One extra request per affected show, so it is chunked rather than
     * fanned out, and the log line below says whether it is still being reached.
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
                        target to SimklEpisodeCatalog.episodesFor(target.simklId, target.watchedEpisodesCount)
                    }
                }.awaitAll()
            }.forEach { (target, episodes) ->
                val items = buildBackfilledWatchedItems(target, episodes)
                if (items.isEmpty()) unresolved++
                recovered += items
            }
        }
        SimklEpisodeCatalog.flush()
        log.i {
            "SIMKL: recovered ${recovered.size} episode rows from ${targets.size - unresolved} shows" +
                if (unresolved > 0) " ($unresolved could not be resolved)" else ""
        }
        return recovered
    }

    /** Episode-list requests in flight at once. See [backfillSeasonlessEntries]. */
    private const val BACKFILL_CONCURRENCY = 6
}

@Serializable
private data class StoredWatchedSyncState(
    /** [simklWatchedHistoryActivitiesStamp] at the last successful read; equal means skip. */
    val stamp: String? = null,
    /** Activities `all` at the last successful read: the next read's `date_from`. */
    val dateFrom: String? = null,
    /** Every IMDb/TMDB pair seen so far; see [toContentIdAliasGroups]. */
    val aliasGroups: List<List<String>> = emptyList(),
)

/**
 * [existing] alias groups updated with the ones a delta just reported. A title's group is replaced
 * rather than unioned, so a corrected id on SIMKL does not leave the old pairing behind.
 */
internal fun mergeContentIdAliasGroups(
    existing: List<Set<String>>,
    delta: List<Set<String>>,
): List<Set<String>> {
    if (delta.isEmpty()) return existing
    val touched = delta.flatten().toSet()
    return existing.filter { group -> group.none(touched::contains) } + delta
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

/**
 * Each non-anime title's IMDb and TMDB ids as one alias group — see [WatchedIdAliases]. Includes
 * rewatch rows: the ids name the title whatever list it is on.
 */
internal fun SimklAllItemsResponse.toContentIdAliasGroups(): List<Set<String>> = buildList {
    fun addGroup(ids: SimklMediaIds?) {
        if (ids == null || ids.isKnownAnime()) return
        val imdb = ids.imdb?.takeIf { it.isNotBlank() && it != PLACEHOLDER_IMDB_ID } ?: return
        val tmdb = ids.tmdb?.takeIf(String::isNotBlank) ?: return
        add(setOf(imdb, "tmdb:$tmdb"))
    }
    movies.forEach { addGroup(it.movie?.ids) }
    shows.forEach { addGroup(it.showMedia?.ids) }
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
