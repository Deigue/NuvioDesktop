package com.nuvio.app.features.playlist

import co.touchlab.kermit.Logger
import com.nuvio.app.features.watchprogress.isWatchProgressComplete
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ActivePlaylistPlayback(
    val playlistId: String,
    val entryId: String,
)

/**
 * Which playlist entry is being played right now, if any.
 *
 * Launches carry only `PlayerAutoPlayMode.Playlist`; the playlist and entry live here instead of
 * being threaded through the dozen `PlayerLaunch` construction sites between the streams screen and
 * the player. The mode is what makes the player consult this at all, so a stale session can never
 * hijack an unrelated launch — but it is still cleared whenever something else is played from a
 * playlist-less path, see [clear].
 */
object PlaylistPlaybackSession {
    internal val log = Logger.withTag("PlaylistPlayback")

    private val _active = MutableStateFlow<ActivePlaylistPlayback?>(null)
    val active: StateFlow<ActivePlaylistPlayback?> = _active.asStateFlow()

    /**
     * The speed the last entry was playing at. Every entry opens a fresh player, which would
     * otherwise start at the default speed; carried for the length of the session.
     */
    var playbackSpeed: Float? = null

    private var lastPositionMs = 0L
    private var lastDurationMs = 0L

    fun current(): ActivePlaylistPlayback? = _active.value

    fun start(playlistId: String, entryId: String) {
        log.i { "start playlist=$playlistId entry=$entryId" }
        _active.value = ActivePlaylistPlayback(playlistId, entryId)
        lastPositionMs = 0L
        lastDurationMs = 0L
        PlaylistRepository.setResumeEntry(playlistId, entryId)
    }

    fun clear() {
        if (_active.value != null) log.i { "cleared" }
        _active.value = null
        playbackSpeed = null
        lastPositionMs = 0L
        lastDurationMs = 0L
    }

    fun currentEntry(): PlaylistEntry? {
        val active = _active.value ?: return null
        return PlaylistRepository.get(active.playlistId)?.entries?.firstOrNull { it.entryId == active.entryId }
    }

    fun currentPlaylist(): Playlist? = _active.value?.let { PlaylistRepository.get(it.playlistId) }

    /** The entry that will play after the current one, or null at the end of a non-looping list. */
    fun upNext(): PlaylistEntry? {
        val active = _active.value ?: return null
        return PlaylistRepository.entryAfter(active.playlistId, active.entryId)
    }

    /** Fed by the player while a playlist entry plays, so leaving it late counts as finishing it. */
    fun reportProgress(positionMs: Long, durationMs: Long) {
        if (_active.value == null || durationMs <= 0L) return
        lastPositionMs = positionMs
        lastDurationMs = durationMs
    }

    /**
     * The current entry played to its end (or the viewer skipped ahead to the next). Moves the
     * resume pointer past it and returns what should play next, without starting it.
     */
    fun finishCurrent(): PlaylistEntry? {
        val active = _active.value ?: return null
        // Looked up first: with "remove when watched", marking it finished takes the entry out.
        val next = PlaylistRepository.entryAfter(active.playlistId, active.entryId)
        PlaylistRepository.markFinished(active.playlistId, active.entryId)
        log.i { "finished entry=${active.entryId} next=${next?.entryId ?: "<end>"}" }
        return next
    }

    /**
     * The player was left before the entry ended. If it was watched far enough to count as watched
     * (the same rule that marks watch progress complete), Play should move on; otherwise it resumes
     * this entry. Either way the session ends: backing out of the player is leaving the playlist.
     */
    fun onPlayerExited() {
        leaveCurrent()
        clear()
    }

    /**
     * The viewer jumped to another entry from the player. The one being left counts as finished if
     * it was watched far enough, as on exit, but the session carries on.
     */
    fun leaveCurrent() {
        val active = _active.value ?: return
        val completed = isWatchProgressComplete(
            positionMs = lastPositionMs,
            durationMs = lastDurationMs,
            isEnded = false,
        )
        log.i {
            "player exited entry=${active.entryId} pos=$lastPositionMs dur=$lastDurationMs completed=$completed"
        }
        if (completed) PlaylistRepository.markFinished(active.playlistId, active.entryId)
        lastPositionMs = 0L
        lastDurationMs = 0L
    }
}
