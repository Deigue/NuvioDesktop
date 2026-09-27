package com.nuvio.app.core.ui

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Upstream's synced card depth (`card_depth_style_settings_payload`), a JSON string of exactly
 * this shape (NuvioMobile `StoredCardDepthStylePreferences`). The fork keeps the same nine values
 * inside [PosterCardStyleRepository], so this only translates field names.
 */
@Serializable
private data class OfficialCardDepthPayload(
    val enabled: Boolean = false,
    val edgeStrength: Int = 28,
    val sheenStrength: Int = 10,
    val edgeCoverage: Int = 0,
    val postersEnabled: Boolean = true,
    val continueWatchingEnabled: Boolean = true,
    val episodeCardsEnabled: Boolean = true,
    val castEnabled: Boolean = true,
    val trailersEnabled: Boolean = true,
)

internal object CardDepthSync {
    const val PAYLOAD_KEY = "card_depth_style_settings_payload"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun exportPayload(): String {
        val state = PosterCardStyleRepository.uiState.value
        return json.encodeToString(
            OfficialCardDepthPayload.serializer(),
            OfficialCardDepthPayload(
                enabled = state.depthEnabled,
                edgeStrength = state.depthEdgeStrength,
                sheenStrength = state.depthSheenStrength,
                edgeCoverage = state.depthEdgeCoverage,
                postersEnabled = state.depthPosters,
                continueWatchingEnabled = state.depthContinueWatching,
                episodeCardsEnabled = state.depthEpisodes,
                castEnabled = state.depthCast,
                trailersEnabled = state.depthTrailers,
            ),
        )
    }

    /**
     * Applies a remote payload. A blank one means the writer never had card depth set, and is
     * ignored rather than read as upstream's defaults, which differ from this fork's (edge 28 vs 42,
     * coverage 0 vs 64) and would quietly restyle every poster.
     */
    fun applyPayload(payload: String) {
        val trimmed = payload.trim()
        if (trimmed.isEmpty()) return
        val remote = runCatching { json.decodeFromString(OfficialCardDepthPayload.serializer(), trimmed) }
            .getOrNull() ?: return
        PosterCardStyleRepository.applyDepth(
            enabled = remote.enabled,
            edgeStrength = remote.edgeStrength,
            sheenStrength = remote.sheenStrength,
            edgeCoverage = remote.edgeCoverage,
            posters = remote.postersEnabled,
            continueWatching = remote.continueWatchingEnabled,
            episodes = remote.episodeCardsEnabled,
            cast = remote.castEnabled,
            trailers = remote.trailersEnabled,
        )
    }

    /** What a push would carry, for the sync observer's change signature. */
    fun signature(): String = exportPayload()
}
