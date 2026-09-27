package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.library.LibraryRepository

/**
 * Answers "is this poster's title in the library?" for the optional watchlist badge.
 *
 * [Off] is what every card gets while the badge setting is off: no library subscription and no
 * lookup, so the default costs a poster nothing. With it on, the answer comes from the library's
 * own key set, which the repository rebuilds on every publish — a saved title shows its bookmark
 * as soon as the library says so, including after a remote provider applies the change.
 */
internal fun interface PosterWatchlistMembership {
    fun contains(item: MetaPreview): Boolean

    companion object {
        val Off = PosterWatchlistMembership { false }
    }
}

@Composable
internal fun rememberPosterWatchlistMembership(): PosterWatchlistMembership {
    val posterCardStyle = rememberPosterCardStyleUiState()
    if (!posterCardStyle.watchlistBadgeEnabled) return PosterWatchlistMembership.Off
    val libraryState by remember {
        LibraryRepository.ensureLoaded()
        LibraryRepository.uiState
    }.collectAsStateWithLifecycle()
    val savedKeys = libraryState.savedKeys
    return remember(savedKeys) {
        PosterWatchlistMembership { item -> libraryState.contains(item.id, item.type) }
    }
}
