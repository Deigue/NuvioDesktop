package com.nuvio.app.features.playlist

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.details.effectiveEpisodeNumber
import com.nuvio.app.features.details.effectiveSeasonNumber
import com.nuvio.app.features.details.sortedPlayableEpisodes
import com.nuvio.app.features.player.skip.PlayerNextEpisodeRules
import kotlin.random.Random

/**
 * Turns a random-episode slot ([PlaylistEntry.randomEpisode]) into a concrete episode when it is
 * reached. Same pool as the details page's Random episode button: aired episodes, main seasons
 * before specials — narrowed to the slot's [PlaylistEntry.randomSeasons] when it has any.
 */
internal object PlaylistRandomEpisodes {

    // Recently picked episodes per show, so a channel with several slots for one show (or one slot
    // on a looping list) does not replay the same episode back to back. In memory only.
    private val recent = mutableMapOf<String, ArrayDeque<String>>()

    /** The slot as a concrete episode, keeping its entryId, or null if the show's episodes could not be loaded. */
    suspend fun resolve(entry: PlaylistEntry): PlaylistEntry? {
        if (!entry.isUnresolvedRandom) return entry
        val meta = runCatching { MetaDetailsRepository.fetch(entry.parentMetaType, entry.parentMetaId) }.getOrNull()
            ?: return null.also { PlaylistPlaybackSession.log.w { "random slot ${entry.entryId}: meta fetch failed" } }
        val video = pick(meta, entry.randomSeasons)
            ?: return null.also { PlaylistPlaybackSession.log.w { "random slot ${entry.entryId}: no aired episodes" } }
        val resolved = PlaylistEntries.episode(meta, video).copy(
            entryId = entry.entryId,
            randomEpisode = true,
            randomSeasons = entry.randomSeasons,
            addedAtEpochMs = entry.addedAtEpochMs,
        )
        PlaylistPlaybackSession.log.i {
            "random slot ${entry.entryId} -> S${resolved.seasonNumber}E${resolved.episodeNumber} (${resolved.videoId})"
        }
        return resolved
    }

    private fun pick(meta: MetaDetails, seasons: List<Int>?): MetaVideo? {
        val aired = meta.sortedPlayableEpisodes().filter { video ->
            video.effectiveEpisodeNumber()?.let { it > 0 } == true &&
                PlayerNextEpisodeRules.hasEpisodeAired(video.released)
        }
        val pool = if (!seasons.isNullOrEmpty()) {
            aired.filter { it.effectiveSeasonNumber() in seasons }.ifEmpty { aired }
        } else {
            aired.filter { video -> video.effectiveSeasonNumber()?.let { it > 0 } == true }.ifEmpty { aired }
        }
        if (pool.isEmpty()) return null
        val history = recent.getOrPut(meta.id) { ArrayDeque() }
        val fresh = pool.filterNot { it.id in history }.ifEmpty { pool }
        val choice = fresh.random(Random.Default)
        history.addLast(choice.id)
        // Remember up to half the pool, so small shows still get variety without running dry.
        while (history.size > (pool.size / 2).coerceAtLeast(1)) history.removeFirst()
        return choice
    }
}
