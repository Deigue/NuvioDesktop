package com.nuvio.app.features.playlist

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.details.effectiveEpisodeNumber
import com.nuvio.app.features.details.effectiveSeasonNumber
import com.nuvio.app.features.details.streamVideoIdForPlayback
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.watchprogress.buildPlaybackVideoId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Builds entries with exactly the ids the details page would launch with, so a playlist entry finds
 * the same streams, watch progress and downloads as pressing Play on it by hand.
 */
internal object PlaylistEntries {

    fun movie(meta: MetaDetails): PlaylistEntry = PlaylistEntry(
        entryId = "",
        type = meta.type,
        videoId = meta.id,
        parentMetaId = meta.id,
        parentMetaType = meta.type,
        title = meta.name,
        poster = meta.poster,
        background = meta.background,
        logo = meta.logo,
        description = meta.description,
    )

    fun movie(preview: MetaPreview): PlaylistEntry = PlaylistEntry(
        entryId = "",
        type = preview.type,
        videoId = preview.id,
        parentMetaId = preview.id,
        parentMetaType = preview.type,
        title = preview.name,
        poster = preview.poster,
        background = preview.banner,
        logo = preview.logo,
        description = preview.description,
    )

    /** A random-episode slot for [meta]'s show; resolved when reached, see [PlaylistRandomEpisodes]. */
    fun randomSlot(meta: MetaDetails, seasons: List<Int>?): PlaylistEntry = PlaylistEntry(
        entryId = "",
        type = meta.type,
        videoId = meta.id,
        parentMetaId = meta.id,
        parentMetaType = meta.type,
        title = meta.name,
        poster = meta.poster,
        background = meta.background,
        logo = meta.logo,
        description = meta.description,
        randomEpisode = true,
        randomSeasons = seasons?.sorted()?.takeIf { it.isNotEmpty() },
    )

    /** Mirrors `onEpisodePlayClick` on the details page. */
    fun episode(meta: MetaDetails, video: MetaVideo): PlaylistEntry {
        val season = video.effectiveSeasonNumber()
        val episode = video.effectiveEpisodeNumber()
        val playbackVideoId = buildPlaybackVideoId(
            parentMetaId = meta.id,
            seasonNumber = season,
            episodeNumber = episode,
            fallbackVideoId = video.id,
        )
        return PlaylistEntry(
            entryId = "",
            type = meta.type,
            videoId = video.streamVideoIdForPlayback(meta.id, playbackVideoId),
            parentMetaId = meta.id,
            parentMetaType = meta.type,
            title = meta.name,
            seasonNumber = season,
            episodeNumber = episode,
            episodeTitle = video.title.takeIf { it.isNotBlank() },
            episodeThumbnail = video.thumbnail,
            poster = meta.poster,
            background = meta.background,
            logo = meta.logo,
            description = video.overview,
        )
    }
}

/** What the add-to-playlist dialog was opened for. */
sealed interface PlaylistAddTarget {
    /** Ready-made entries: a movie, one episode, or a season picked from the details page. */
    data class Entries(val label: String, val entries: List<PlaylistEntry>) : PlaylistAddTarget

    /**
     * A show picked from a poster, where only the id is known. The dialog loads its episodes and
     * asks which season to add — a show entry on its own is never queued, see [PlaylistEntry].
     */
    data class Series(
        val type: String,
        val id: String,
        val name: String,
    ) : PlaylistAddTarget
}

/**
 * Opens the add-to-playlist dialog from anywhere. The dialog is hosted once at the app root, so
 * the details page, episode menus and poster sheet can all offer "Add to playlist" without each
 * threading a callback up through their screens.
 */
object PlaylistAddController {
    private val _target = MutableStateFlow<PlaylistAddTarget?>(null)
    val target: StateFlow<PlaylistAddTarget?> = _target.asStateFlow()

    fun request(target: PlaylistAddTarget) {
        _target.value = target
    }

    fun dismiss() {
        _target.value = null
    }
}

/** The details page's "Add to playlist": a film is queued as-is, a show asks which season. */
fun playlistAddTargetFor(meta: MetaDetails): PlaylistAddTarget =
    if (meta.videos.isEmpty() && !meta.type.equals("series", ignoreCase = true)) {
        PlaylistAddTarget.Entries(label = meta.name, entries = listOf(PlaylistEntries.movie(meta)))
    } else {
        PlaylistAddTarget.Series(type = meta.type, id = meta.id, name = meta.name)
    }

/** An episode menu's "Add to playlist": that one episode. */
fun playlistAddTargetForEpisode(meta: MetaDetails, video: MetaVideo): PlaylistAddTarget {
    val entry = PlaylistEntries.episode(meta, video)
    return PlaylistAddTarget.Entries(label = entry.displayTitle(), entries = listOf(entry))
}

/** A season menu's "Add season to playlist": its released episodes, in order. */
fun playlistAddTargetForEpisodes(meta: MetaDetails, label: String, videos: List<MetaVideo>): PlaylistAddTarget =
    PlaylistAddTarget.Entries(
        label = "${meta.name} · $label",
        entries = videos.map { PlaylistEntries.episode(meta, it) },
    )
