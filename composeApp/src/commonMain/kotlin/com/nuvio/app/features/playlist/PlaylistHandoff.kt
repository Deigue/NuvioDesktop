package com.nuvio.app.features.playlist

import com.nuvio.app.features.downloads.DownloadItem
import com.nuvio.app.features.streams.StreamItem

/**
 * What the player resolved for the playlist's next entry, handed to the app layer to open.
 *
 * The player finds the source itself, in the background and with the same auto-play pipeline as a
 * binge next-episode advance. What it cannot do is become a different title in place: everything
 * from skip lookups to scrobbling is keyed on the launch's parent meta. So the app replaces the
 * player route with a fresh one for the resolved source — no streams screen in between, except for
 * [SourceList], where nothing could be picked automatically.
 */
sealed interface PlaylistHandoff {
    val entry: PlaylistEntry

    data class Stream(override val entry: PlaylistEntry, val stream: StreamItem) : PlaylistHandoff

    data class Downloaded(override val entry: PlaylistEntry, val item: DownloadItem) : PlaylistHandoff

    /** No auto-selectable source: open the entry's normal source list. */
    data class SourceList(override val entry: PlaylistEntry) : PlaylistHandoff
}
