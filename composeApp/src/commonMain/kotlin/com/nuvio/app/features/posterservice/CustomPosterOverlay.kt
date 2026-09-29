package com.nuvio.app.features.posterservice

import co.touchlab.kermit.Logger
import com.nuvio.app.features.cloud.CloudLibraryContentType
import com.nuvio.app.features.playlist.PlaylistContentType
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.metadata.isAnimeNativeId
import com.nuvio.app.features.tmdb.TmdbService
import com.nuvio.app.features.watchprogress.ContinueWatchingItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * [withResolvedCustomPosters] for paths that must not wait: Home rows, Search, Collections, and
 * See All publish on every page and every few seconds, so the counterpart id a template needs is
 * read from TMDB's external-id cache only. A miss keeps the original art for now and queues the
 * lookup with [CustomPosterIdWarmer], whose version bump re-derives the screen once it lands — one
 * `/find` per title, ever, since the cache persists.
 */
internal fun MetaPreview.withCachedCustomPosters(
    settings: CustomPosterSettings,
    keys: CustomPosterKeys,
): MetaPreview {
    if (!settings.isActive || type == PlaylistContentType) return this
    val lookupId = metadataId
    val lookupType = metaLookupType?.takeIf { it.isNotBlank() } ?: type
    if (lookupId.isAnimeNativeId() && !settings.customPosterTemplateUsesNativeAnimeId()) return this
    val imdbFromId = lookupId.takeIf { it.startsWith("tt") }?.substringBefore(':')
    val tmdbFromId = lookupId.takeIf { it.startsWith("tmdb:") }?.removePrefix("tmdb:")?.substringBefore(":")?.toIntOrNull()
        ?: posterServiceAddonTmdbId()

    var tmdb = tmdbFromId
    var imdb = imdbFromId
    if (tmdb == null && imdb != null && settings.customPosterTemplateNeedsTmdbId()) {
        tmdb = TmdbService.peekImdbToTmdb(imdb, lookupType)?.toIntOrNull()
        if (tmdb == null) CustomPosterIdWarmer.request(lookupId, lookupType)
    }
    if (imdb == null && tmdb != null && settings.customPosterTemplateNeedsImdbId()) {
        imdb = TmdbService.peekTmdbToImdb(tmdb, lookupType)?.takeIf { it.isNotBlank() }
        if (imdb == null) CustomPosterIdWarmer.request(lookupId, lookupType)
    }
    // The PostersPlus 400 trap, as in [withResolvedCustomPosters]: a blank tmdb_id beside a filled
    // {id} is rejected, so wait for the mapping rather than swap working art for a broken image.
    if (tmdb == null && settings.customPosterTemplateNeedsTmdbId()) return this
    return withCustomPosters(settings = settings, imdbId = imdb, tmdbId = tmdb, keys = keys)
}

/** Returns the same list instance when nothing applies, so unchanged screens stay unchanged. */
internal fun List<MetaPreview>.withCachedCustomPosters(
    settings: CustomPosterSettings,
    keys: CustomPosterKeys,
): List<MetaPreview> {
    if (!settings.isActive || isEmpty()) return this
    return map { it.withCachedCustomPosters(settings, keys) }
}

/**
 * Background TMDB `/find` lookups for ids a poster template needs but a cached-only overlay could
 * not supply. Each title is asked for once per session; [version] ticks (debounced) as results land
 * so derived screens re-apply the overlay.
 */
internal object CustomPosterIdWarmer {
    private val log = Logger.withTag("CustomPosterIdWarmer")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val permits = Semaphore(2)
    private val requested = HashSet<String>()
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()
    private var bumpJob: Job? = null
    private var hydrationRequested = false

    fun request(id: String, type: String) {
        val key = "$type|$id"
        synchronized(requested) {
            if (!requested.add(key)) return
            if (!hydrationRequested) {
                hydrationRequested = true
                // The persisted cache usually already holds the answer; peeks miss until it is read.
                TmdbService.hydrateExternalIdCacheAsync()
            }
        }
        scope.launch {
            val found = permits.withPermit {
                runCatching {
                    when {
                        id.startsWith("tt") -> TmdbService.ensureTmdbId(id.substringBefore(':'), type) != null
                        id.startsWith("tmdb:") -> id.removePrefix("tmdb:").substringBefore(':').toIntOrNull()
                            ?.let { TmdbService.tmdbToImdb(it, type) } != null
                        else -> false
                    }
                }.getOrElse { error ->
                    log.d { "id lookup failed for $id: ${error.message}" }
                    false
                }
            }
            if (found) scheduleBump()
        }
    }

    private fun scheduleBump() {
        synchronized(requested) {
            if (bumpJob?.isActive == true) return
            bumpJob = scope.launch {
                delay(BUMP_DEBOUNCE_MS)
                _version.value += 1
            }
        }
    }

    private const val BUMP_DEBOUNCE_MS = 750L
}

/**
 * A read-only view of [source] with the poster service applied for [screen]. The repository keeps
 * publishing raw state (and its own caches never hold poster-service URLs); readers of this view
 * get the overlay, re-derived when the source, the settings, or [CustomPosterIdWarmer] change.
 * Unlike `stateIn`, [value] is computed on read, so it is never a frame behind the source.
 */
internal fun <T> StateFlow<T>.withCustomPosterOverlay(
    screen: CustomPosterScreen,
    transform: T.(CustomPosterSettings, CustomPosterKeys) -> T,
): StateFlow<T> = CustomPosterOverlayStateFlow(this, screen, transform)

@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
private class CustomPosterOverlayStateFlow<T>(
    private val source: StateFlow<T>,
    private val screen: CustomPosterScreen,
    private val transform: T.(CustomPosterSettings, CustomPosterKeys) -> T,
) : StateFlow<T> {
    private class Memo<T>(val source: T, val settings: CustomPosterSettings, val version: Int, val result: T)

    @Volatile
    private var memo: Memo<T>? = null

    private fun derive(value: T, settings: CustomPosterSettings, version: Int): T {
        memo?.let { if (it.source === value && it.settings == settings && it.version == version) return it.result }
        val screenSettings = settings.forScreen(screen)
        val result = if (screenSettings.isActive) value.transform(screenSettings, CustomPosterKeys.snapshot()) else value
        memo = Memo(value, settings, version, result)
        return result
    }

    override val value: T
        get() = derive(
            source.value,
            CustomPosterSettingsRepository.snapshot(),
            CustomPosterIdWarmer.version.value,
        )

    override val replayCache: List<T>
        get() = listOf(value)

    override suspend fun collect(collector: FlowCollector<T>): Nothing {
        CustomPosterSettingsRepository.ensureLoaded()
        combine(
            source,
            CustomPosterSettingsRepository.uiState,
            CustomPosterIdWarmer.version,
        ) { value, settings, version -> derive(value, settings, version) }
            .distinctUntilChanged { old, new -> old === new }
            .collect(collector)
        awaitCancellation()
    }
}

/**
 * Resolves (and caches) the ids [settings]' templates need for this row, without applying any art,
 * so a later [withCachedCustomPosters] finds them. For builders that already spend a coroutine per
 * item — Discover — this keeps first paint styled while leaving the art itself to read time, where
 * a template edit or screen toggle takes effect immediately instead of after the next rebuild.
 */
internal suspend fun MetaPreview.prefetchCustomPosterIds(settings: CustomPosterSettings): MetaPreview {
    if (!settings.isActive || type == PlaylistContentType) return this
    val lookupId = metadataId
    if (lookupId.isAnimeNativeId() && !settings.customPosterTemplateUsesNativeAnimeId()) return this
    runCatching {
        resolveCustomPosterIds(
            settings = settings,
            imdbId = lookupId.takeIf { it.startsWith("tt") },
            tmdbId = lookupId.takeIf { it.startsWith("tmdb:") }?.removePrefix("tmdb:")?.substringBefore(":")?.toIntOrNull()
                ?: posterServiceAddonTmdbId(),
            type = metaLookupType?.takeIf { it.isNotBlank() } ?: type,
        )
    }
    return this
}

/**
 * Continue Watching rows are keyed by the parent title, so the poster service sees them the way it
 * sees a catalog item of that title. Cloud-library rows are skipped: their ids name files.
 */
internal fun ContinueWatchingItem.withCachedCustomPosters(
    settings: CustomPosterSettings,
    keys: CustomPosterKeys,
): ContinueWatchingItem {
    if (!settings.isActive || parentMetaType.equals(CloudLibraryContentType, ignoreCase = true)) return this
    // A watch-progress row carries only its own id; the details cache usually holds the addon's
    // TMDB/TVDB ids for it (AIOMetadata `_tmdbId`), which no IMDb lookup can recover for titles
    // whose TMDB record lacks an IMDb link.
    val known = MetaDetailsRepository.peekAny(parentMetaType, parentMetaId)
    val serviced = MetaPreview(
        id = parentMetaId,
        type = parentMetaType,
        name = title,
        addonTmdbId = known?.tmdbId,
        addonTvdbId = known?.tvdbId,
    ).withCachedCustomPosters(settings, keys)
    val customPoster = serviced.poster
    val customLandscape = serviced.landscapePoster
    val customArtFirst = settings.continueWatchingOverStills && (customPoster != null || customLandscape != null)
    if (
        customPoster == this.customPoster &&
        customLandscape == this.customLandscape &&
        customArtFirst == this.customArtFirst
    ) {
        return this
    }
    return copy(customPoster = customPoster, customLandscape = customLandscape, customArtFirst = customArtFirst)
}

@kotlin.jvm.JvmName("withCachedCustomPostersContinueWatching")
internal fun List<ContinueWatchingItem>.withCachedCustomPosters(
    settings: CustomPosterSettings,
    keys: CustomPosterKeys,
): List<ContinueWatchingItem> {
    if (!settings.isActive || isEmpty()) return this
    return map { it.withCachedCustomPosters(settings, keys) }
}
