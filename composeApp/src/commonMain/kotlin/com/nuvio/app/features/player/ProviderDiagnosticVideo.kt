package com.nuvio.app.features.player

internal data class ProviderDiagnosticVideo(
    val sourceUrl: String,
)

/**
 * Recognises URLs that explicitly describe themselves as a generated error/status video.
 * This is intentionally provider-neutral: AIOStreams-style `title`/`body` URLs and ordinary
 * `/error.*` or `/status.*` assets should not need an addon-specific allow-list.
 */
internal fun isExplicitProviderDiagnosticVideoUrl(sourceUrl: String): Boolean {
    val normalized = sourceUrl.trim().lowercase()
    if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) return false

    val path = normalized.substringBefore('?')
    val query = normalized.substringAfter('?', missingDelimiterValue = "")
    val hasMessageQuery =
        ("title=" in query && ("body=" in query || "message=" in query)) ||
            "error=" in query
    val hasDiagnosticPath = listOf("/error.", "/error/", "/status.", "/status/", "/failed.")
        .any(path::contains)
    val isStremThruStaticStatusVideo = "/store/_/static/" in path && path.endsWith(".mp4")
    return hasMessageQuery || hasDiagnosticPath || isStremThruStaticStatusVideo
}

/**
 * AIOStreams' `/static/` status clips (`StaticFiles` in its server). Its playback resolver runs the
 * whole failover chain inside one request and only 3xx's here when every attempt failed, so the
 * clip is an *outcome* of the stable `/api/v1/debrid/playback/…` URL, never a link of its own.
 */
private val AIOSTREAMS_STATIC_STATUS_CLIPS = setOf(
    "download_failed.mp4",
    "downloading.mp4",
    "unavailable_for_legal_reasons.mp4",
    "store_limit_exceeded.mp4",
    "content_proxy_limit_reached.mp4",
    "500.mp4",
    "429.mp4",
    "403.mp4",
    "401.mp4",
    "no_matching_file.mp4",
    "payment_required.mp4",
    "200.mp4",
)

/**
 * A provider status clip reached by redirect: AIOStreams' `/static/<status>.mp4` set, plus any
 * `.mp4` whose file name says failed/error/status. Such a clip must never be pinned or cached —
 * the source URL that produced it is what a retry re-resolves.
 */
internal fun isProviderStatusClipUrl(url: String): Boolean {
    val path = url.trim()
        .substringAfter("://", missingDelimiterValue = "")
        .substringAfter('/', missingDelimiterValue = "")
        .substringBefore('?')
        .substringBefore('#')
        .lowercase()
    val file = path.substringAfterLast('/')
    if (!file.endsWith(".mp4")) return false
    val parent = path.substringBeforeLast('/', missingDelimiterValue = "").substringAfterLast('/')
    return (parent == "static" && file in AIOSTREAMS_STATIC_STATUS_CLIPS) ||
        file.contains("failed") || file.contains("error") || file.contains("status")
}

/**
 * Any URL that is a provider's error/status placeholder rather than the media: an encoded
 * rate-limit placeholder, a self-describing diagnostic video, or a redirect-target status clip.
 * Such URLs describe one failed resolve, so they must never be remembered as "the link" for a
 * title — reusing one replays the failure long after the provider has recovered.
 */
internal fun isPlaybackPlaceholderUrl(url: String): Boolean =
    playbackSourceFailure(url) != null ||
        isExplicitProviderDiagnosticVideoUrl(url) ||
        isProviderStatusClipUrl(url)

/**
 * Playback endpoints known to resolve dynamically to either full media or a small status video.
 * The URL shapes are protocol-level signatures; no addon hostname allow-list is required.
 */
internal fun isProviderPlaybackEndpoint(sourceUrl: String): Boolean {
    val normalized = sourceUrl.trim().lowercase()
    if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) return false
    val path = normalized.substringBefore('?')
    val query = normalized.substringAfter('?', missingDelimiterValue = "")
    val isCometPlayback = "/playback/" in path && "torrent_name=" in query && "name=" in query
    val isStremThruStorePlayback =
        (("/stremio/wrap/" in path || "/stremio/torz/" in path) && "/_/strem/" in path) ||
            ("/stremio/newz/" in path && "/playback/" in path)
    return isCometPlayback || isStremThruStorePlayback
}

/**
 * Resolves a response only when it is a complete, small, verified video. It is used as a narrow
 * provider-endpoint probe and after major startup failures; returned media must never scrobble.
 */
internal expect suspend fun resolveProviderDiagnosticVideo(
    sourceUrl: String,
    sourceHeaders: Map<String, String>,
): ProviderDiagnosticVideo?

internal expect fun releaseProviderDiagnosticVideo(sourceUrl: String)

internal fun shouldSkipProviderDiagnosticVideo(streamFailoverEnabled: Boolean): Boolean =
    streamFailoverEnabled

private const val PROVIDER_WAIT_VIDEO_DURATION_MS = 120_000L
private const val PROVIDER_WAIT_VIDEO_DURATION_TOLERANCE_MS = 1_500L

/**
 * Detects the debrid "file is being downloaded" status clip after mpv has exposed its media
 * metadata. The resume mismatch keeps this deliberately narrow: an actual two-minute episode is
 * not diagnostic, but a two-minute file cannot satisfy a resume point from later in a normal
 * episode. Native code independently blocks VapourSynth for the duration signature so detection
 * remains crash-safe before this common-layer callback runs.
 */
internal fun isLikelyProviderWaitVideo(
    durationMs: Long,
    requestedResumePositionMs: Long,
    isSeries: Boolean,
): Boolean =
    isSeries &&
        durationMs in
            (PROVIDER_WAIT_VIDEO_DURATION_MS - PROVIDER_WAIT_VIDEO_DURATION_TOLERANCE_MS)..
            (PROVIDER_WAIT_VIDEO_DURATION_MS + PROVIDER_WAIT_VIDEO_DURATION_TOLERANCE_MS) &&
        requestedResumePositionMs > durationMs + 1_000L

internal fun PlayerScreenRuntime.activateProviderDiagnosticVideo(diagnostic: ProviderDiagnosticVideo) {
    removeFailedStreamFromCache()
    hasRequestedScrobbleStartForCurrentItem = false
    scrobbleStartRequestGeneration += 1L
    pendingScrobbleStartAfterSeek = false
    currentTrackingScrobbleMedia = null
    providerDiagnosticVideoSourceUrl = diagnostic.sourceUrl
    providerDiagnosticProbePendingSourceUrl = null
    activeSourceAudioUrl = null
    activeSourceHeaders = emptyMap()
    activeSourceResponseHeaders = emptyMap()
    activeInitialPositionMs = 0L
    activeInitialProgressFraction = null
    shouldPlay = true
    errorMessage = null
    beginPlaybackAttempt()
    activeSourceUrl = diagnostic.sourceUrl
}
