package com.nuvio.app.features.posterservice

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.watchprogress.ContinueWatchingItem

/**
 * [withCachedCustomPosters] for lists a screen assembles itself rather than reading from a
 * repository view (details-page collection parts, cast/crew credits). Re-applies when the settings
 * or [CustomPosterIdWarmer] change, and hands back [items] itself when nothing applies.
 */
@Composable
internal fun rememberCustomPosters(items: List<MetaPreview>, screen: CustomPosterScreen): List<MetaPreview> {
    remember { CustomPosterSettingsRepository.ensureLoaded() }
    val settings by CustomPosterSettingsRepository.uiState.collectAsState()
    val version by CustomPosterIdWarmer.version.collectAsState()
    return remember(items, settings, screen, version) {
        items.withCachedCustomPosters(settings.forScreen(screen), CustomPosterKeys.snapshot())
    }
}

/** [rememberCustomPosters] for the Continue Watching row. */
@Composable
internal fun rememberContinueWatchingCustomPosters(items: List<ContinueWatchingItem>): List<ContinueWatchingItem> {
    remember { CustomPosterSettingsRepository.ensureLoaded() }
    val settings by CustomPosterSettingsRepository.uiState.collectAsState()
    val version by CustomPosterIdWarmer.version.collectAsState()
    return remember(items, settings, version) {
        items.withCachedCustomPosters(settings.forScreen(CustomPosterScreen.ContinueWatching), CustomPosterKeys.snapshot())
    }
}
