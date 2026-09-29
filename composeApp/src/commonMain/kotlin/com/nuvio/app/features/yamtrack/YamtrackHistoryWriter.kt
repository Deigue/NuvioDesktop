package com.nuvio.app.features.yamtrack

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.tracking.TrackingHistoryItem
import com.nuvio.app.features.tracking.TrackingHistoryWriter
import com.nuvio.app.features.tracking.TrackingMediaKind
import com.nuvio.app.features.tracking.TrackingMediaReference
import com.nuvio.app.features.tracking.TrackingMutationResult
import com.nuvio.app.features.tracking.TrackingProviderId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Explicit mark-watched/unwatched actions through Floppy's REST tracking routes. */
internal object YamtrackHistoryWriter : TrackingHistoryWriter {
    override val providerId: TrackingProviderId = TrackingProviderId.YAMTRACK
    private val json = Json { ignoreUnknownKeys = true }
    private val log = Logger.withTag("YamtrackHistory")

    /**
     * How many times an unwatch will re-delete before giving up.
     *
     * Both endpoints remove one play at a time, so repeats have to be drained in a loop. The cap
     * exists because the loop's exit depends on the server answering the way we expect: a hundred
     * passes over a paged history is a request storm on the user's own instance.
     */
    private const val MAX_HISTORY_DELETE_PASSES = 20

    override suspend fun addToHistory(
        profileId: Int,
        items: Collection<TrackingHistoryItem>,
    ): TrackingMutationResult = mutate(profileId, items.map(TrackingHistoryItem::media), watched = true)

    override suspend fun removeFromHistory(
        profileId: Int,
        items: Collection<TrackingMediaReference>,
    ): TrackingMutationResult = mutate(profileId, items, watched = false)

    private suspend fun mutate(
        profileId: Int,
        items: Collection<TrackingMediaReference>,
        watched: Boolean,
    ): TrackingMutationResult {
        if (profileId != ProfileRepository.activeProfileId) {
            return TrackingMutationResult(items.size, notFoundCount = items.size)
        }
        val (baseUrl, token) = YamtrackSettingsRepository.activeCredentials()
            ?: error("Floppy is not connected")
        var notFound = 0
        // Anime episodes collapse per MAL entry: its progress is one count, so a batch only ever
        // needs the highest episode to mark or the lowest to unmark.
        val animeEpisodes = linkedMapOf<String, MutableList<YamtrackAnimeResolution.Entry>>()
        items.forEach { media ->
            when (val anime = media.resolveFloppyAnime()) {
                is YamtrackAnimeResolution.Entry -> {
                    // The show-level marker of an anime action; its episodes carry the change.
                    if (anime.episode != null) animeEpisodes.getOrPut(anime.mal) { mutableListOf() } += anime
                    return@forEach
                }
                is YamtrackAnimeResolution.Unaddressable -> {
                    log.w { "Floppy skipped anime '${media.title}': ${anime.reason}" }
                    notFound += 1
                    return@forEach
                }
                YamtrackAnimeResolution.NotAnime -> Unit
            }
            val target = media.toFloppyHistoryTarget()
            if (target == null) {
                notFound += 1
                return@forEach
            }
            val succeeded = when (target) {
                is FloppyHistoryTarget.Episode -> mutateEpisode(baseUrl, token, target, watched)
                is FloppyHistoryTarget.Movie -> if (watched) {
                    YamtrackScrobbleRepository.scrobble(
                        action = "stop",
                        item = target.item,
                        progressPercent = 100f,
                        positionSeconds = null,
                        durationSeconds = null,
                    ).handled
                } else {
                    deleteMovieHistory(baseUrl, token, target.identity)
                }
            }
            if (!succeeded) notFound += 1
        }
        animeEpisodes.values.forEach { entries ->
            val succeeded = if (watched) {
                markAnimeEpisode(baseUrl, token, entries.maxBy { it.episode ?: 0 })
            } else {
                unmarkAnimeEpisode(baseUrl, token, entries.minBy { it.episode ?: 0 })
            }
            if (!succeeded) notFound += entries.size
        }
        return TrackingMutationResult(items.size, notFoundCount = notFound)
    }

    private suspend fun TrackingMediaReference.resolveFloppyAnime(): YamtrackAnimeResolution {
        val catalog = catalog ?: return YamtrackAnimeResolution.NotAnime
        return YamtrackScrobbleRepository.resolveAnimeEntry(
            contentType = catalog.contentType,
            parentMetaId = catalog.contentId,
            videoId = catalog.videoId,
            title = title,
            seasonNumber = episode?.season,
            episodeNumber = episode?.number,
            isAnime = kind == TrackingMediaKind.ANIME,
        )
    }

    /**
     * Marks an anime episode by scrobbling it against its MAL entry.
     *
     * Floppy sets the entry's progress to the scrobbled episode number even when that is *lower*,
     * so an episode already inside the count is left alone — otherwise marking an old episode
     * would roll the count back.
     */
    private suspend fun markAnimeEpisode(
        baseUrl: String,
        token: String,
        entry: YamtrackAnimeResolution.Entry,
    ): Boolean {
        val episode = entry.episode ?: return true
        if (animeRows(baseUrl, token, entry.mal).any { it.progressCount >= episode }) return true
        return YamtrackScrobbleRepository.scrobble(
            action = "stop",
            item = YamtrackScrobbleItem.Episode(
                ids = entry.ids,
                seriesTitle = entry.title,
                title = entry.title,
                season = 1,
                episode = episode,
            ),
            progressPercent = 100f,
            positionSeconds = null,
            durationSeconds = null,
        ).handled
    }

    /**
     * Unmarks an anime episode by lowering its MAL entry's count to the episode before it.
     *
     * A count cannot hold gaps, so every later episode goes with it. Nothing left means nothing
     * watched, and — as for movies — unwatched means untracked rather than demoted to Planning.
     * Floppy's grouped (TVDB) anime shape derives progress from plays and ignores the PATCH; the
     * read-back reports that instead of claiming a success the next read would contradict.
     */
    private suspend fun unmarkAnimeEpisode(
        baseUrl: String,
        token: String,
        entry: YamtrackAnimeResolution.Entry,
    ): Boolean {
        val episode = entry.episode ?: return true
        val rows = animeRows(baseUrl, token, entry.mal).filter { it.progressCount >= episode }
        if (rows.isEmpty()) return true
        val headers = floppyHeaders(token)
        val remaining = episode - 1
        if (remaining <= 0) {
            val response = httpRequestRaw("DELETE", "$baseUrl/api/v1/media/anime/mal/${entry.mal}/", headers, "")
            require(response.status in 200..299 || response.status == 404) {
                "Floppy anime untrack failed (${response.status}): ${response.body.take(200)}"
            }
            return true
        }
        rows.forEach { row ->
            val response = httpRequestRaw(
                "PATCH",
                "$baseUrl/api/v1/media/anime/mal/${entry.mal}/history/${row.consumptionId}/",
                headers,
                json.encodeToString(FloppyAnimeProgressPatch(progress = remaining, status = FLOPPY_STATUS_IN_PROGRESS)),
            )
            require(response.status in 200..299) {
                "Floppy anime unwatch failed (${response.status}): ${response.body.take(200)}"
            }
        }
        val stuck = animeRows(baseUrl, token, entry.mal).any { it.progressCount > remaining }
        if (stuck) log.w { "Floppy kept MAL ${entry.mal} above episode $remaining; it may be a grouped anime" }
        return !stuck
    }

    private suspend fun animeRows(baseUrl: String, token: String, mal: String): List<FloppyHistoryRecord> {
        val response = httpRequestRaw(
            "GET",
            "$baseUrl/api/v1/media/anime/mal/$mal/history/?limit=100&offset=0",
            floppyHeaders(token),
            "",
        )
        if (response.status == 404) return emptyList()
        require(response.status in 200..299) {
            "Floppy anime history fetch failed (${response.status}): ${response.body.take(200)}"
        }
        return json.decodeFromString<FloppyHistoryPage>(response.body).results
            .filter { it.consumptionId != null }
    }

    private suspend fun TrackingMediaReference.toFloppyHistoryTarget(): FloppyHistoryTarget? {
        val catalog = catalog ?: return null
        // A show-level reference has no episode route on Floppy, and buildItem would fall through
        // to a movie — whose tmdb id space overlaps TV's, so it could hit an unrelated film.
        if (episode == null && catalog.contentType.isSeriesLikeFloppyType()) return null
        val item = YamtrackScrobbleRepository.buildItem(
            contentType = catalog.contentType,
            parentMetaId = catalog.contentId,
            videoId = catalog.videoId,
            title = title,
            episodeTitle = episode?.title,
            seasonNumber = episode?.season,
            episodeNumber = episode?.number,
            isAnime = kind == TrackingMediaKind.ANIME,
        ) ?: return null
        val identity = item.ids.toFloppyIdentity() ?: return null
        return when (item) {
            is YamtrackScrobbleItem.Movie -> FloppyHistoryTarget.Movie(identity, item)
            is YamtrackScrobbleItem.Episode -> FloppyHistoryTarget.Episode(
                identity = identity,
                season = item.season,
                episode = item.episode,
            )
        }
    }

    private suspend fun mutateEpisode(
        baseUrl: String,
        token: String,
        target: FloppyHistoryTarget.Episode,
        watched: Boolean,
    ): Boolean {
        val headers = floppyHeaders(token)
        if (watched) {
            val response = httpRequestRaw("POST", target.watchUrl(baseUrl), headers, "{}")
            if (response.status == 404) return false
            require(response.status in 200..299) {
                "Floppy episode watch failed (${response.status}): ${response.body.take(200)}"
            }
            return true
        }

        // The endpoint removes the most recent play. Repeat so "mark unwatched" clears repeats as
        // well and does not leave the episode watched because it happened to have two play rows.
        var deleted = false
        repeat(MAX_HISTORY_DELETE_PASSES) {
            val response = httpRequestRaw("DELETE", target.watchUrl(baseUrl), headers, "")
            when (response.status) {
                in 200..299 -> deleted = true
                404 -> return deleted
                else -> error("Floppy episode unwatch failed (${response.status}): ${response.body.take(200)}")
            }
        }
        return deleted
    }

    /**
     * Unwatching a movie deletes every tracked entry it has, which also takes it out of the
     * Floppy library — unwatched means untracked, not quietly demoted to Planning.
     *
     * Entries must go through the per-media `history/{consumption_id}/` route. The top-level
     * `/api/v1/history/{type}/{history_id}/` route takes a *change-log* id, and feeding it a
     * consumption id answers 204 while deleting whichever unrelated title owns that log row.
     *
     * Plays recorded through `/watch/` are a separate record from entries, so they are drained too.
     */
    private suspend fun deleteMovieHistory(
        baseUrl: String,
        token: String,
        identity: FloppyIdentity,
    ): Boolean {
        val headers = floppyHeaders(token)
        val mediaUrl = "$baseUrl/api/v1/media/movie/${identity.source}/${identity.id}"
        var deleted = false
        for (pass in 0 until MAX_HISTORY_DELETE_PASSES) {
            val history = httpRequestRaw("GET", "$mediaUrl/history/?limit=100&offset=0", headers, "")
            if (history.status == 404) break
            require(history.status in 200..299) {
                "Floppy movie history fetch failed (${history.status}): ${history.body.take(200)}"
            }
            val ids = json.decodeFromString<FloppyHistoryPage>(history.body).results
                .mapNotNull(FloppyHistoryRecord::consumptionId)
            if (ids.isEmpty()) break
            var deletedThisPass = false
            ids.forEach { id ->
                val response = httpRequestRaw("DELETE", "$mediaUrl/history/$id/", headers, "")
                require(response.status in 200..299 || response.status == 404) {
                    "Floppy movie unwatch failed (${response.status}): ${response.body.take(200)}"
                }
                if (response.status in 200..299) deletedThisPass = true
            }
            // A pass that removed nothing will not remove anything on the next one either — the
            // history page comes back identical. Without this the loop re-fetches and re-deletes
            // the same rows for every remaining pass.
            if (!deletedThisPass) break
            deleted = true
        }
        for (pass in 0 until MAX_HISTORY_DELETE_PASSES) {
            val response = httpRequestRaw("DELETE", "$mediaUrl/watch/", headers, "")
            when (response.status) {
                in 200..299 -> deleted = true
                404 -> break
                else -> error("Floppy movie play delete failed (${response.status}): ${response.body.take(200)}")
            }
        }
        return deleted
    }

    private fun floppyHeaders(token: String) = mapOf(
        "Accept" to "application/json",
        "Content-Type" to "application/json",
        "Authorization" to "Bearer $token",
    )
}

private fun String.isSeriesLikeFloppyType(): Boolean =
    trim().lowercase() in setOf("series", "tv", "show", "tvshow", "anime")

private data class FloppyIdentity(val source: String, val id: String)

private sealed interface FloppyHistoryTarget {
    data class Movie(
        val identity: FloppyIdentity,
        val item: YamtrackScrobbleItem.Movie,
    ) : FloppyHistoryTarget

    data class Episode(
        val identity: FloppyIdentity,
        val season: Int,
        val episode: Int,
    ) : FloppyHistoryTarget {
        fun watchUrl(baseUrl: String): String =
            "$baseUrl/api/v1/media/tv/${identity.source}/${identity.id}/$season/episodes/$episode/watch"
    }
}

@Serializable
private data class FloppyHistoryPage(val results: List<FloppyHistoryRecord> = emptyList())

@Serializable
private data class FloppyHistoryRecord(
    @SerialName("consumption_id") val consumptionId: Long? = null,
    val progress: Double? = null,
) {
    val progressCount: Int get() = progress?.toInt() ?: 0
}

private const val FLOPPY_STATUS_IN_PROGRESS = 1

@Serializable
private data class FloppyAnimeProgressPatch(val progress: Int, val status: Int)

private fun YamtrackScrobbleRepository.YamtrackIds.toFloppyIdentity(): FloppyIdentity? =
    tmdb?.takeIf(String::isNotBlank)?.let { FloppyIdentity("tmdb", it) }
        ?: imdb?.takeIf(String::isNotBlank)?.let { FloppyIdentity("imdb", it) }
        ?: tvdb?.takeIf(String::isNotBlank)?.let { FloppyIdentity("tvdb", it) }
