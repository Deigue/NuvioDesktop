package com.nuvio.app.features.playlist

import com.nuvio.app.features.cloud.CloudLibraryContentType
import com.nuvio.app.features.player.skip.PlayerNextEpisodeRules
import com.nuvio.app.features.watchprogress.ContinueWatchingItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The Continue Watching / Next Up rows exactly as Home last drew them, so "Add all to playlist" from
 * a card's menu queues what the viewer is looking at. The rows are derived inside HomeScreen from
 * several repositories, too entangled to recompute here.
 */
object PlaylistContinueWatchingSnapshot {
    private val _rows = MutableStateFlow<Pair<List<ContinueWatchingItem>, List<ContinueWatchingItem>>>(emptyList<ContinueWatchingItem>() to emptyList())

    /** First: Continue Watching (or the merged row). Second: the separate Next Up row, if any. */
    val rows: StateFlow<Pair<List<ContinueWatchingItem>, List<ContinueWatchingItem>>> = _rows.asStateFlow()

    fun publish(continueWatching: List<ContinueWatchingItem>, nextUp: List<ContinueWatchingItem>) {
        _rows.value = continueWatching to nextUp
    }

    /** The row [item] sits in. */
    fun rowOf(item: ContinueWatchingItem): List<ContinueWatchingItem> {
        val (continueWatching, nextUp) = _rows.value
        return if (nextUp.any { it.videoId == item.videoId }) nextUp else continueWatching
    }
}

/**
 * A Continue Watching card is already episode-scoped, so it maps straight onto an entry with the
 * ids that card's own Play uses. Null for what cannot be queued: cloud-library files (no addon
 * identity) and Next Up cards for episodes that have not aired yet.
 */
fun ContinueWatchingItem.toPlaylistEntryOrNull(): PlaylistEntry? {
    if (parentMetaType.equals(CloudLibraryContentType, ignoreCase = true)) return null
    if (isReleaseAlert || (!released.isNullOrBlank() && !PlayerNextEpisodeRules.hasEpisodeAired(released))) return null
    return PlaylistEntry(
        entryId = "",
        type = parentMetaType,
        videoId = videoId,
        parentMetaId = parentMetaId,
        parentMetaType = parentMetaType,
        title = title,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        episodeTitle = episodeTitle?.takeIf { it.isNotBlank() },
        episodeThumbnail = episodeThumbnail,
        poster = poster,
        background = background,
        logo = logo,
        description = pauseDescription,
    )
}

fun playlistAddTargetForContinueWatching(items: List<ContinueWatchingItem>, label: String): PlaylistAddTarget? {
    val entries = items.mapNotNull { it.toPlaylistEntryOrNull() }
    if (entries.isEmpty()) return null
    return PlaylistAddTarget.Entries(label = label, entries = entries)
}
