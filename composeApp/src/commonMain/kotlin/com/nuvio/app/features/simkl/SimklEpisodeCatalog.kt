package com.nuvio.app.features.simkl

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** One episode of a show, from SIMKL's public episode listing. */
@Serializable
internal data class SimklCatalogEpisode(
    val season: Int? = null,
    val episode: Int? = null,
    /** `episode` for a numbered episode, `special` for everything else. */
    val type: String? = null,
) {
    /**
     * Specials carry no season and are excluded from SIMKL's own `watched_episodes_count`, so they
     * must not be counted here either — including them would shift every subsequent episode.
     */
    val isNumberedEpisode: Boolean
        get() = type == "episode" && season != null && episode != null
}

/**
 * The episode list for a show, fetched from SIMKL's public `/tv/episodes` endpoint.
 *
 * Needed when `/sync/all-items` **omits the `seasons` array for a completed or dropped show** — it
 * reports `watched_episodes_count` and nothing to attach it to. Measured on a real account: 145 of
 * 298 shows, 8,017 watched episodes, silently importing as zero. SIMKL's sync guide documents the
 * cause: `extended=full` loads episodes for watching / hold / plantowatch only, and completed and
 * dropped need `include_all_episodes=yes` as well. The history read now sends it, so this is a
 * fallback for whatever still arrives season-less rather than a pass over every finished show.
 *
 * Unauthenticated: this is public catalogue metadata, not user data. Kept on disk, shared by every
 * profile: a full history read touches every affected show at once (145 requests on the account
 * above), and while the list lived only in memory every launch paid for all of them again. An entry
 * is fetched again once it is [MAX_AGE_MS] old, or sooner when it is too short to hold the watched
 * count it is asked to cover — a new season the user has started (at most daily; see below).
 */
internal object SimklEpisodeCatalog {
    private const val BASE_URL = "https://api.simkl.com"
    private const val MAX_AGE_MS = 30L * 24L * 60L * 60L * 1000L
    private const val SHORT_RETRY_MS = 24L * 60L * 60L * 1000L
    private val log = Logger.withTag("SimklEpisodes")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }
    private val cacheMutex = Mutex()
    private var cache: MutableMap<Int, StoredCatalogEntry>? = null
    private var dirty = false

    /**
     * The episode list for [simklId]. [minNumberedEpisodes] is the watched count the caller is about
     * to spread over it; a cached list shorter than that is stale and fetched again.
     */
    suspend fun episodesFor(simklId: Int, minNumberedEpisodes: Int = 0): List<SimklCatalogEpisode> {
        if (simklId <= 0) return emptyList()
        val now = System.currentTimeMillis()
        cacheMutex.withLock { loadedCache()[simklId] }
            ?.takeIf { entry ->
                val age = now - entry.fetchedAtEpochMs
                // A list that stays short after a refetch is SIMKL's data, not staleness, so it is
                // retried at most daily rather than on every read.
                val short = entry.episodes.count(SimklCatalogEpisode::isNumberedEpisode) < minNumberedEpisodes
                age < MAX_AGE_MS && !(short && age >= SHORT_RETRY_MS)
            }
            ?.let { return it.episodes }
        val url = SimklAuthRepository.appendParams("$BASE_URL/tv/episodes/$simklId")
        val episodes = try {
            // Edge-cached catalog data: no Authorization header, but SIMKL still wants the User-Agent.
            val response = simklRequest(method = "GET", url = url, authenticated = false)
            check(response.status in 200..299) { "HTTP ${response.status}" }
            json.decodeFromString<List<SimklCatalogEpisode>>(response.body)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            log.w { "Episode list for simkl:$simklId failed: ${failure.message}" }
            return emptyList()
        }
        cacheMutex.withLock {
            // Specials are never backfilled, so they are not worth the disk.
            val kept = episodes.filter(SimklCatalogEpisode::isNumberedEpisode)
            loadedCache()[simklId] = StoredCatalogEntry(fetchedAtEpochMs = now, episodes = kept)
            dirty = true
        }
        return episodes
    }

    /** Writes what [episodesFor] fetched since the last call. Once per backfill, not per show. */
    suspend fun flush() {
        cacheMutex.withLock {
            val current = cache ?: return
            if (!dirty) return
            dirty = false
            runCatching {
                SimklEpisodeCatalogStorage.savePayload(json.encodeToString(StoredCatalog(current)))
            }.onFailure { log.w(it) { "Episode catalog could not be saved" } }
        }
    }

    private fun loadedCache(): MutableMap<Int, StoredCatalogEntry> =
        cache ?: runCatching {
            SimklEpisodeCatalogStorage.loadPayload()
                ?.takeIf(String::isNotBlank)
                ?.let { json.decodeFromString<StoredCatalog>(it).shows.toMutableMap() }
        }.getOrNull().let { it ?: mutableMapOf() }.also { cache = it }
}

@Serializable
private data class StoredCatalog(val shows: Map<Int, StoredCatalogEntry> = emptyMap())

@Serializable
private data class StoredCatalogEntry(
    val fetchedAtEpochMs: Long = 0L,
    val episodes: List<SimklCatalogEpisode> = emptyList(),
)

/** A show whose watch state arrived as a bare count, with no episodes attached to it. */
internal data class SimklEpisodeBackfillTarget(
    val simklId: Int,
    val show: SimklShowMedia,
    val isAnime: Boolean,
    val watchedEpisodesCount: Int,
    val lastWatchedAt: String?,
)

/**
 * Entries that reported watched episodes but carried no `seasons` array to put them in.
 *
 * Pure, so the selection is testable without a network. An entry with its episodes attached is left
 * alone — this is a repair for missing data, never a second source for data already present.
 */
internal fun SimklAllItemsResponse.episodeBackfillTargets(): List<SimklEpisodeBackfillTarget> =
    buildList {
        fun collect(entries: List<SimklAllItemsEntry>, isAnime: Boolean) {
            entries.forEach { entry ->
                if (entry.isRewatch) return@forEach
                if (entry.seasons.isNotEmpty()) return@forEach
                val watched = entry.watchedEpisodesCount ?: 0
                if (watched <= 0) return@forEach
                val show = entry.showMedia ?: return@forEach
                val simklId = show.ids.simkl ?: return@forEach
                add(
                    SimklEpisodeBackfillTarget(
                        simklId = simklId,
                        show = show,
                        isAnime = isAnime,
                        watchedEpisodesCount = watched,
                        lastWatchedAt = entry.lastWatchedAt,
                    ),
                )
            }
        }
        collect(shows, isAnime = false)
        collect(anime, isAnime = true)
    }

/**
 * Turns a bare watched count plus an episode list into the watched rows SIMKL did not send.
 *
 * Exact when the show is finished — `watchedEpisodesCount` covers every numbered episode, so every
 * one of them is marked and nothing is guessed. That is the overwhelming majority of the repair
 * (131 of 145 affected shows on the account this was measured against).
 *
 * For a partly-watched show it is an approximation: the API gives no way to know *which* episodes
 * those were, so they are taken in aired order, which is how people watch and drop shows.
 *
 * **SIMKL's `last_watched` marker is deliberately not used as a ceiling.** It looks like a better
 * signal and is not: on a real account four shows carried a marker stranded far behind their watch
 * state — Jessica Jones reported 39 of 39 watched with `last_watched` still at `S01E07` — so
 * honouring it would have restored 7 episodes instead of 39. Nor would trusting it only when it
 * agrees with the count buy anything: the marker can only ever remove a *suffix* of the aired order,
 * and taking the first N of a prefix is the first N of the whole list whenever the prefix is long
 * enough. It is either wrong or a no-op, so it plays no part.
 *
 * Every row carries the entry's `last_watched_at`, the only timestamp SIMKL offers here. Per-episode
 * times are simply not in this payload.
 */
internal fun buildBackfilledWatchedItems(
    target: SimklEpisodeBackfillTarget,
    episodes: List<SimklCatalogEpisode>,
): List<com.nuvio.app.features.watched.WatchedItem> {
    val show = target.show
    val contentId = (if (target.isAnime) show.ids.toBestAnimeContentId() else show.ids.toBestContentId())
        ?: return emptyList()
    val ordered = episodes
        .filter { it.isNumberedEpisode }
        .sortedWith(compareBy({ it.season }, { it.episode }))
    if (ordered.isEmpty()) return emptyList()

    val markedAt = target.lastWatchedAt?.let(::parseSimklTimestamp) ?: 0L
    return ordered.take(target.watchedEpisodesCount).map { episode ->
        val (seasonNumber, episodeNumber) = show.ids.episodeCoordinatesFor(
            contentId = contentId,
            isAnime = target.isAnime,
            entrySeason = episode.season!!,
            entryEpisode = episode.episode!!,
            // The public episode listing carries no TVDB mapping, so there is no franchise
            // coordinate to prefer — the same case as an entry whose episodes have no `tvdb` block.
            franchiseSeason = null,
            franchiseEpisode = null,
        )
        com.nuvio.app.features.watched.WatchedItem(
            id = contentId,
            type = "series",
            name = show.title ?: contentId,
            poster = show.poster?.takeIf(String::isNotBlank)?.simklPosterUrl(),
            releaseInfo = show.year?.toString(),
            season = seasonNumber,
            episode = episodeNumber,
            markedAtEpochMs = markedAt,
        )
    }
}
