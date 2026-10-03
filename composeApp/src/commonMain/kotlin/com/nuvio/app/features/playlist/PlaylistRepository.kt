package com.nuvio.app.features.playlist

import co.touchlab.kermit.Logger
import com.nuvio.app.features.library.LibraryClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The user's playlists for the active profile. Follows the CollectionRepository shape: an in-memory
 * StateFlow that is the source of truth, loaded lazily and written through on every change.
 */
object PlaylistRepository {
    private val log = Logger.withTag("Playlists")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    private var hasLoaded = false

    fun ensureLoaded() {
        if (hasLoaded) return
        hasLoaded = true
        val payload = PlaylistStorage.loadPayload()
        if (payload.isNullOrBlank()) return
        runCatching {
            _playlists.value = json.decodeFromString<List<Playlist>>(payload).map(::sanitized)
        }.onFailure { error ->
            log.e(error) { "Failed to load playlists from storage" }
        }
    }

    fun onProfileChanged() {
        hasLoaded = false
        _playlists.value = emptyList()
        PlaylistPlaybackSession.clear()
    }

    fun clearLocalState() = onProfileChanged()

    fun get(playlistId: String): Playlist? {
        ensureLoaded()
        return _playlists.value.firstOrNull { it.id == playlistId }
    }

    @OptIn(ExperimentalUuidApi::class)
    fun create(name: String): Playlist {
        ensureLoaded()
        val now = LibraryClock.nowEpochMs()
        val playlist = Playlist(
            id = Uuid.random().toString(),
            name = name.trim().ifBlank { "Playlist" },
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
        )
        _playlists.value = _playlists.value + playlist
        persist()
        return playlist
    }

    fun rename(playlistId: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        update(playlistId) { it.copy(name = trimmed) }
    }

    fun delete(playlistId: String) {
        ensureLoaded()
        _playlists.value = _playlists.value.filterNot { it.id == playlistId }
        if (PlaylistPlaybackSession.current()?.playlistId == playlistId) PlaylistPlaybackSession.clear()
        persist()
    }

    fun setLoop(playlistId: String, loop: Boolean) {
        update(playlistId) { it.copy(loop = loop) }
    }

    fun setPreferLocalLibrary(playlistId: String, preferLocal: Boolean) {
        update(playlistId) { it.copy(preferLocalLibrary = preferLocal) }
    }

    fun setRemoveWatched(playlistId: String, removeWatched: Boolean) {
        update(playlistId) { it.copy(removeWatched = removeWatched) }
    }

    /**
     * Shuffle on remembers the current order and shuffles (see [PlaylistQueue.shuffle]); shuffle off
     * restores it. The resume pointer follows its entry either way.
     */
    fun setShuffled(playlistId: String, shuffled: Boolean) {
        update(playlistId) { playlist ->
            if (shuffled == playlist.shuffled) return@update playlist
            if (shuffled) {
                val (order, resume) = PlaylistQueue.shuffle(playlist.entries)
                playlist.copy(
                    entries = order,
                    resumeIndex = resume,
                    shuffleOriginalOrder = playlist.entries.map { it.entryId },
                )
            } else {
                val resumeId = playlist.resumeEntry?.entryId
                val order = PlaylistQueue.unshuffle(playlist.entries, playlist.shuffleOriginalOrder.orEmpty()) { it.entryId }
                playlist.copy(
                    entries = order,
                    resumeIndex = order.indexOfFirst { it.entryId == resumeId }.coerceAtLeast(0),
                    shuffleOriginalOrder = null,
                )
            }
        }
    }

    /**
     * Appends [entries], skipping any video already in the playlist so adding a season twice (or a
     * season that overlaps an episode added by hand) does not double it up. Random-episode slots are
     * exempt: several slots for one show is the point of a "channel". Returns how many were
     * actually added.
     */
    @OptIn(ExperimentalUuidApi::class)
    fun addEntries(playlistId: String, entries: List<PlaylistEntry>): Int {
        var added = 0
        update(playlistId) { playlist ->
            val existing = playlist.entries.mapTo(mutableSetOf()) { it.videoId }
            val now = LibraryClock.nowEpochMs()
            val fresh = entries
                .filter { it.randomEpisode || existing.add(it.videoId) }
                .map { it.copy(entryId = Uuid.random().toString(), addedAtEpochMs = now) }
            added = fresh.size
            playlist.copy(entries = playlist.entries + fresh)
        }
        return added
    }

    fun removeEntry(playlistId: String, entryId: String) {
        update(playlistId) { playlist ->
            val index = playlist.entries.indexOfFirst { it.entryId == entryId }
            if (index < 0) return@update playlist
            val remaining = playlist.entries.filterIndexed { i, _ -> i != index }
            playlist.copy(
                entries = remaining,
                resumeIndex = PlaylistQueue.resumeIndexAfterRemoval(playlist.resumeIndex, index, remaining.size),
            )
        }
    }

    fun moveEntry(playlistId: String, fromIndex: Int, toIndex: Int) {
        update(playlistId) { playlist ->
            val moved = PlaylistQueue.move(playlist.entries, fromIndex, toIndex)
            if (moved === playlist.entries) return@update playlist
            playlist.copy(
                entries = moved,
                resumeIndex = PlaylistQueue.resumeIndexAfterMove(playlist.resumeIndex, fromIndex, toIndex),
            )
        }
    }

    fun setResumeEntry(playlistId: String, entryId: String) {
        update(playlistId) { playlist ->
            val index = playlist.entries.indexOfFirst { it.entryId == entryId }
            if (index < 0 || index == playlist.resumeIndex) playlist else playlist.copy(resumeIndex = index)
        }
    }

    /**
     * Marks [entryId] as watched through: Play will now start from the entry after it. With
     * [Playlist.removeWatched] the entry is dropped instead — except a random-episode slot, which
     * is a standing "channel" slot rather than a thing to get through.
     */
    fun markFinished(playlistId: String, entryId: String) {
        update(playlistId) { playlist ->
            val index = playlist.entries.indexOfFirst { it.entryId == entryId }
            if (index < 0) return@update playlist
            if (playlist.removeWatched && !playlist.entries[index].randomEpisode) {
                val remaining = playlist.entries.filterIndexed { i, _ -> i != index }
                return@update playlist.copy(
                    entries = remaining,
                    resumeIndex = PlaylistQueue.resumeIndexAfterFinishingRemoved(index, remaining.size),
                )
            }
            playlist.copy(
                resumeIndex = PlaylistQueue.resumeIndexAfterFinishing(playlist.entries.size, index, playlist.loop),
            )
        }
    }

    /** The entry that follows [entryId] in play order, honouring loop. */
    fun entryAfter(playlistId: String, entryId: String): PlaylistEntry? {
        val playlist = get(playlistId) ?: return null
        val index = playlist.entries.indexOfFirst { it.entryId == entryId }
        val next = PlaylistQueue.nextIndex(playlist.entries.size, index, playlist.loop) ?: return null
        return playlist.entries[next]
    }

    private fun update(playlistId: String, transform: (Playlist) -> Playlist) {
        ensureLoaded()
        var changed = false
        _playlists.value = _playlists.value.map { playlist ->
            if (playlist.id != playlistId) return@map playlist
            val updated = transform(playlist)
            if (updated == playlist) {
                playlist
            } else {
                changed = true
                updated.copy(updatedAtEpochMs = LibraryClock.nowEpochMs())
            }
        }
        if (changed) persist()
    }

    private fun sanitized(playlist: Playlist): Playlist = playlist.copy(
        resumeIndex = playlist.resumeIndex.coerceIn(0, (playlist.entries.size - 1).coerceAtLeast(0)),
    )

    private fun persist() {
        runCatching {
            PlaylistStorage.savePayload(json.encodeToString(_playlists.value))
        }.onFailure { error ->
            log.e(error) { "Failed to persist playlists" }
        }
    }
}
