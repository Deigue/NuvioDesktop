package com.nuvio.app.features.details.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.HistoryEdu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAddCheckCircle
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioBottomSheetActionRow
import com.nuvio.app.core.ui.NuvioBottomSheetDivider
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.dismissNuvioBottomSheet
import com.nuvio.app.core.ui.nuvioSafeBottomPadding
import com.nuvio.app.core.i18n.localizedSeasonEpisodeCode
import com.nuvio.app.features.details.MetaVideo
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpisodeWatchedActionSheet(
    episode: MetaVideo,
    seasonLabel: String,
    isEpisodeWatched: Boolean,
    canMarkPreviousEpisodes: Boolean,
    arePreviousEpisodesWatched: Boolean,
    isSeasonWatched: Boolean,
    onDismiss: () -> Unit,
    onToggleWatched: () -> Unit,
    onTogglePreviousWatched: () -> Unit,
    onToggleSeasonWatched: () -> Unit,
    alternatePlayLabel: String? = null,
    onAlternatePlay: (() -> Unit)? = null,
    // Separate from [onAlternatePlay] on purpose: the source picker is not the "other" of the two
    // local-library routes, it is the one that overrides stream auto-play, so it is offered
    // whether or not this episode has a local file to alternate to.
    onChooseSource: (() -> Unit)? = null,
    /**
     * Null when there is no earlier story with synopses behind this episode, or the feature is off.
     * This is the mid-season half of the recap: the boundary is the episode about to be played,
     * which is more precise than "before season N" and is what someone resuming actually wants.
     */
    onRecap: (() -> Unit)? = null,
    /** Queues this episode in a playlist. Null hides the row. */
    onAddToPlaylist: (() -> Unit)? = null,
    /** Queues the episode's whole season in a playlist. Null hides the row. */
    onAddSeasonToPlaylist: (() -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()

    NuvioModalBottomSheet(
        onDismissRequest = {
            coroutineScope.launch {
                dismissNuvioBottomSheet(sheetState = sheetState, onDismiss = onDismiss)
            }
        },
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = nuvioSafeBottomPadding(16.dp)),
        ) {
            EpisodeActionSheetHeader(
                episode = episode,
                seasonLabel = seasonLabel,
            )
            NuvioBottomSheetDivider()
            NuvioBottomSheetActionRow(
                icon = Icons.Default.CheckCircle,
                title = if (isEpisodeWatched) {
                    stringResource(Res.string.episode_mark_unwatched)
                } else {
                    stringResource(Res.string.episode_mark_watched)
                },
                onClick = {
                    onToggleWatched()
                    coroutineScope.launch {
                        dismissNuvioBottomSheet(sheetState = sheetState, onDismiss = onDismiss)
                    }
                },
            )
            if (canMarkPreviousEpisodes) {
                NuvioBottomSheetDivider()
                NuvioBottomSheetActionRow(
                    icon = Icons.Default.DoneAll,
                    title = if (arePreviousEpisodesWatched) {
                        stringResource(Res.string.episode_mark_previous_unwatched)
                    } else {
                        stringResource(Res.string.episode_mark_previous_watched)
                    },
                    onClick = {
                        onTogglePreviousWatched()
                        coroutineScope.launch {
                            dismissNuvioBottomSheet(sheetState = sheetState, onDismiss = onDismiss)
                        }
                    },
                )
            }
            NuvioBottomSheetDivider()
            NuvioBottomSheetActionRow(
                icon = Icons.Default.PlaylistAddCheckCircle,
                title = if (isSeasonWatched) {
                    stringResource(Res.string.episode_mark_season_unwatched, seasonLabel)
                } else {
                    stringResource(Res.string.episode_mark_season_watched, seasonLabel)
                },
                onClick = {
                    onToggleSeasonWatched()
                    coroutineScope.launch {
                        dismissNuvioBottomSheet(sheetState = sheetState, onDismiss = onDismiss)
                    }
                },
            )
            if (alternatePlayLabel != null && onAlternatePlay != null) {
                NuvioBottomSheetDivider()
                NuvioBottomSheetActionRow(
                    icon = Icons.Default.PlayArrow,
                    title = alternatePlayLabel,
                    onClick = {
                        onAlternatePlay()
                        coroutineScope.launch {
                            dismissNuvioBottomSheet(sheetState = sheetState, onDismiss = onDismiss)
                        }
                    },
                )
            }
            if (onRecap != null) {
                NuvioBottomSheetDivider()
                NuvioBottomSheetActionRow(
                    icon = Icons.Default.HistoryEdu,
                    title = stringResource(Res.string.recap_action),
                    onClick = {
                        onRecap()
                        coroutineScope.launch {
                            dismissNuvioBottomSheet(sheetState = sheetState, onDismiss = onDismiss)
                        }
                    },
                )
            }
            if (onChooseSource != null) {
                NuvioBottomSheetDivider()
                NuvioBottomSheetActionRow(
                    icon = Icons.AutoMirrored.Filled.PlaylistPlay,
                    title = stringResource(Res.string.play_choose_source),
                    onClick = {
                        onChooseSource()
                        coroutineScope.launch {
                            dismissNuvioBottomSheet(sheetState = sheetState, onDismiss = onDismiss)
                        }
                    },
                )
            }
            if (onAddToPlaylist != null) {
                NuvioBottomSheetDivider()
                NuvioBottomSheetActionRow(
                    icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                    title = "Add episode to playlist",
                    onClick = {
                        onAddToPlaylist()
                        coroutineScope.launch {
                            dismissNuvioBottomSheet(sheetState = sheetState, onDismiss = onDismiss)
                        }
                    },
                )
            }
            if (onAddSeasonToPlaylist != null) {
                NuvioBottomSheetDivider()
                NuvioBottomSheetActionRow(
                    icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                    title = "Add $seasonLabel to playlist",
                    onClick = {
                        onAddSeasonToPlaylist()
                        coroutineScope.launch {
                            dismissNuvioBottomSheet(sheetState = sheetState, onDismiss = onDismiss)
                        }
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeasonWatchedActionSheet(
    seasonLabel: String,
    isSeasonWatched: Boolean,
    canMarkPreviousSeasons: Boolean,
    onDismiss: () -> Unit,
    onToggleSeasonWatched: () -> Unit,
    onMarkPreviousSeasonsWatched: () -> Unit,
    /**
     * Null when there is nothing to recap or the feature is off — an absent row rather than a
     * disabled one, because a season 1 long-press has no earlier story by definition and a row
     * that is always greyed out there reads as broken.
     */
    onRecap: (() -> Unit)? = null,
    /** Queues the season's released episodes in a playlist. Null hides the row. */
    onAddSeasonToPlaylist: (() -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()

    NuvioModalBottomSheet(
        onDismissRequest = {
            coroutineScope.launch {
                dismissNuvioBottomSheet(sheetState = sheetState, onDismiss = onDismiss)
            }
        },
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = nuvioSafeBottomPadding(16.dp)),
        ) {
            Text(
                text = seasonLabel,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            )
            NuvioBottomSheetDivider()
            NuvioBottomSheetActionRow(
                icon = Icons.Default.PlaylistAddCheckCircle,
                title = if (isSeasonWatched) {
                    stringResource(Res.string.episode_mark_season_unwatched, seasonLabel)
                } else {
                    stringResource(Res.string.episode_mark_season_watched, seasonLabel)
                },
                onClick = {
                    onToggleSeasonWatched()
                    coroutineScope.launch {
                        dismissNuvioBottomSheet(sheetState = sheetState, onDismiss = onDismiss)
                    }
                },
            )
            if (onRecap != null) {
                NuvioBottomSheetDivider()
                NuvioBottomSheetActionRow(
                    icon = Icons.Default.HistoryEdu,
                    title = stringResource(Res.string.recap_action),
                    onClick = {
                        onRecap()
                        coroutineScope.launch {
                            dismissNuvioBottomSheet(sheetState = sheetState, onDismiss = onDismiss)
                        }
                    },
                )
            }
            if (canMarkPreviousSeasons) {
                NuvioBottomSheetDivider()
                NuvioBottomSheetActionRow(
                    icon = Icons.Default.DoneAll,
                    title = stringResource(Res.string.episode_mark_previous_seasons_watched),
                    onClick = {
                        onMarkPreviousSeasonsWatched()
                        coroutineScope.launch {
                            dismissNuvioBottomSheet(sheetState = sheetState, onDismiss = onDismiss)
                        }
                    },
                )
            }
            if (onAddSeasonToPlaylist != null) {
                NuvioBottomSheetDivider()
                NuvioBottomSheetActionRow(
                    icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                    title = "Add $seasonLabel to playlist",
                    onClick = {
                        onAddSeasonToPlaylist()
                        coroutineScope.launch {
                            dismissNuvioBottomSheet(sheetState = sheetState, onDismiss = onDismiss)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun EpisodeActionSheetHeader(
    episode: MetaVideo,
    seasonLabel: String,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = episode.title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = buildString {
                localizedSeasonEpisodeCode(
                    seasonNumber = episode.season,
                    episodeNumber = episode.episode,
                )?.let {
                    append(it)
                    append(" • ")
                }
                append(seasonLabel)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
