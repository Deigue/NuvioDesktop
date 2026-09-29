package com.nuvio.app.features.watchprogress

import co.touchlab.kermit.Logger
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.details.nextReleasedEpisodeAfter
import com.nuvio.app.features.player.AnimeContentCache
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.tracking.TrackingMediaKind
import com.nuvio.app.features.tracking.TrackingScrobbleAction
import com.nuvio.app.features.tracking.TrackingScrobbleCoordinator
import com.nuvio.app.features.tracking.TrackingScrobbleEvent
import com.nuvio.app.features.tracking.buildTrackingMediaReference

object ResumePromptRepository {
    private val log = Logger.withTag("ResumePrompt")
    private var uncleanExitRecovered = false

    /**
     * The app was closed (force-quit, crash, power loss) while the player was open, so the player
     * never sent its closing scrobble. Trackers are left holding a bare `start`, which SIMKL and
     * Trakt simply let expire — nothing is saved as a resumable playback, so with a remote Continue
     * Watching source the item drops out of the row even though the local position survived.
     *
     * Sends the stop the player would have sent, from the last locally saved position. Runs once per
     * launch and regardless of the resume-prompt setting; it only reads the flags, which
     * [consumeResumePrompt] (or the next player exit) clears. Returns true if a stop went out, so the
     * caller can refresh Continue Watching.
     */
    suspend fun recoverUncleanPlayerExit(): Boolean {
        if (uncleanExitRecovered) return false
        uncleanExitRecovered = true
        if (!ResumePromptStorage.loadWasInPlayer()) return false
        val videoId = ResumePromptStorage.loadLastPlayerVideoId()?.takeIf { it.isNotBlank() } ?: return false
        WatchProgressRepository.ensureLoaded()
        val entry = WatchProgressRepository.progressForVideo(videoId) ?: return false
        if (!entry.isResumable || entry.lastPositionMs <= 0L || entry.durationMs <= 0L) return false
        val percent = (entry.lastPositionMs.toDouble() / entry.durationMs * 100.0).coerceIn(0.0, 100.0)
        // At/after the completion threshold the player would already have sent its stop.
        if (percent < 0.1 || percent >= 80.0) return false
        val media = buildTrackingMediaReference(
            contentType = entry.contentType.ifBlank { entry.parentMetaType },
            parentMetaId = entry.parentMetaId,
            videoId = entry.videoId,
            title = entry.title,
            seasonNumber = entry.seasonNumber,
            episodeNumber = entry.episodeNumber,
            episodeTitle = entry.episodeTitle,
        ).let { media ->
            if (AnimeContentCache.isAnime(entry.parentMetaId) && media.kind != TrackingMediaKind.MOVIE) {
                media.copy(kind = TrackingMediaKind.ANIME)
            } else {
                media
            }
        }
        val dispatch = runCatching {
            TrackingScrobbleCoordinator.scrobble(
                profileId = ProfileRepository.activeProfileId,
                action = TrackingScrobbleAction.STOP,
                event = TrackingScrobbleEvent(
                    media = media,
                    progressPercent = percent,
                    positionSeconds = entry.lastPositionMs / 1000L,
                    durationSeconds = entry.durationMs / 1000L,
                    isPauseRatherThanStop = true,
                ),
            )
        }.onFailure { error ->
            if (error is kotlinx.coroutines.CancellationException) throw error
            log.w(error) { "unclean-exit recovery scrobble failed for $videoId" }
        }.getOrNull() ?: return false
        log.i { "unclean player exit: re-sent stop for $videoId at ${"%.1f".format(percent)}% (sent=${dispatch.sentCount})" }
        return dispatch.sentCount > 0
    }

    fun markPlayerEntered(videoId: String) {
        ResumePromptStorage.saveWasInPlayer(true)
        ResumePromptStorage.saveLastPlayerVideoId(videoId)
    }

    fun markPlayerExitedNormally() {
        ResumePromptStorage.saveWasInPlayer(false)
        ResumePromptStorage.saveLastPlayerVideoId(null)
    }

    suspend fun consumeResumePrompt(): ContinueWatchingItem? {
        val wasInPlayer = ResumePromptStorage.loadWasInPlayer()
        if (!wasInPlayer) return null

        val videoId = ResumePromptStorage.loadLastPlayerVideoId()
        ResumePromptStorage.saveWasInPlayer(false)
        ResumePromptStorage.saveLastPlayerVideoId(null)

        if (videoId.isNullOrBlank()) return null

        WatchProgressRepository.ensureLoaded()
        val entry = WatchProgressRepository.progressForVideo(videoId) ?: return null

        if (entry.isResumable) {
            return entry.toContinueWatchingItem()
        }

        if (!entry.isEpisode) return null

        val meta = MetaDetailsRepository.fetch(
            type = entry.parentMetaType,
            id = entry.parentMetaId,
        ) ?: return null

        val nextEpisode = meta.nextReleasedEpisodeAfter(
            seasonNumber = entry.seasonNumber,
            episodeNumber = entry.episodeNumber,
            todayIsoDate = CurrentDateProvider.todayIsoDate(),
        ) ?: return null

        return entry.toUpNextContinueWatchingItem(nextEpisode)
    }
}
