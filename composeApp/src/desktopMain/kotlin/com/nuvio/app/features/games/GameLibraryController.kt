package com.nuvio.app.features.games

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.UUID

/**
 * Owns the game library for the lifetime of the app.
 *
 * Held as a singleton rather than remembered by the screen: game mode is a toggle, and rebuilding
 * the controller on every toggle would re-read the library and re-issue the IGDB metadata top-up
 * each time the user glanced at their games.
 */
class GameLibraryController(
    private val repository: GameLibraryRepository = GameLibraryRepository(),
    private val igdbClient: IgdbClient = IgdbClient(),
    private val steamGridDbClient: SteamGridDbClient = SteamGridDbClient(),
    private val steamArtworkClient: SteamArtworkClient = SteamArtworkClient(),
    private val steamMetadataClient: SteamMetadataClient = SteamMetadataClient(steamArtworkClient),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _games = MutableStateFlow<List<GameEntry>>(emptyList())
    val games: StateFlow<List<GameEntry>> = _games.asStateFlow()
    // Seeded with the two defaults so a first frame before the library has loaded still has
    // somewhere to file a game; loadFromDisk replaces them with whatever the file holds.
    private val _rows = MutableStateFlow(ensureDefaultGameRows(emptyList()))
    /** Shelf rows in display order, both default rows always present. */
    val rows: StateFlow<List<GameRow>> = _rows.asStateFlow()
    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()
    private val _lastExecutableDirectory = MutableStateFlow("")
    val lastExecutableDirectory: StateFlow<String> = _lastExecutableDirectory.asStateFlow()

    private val settings: GameLibrarySettings
        get() = GameLibrarySettingsRepository.snapshot()

    init {
        scope.launch {
            val data = repository.load()
            _games.value = data.games
            _rows.value = ensureDefaultGameRows(data.rows)
            _lastExecutableDirectory.value = data.settings.lastExecutableDirectory
            _loading.value = false
            backfillSteamCovers(data.games)
            refreshMetadata(data.games, settings)
        }
        // Entering credentials for the first time should fill in the artwork that could not be
        // fetched without them, without the user having to re-add anything.
        scope.launch {
            GameLibrarySettingsRepository.uiState.drop(1).collect { updated ->
                refreshMetadata(_games.value, updated)
            }
        }
    }

    /** Searches whichever provider the user chose; the editor does not care which it is. */
    suspend fun searchMetadata(query: String): List<GameMetadata> = when (settings.metadataSource) {
        GameMetadataSource.Igdb -> igdbClient.search(query, settings)
        GameMetadataSource.Steam -> steamMetadataClient.search(query)
    }

    suspend fun loadMetadata(gameId: Long): GameMetadata? = when (settings.metadataSource) {
        GameMetadataSource.Igdb -> igdbClient.game(gameId, settings)
        GameMetadataSource.Steam -> steamMetadataClient.game(gameId)
    }

    suspend fun loadLogoCandidates(title: String): List<LogoCandidate> =
        steamGridDbClient.logosFor(title, settings.steamGridDbApiKey)

    suspend fun loadHeroCandidates(title: String): List<ArtworkCandidate> =
        steamGridDbClient.heroesFor(title, settings.steamGridDbApiKey)

    suspend fun loadSteamHeroCandidates(reference: SteamGameReference): List<ArtworkCandidate> =
        steamArtworkClient.heroesFor(reference)

    suspend fun loadSteamLogoCandidates(reference: SteamGameReference): List<LogoCandidate> =
        steamArtworkClient.logosFor(reference)

    fun rememberExecutableDirectory(executablePath: String) {
        if (isProtocolLaunchTarget(executablePath)) return
        val directory = File(executablePath).parentFile?.absolutePath ?: return
        if (_lastExecutableDirectory.value == directory) return
        _lastExecutableDirectory.value = directory
        scope.launch { repository.saveLastExecutableDirectory(directory) }
    }

    fun add(game: GameEntry) {
        _games.value = _games.value + game
        persistGames()
        scope.launch { refreshMetadata(listOf(game), settings) }
    }

    /**
     * Adds every candidate a folder scan produced, then attaches Steam's own artwork to each.
     *
     * The entries land in the library at once, with title and executable only, so a large import
     * is usable immediately; the artwork follows per game as the CDN answers. Steam is the source
     * here because it is the one that needs no key and, for a game in a Steam library, no guess:
     * the manifest already named the app id. Anything it attaches is a default — the editor's
     * pickers and provider search replace it like any other artwork.
     */
    fun importScanned(candidates: List<GameFolderCandidate>): List<GameEntry> {
        if (candidates.isEmpty()) return emptyList()
        val entries = candidates.map { candidate ->
            GameEntry(
                id = UUID.randomUUID().toString(),
                steamAppId = candidate.steamAppId,
                title = candidate.title,
                executablePath = candidate.executablePath,
                workingDirectory = defaultWorkingDirectory(candidate.executablePath),
            )
        }
        _games.value = _games.value + entries
        persistGames()
        scope.launch {
            attachSteamArtwork(entries)
            refreshMetadata(entries, settings)
        }
        return entries
    }

    /**
     * Steam CDN cover, hero and logo for each entry that can be tied to an app id.
     *
     * The logo is stored as chosen: the SteamGridDB half of the top-up would otherwise replace it
     * on the next run, and an import is meant to leave the library looking finished, not to have it
     * repainted a minute later. A few games at a time — every game is a handful of probes, and a
     * whole library arriving at once should not turn into a burst against the CDN.
     */
    private suspend fun attachSteamArtwork(entries: List<GameEntry>) = coroutineScope {
        val gate = Semaphore(STEAM_ARTWORK_PARALLELISM)
        entries.map { entry ->
            async {
                gate.withPermit {
                    val appId = entry.steamAppId
                        ?: runCatching { steamArtworkClient.appIdFor(entry.steamReference()) }.getOrNull()
                        ?: return@withPermit
                    val cover = runCatching { steamArtworkClient.coverFor(appId) }.getOrNull()
                    val hero = runCatching { steamArtworkClient.heroesFor(appId) }.getOrNull()?.firstOrNull()?.url
                    val logo = runCatching { steamArtworkClient.logosFor(appId) }.getOrNull()?.firstOrNull()?.url
                    // Atomic: several games finish at once, and a plain read-modify-write here
                    // would let one drop another's artwork.
                    _games.update { games ->
                        games.map { stored ->
                            if (stored.id != entry.id) return@map stored
                            stored.copy(
                                steamAppId = appId,
                                coverUrl = cover ?: stored.coverUrl,
                                backdropUrl = hero ?: stored.backdropUrl,
                                logoUrl = logo ?: stored.logoUrl,
                                logoManuallyChosen = stored.logoManuallyChosen || logo != null,
                            )
                        }
                    }
                }
            }
        }.awaitAll()
        repository.saveGames(_games.value)
    }

    /**
     * A cover for every entry that is tied to a Steam app id but has none.
     *
     * These are the games whose art the CDN probes never found because it is filed under the
     * hashed layout (see [SteamArtworkClient]); they were reconciled and ratcheted before the
     * manifest lookup existed, so [refreshMetadata] will not look at them again. One manifest
     * request per such game, and a game the store genuinely has no cover for is asked about again
     * next launch — there are very few of those, and the manifest is a single cheap request.
     */
    private suspend fun backfillSteamCovers(entries: List<GameEntry>) = coroutineScope {
        val gate = Semaphore(STEAM_ARTWORK_PARALLELISM)
        val missing = entries.filter { it.steamAppId != null && it.coverUrl.isNullOrBlank() }
        if (missing.isEmpty()) return@coroutineScope
        val found = missing.map { entry ->
            async {
                gate.withPermit {
                    val cover = runCatching { steamArtworkClient.coverFor(entry.steamAppId!!) }.getOrNull()
                    cover?.let { entry.id to it }
                }
            }
        }.awaitAll().filterNotNull().toMap()
        if (found.isEmpty()) return@coroutineScope
        _games.update { games ->
            games.map { stored ->
                val cover = found[stored.id]
                if (cover != null && stored.coverUrl.isNullOrBlank()) stored.copy(coverUrl = cover) else stored
            }
        }
        repository.saveGames(_games.value)
    }

    fun update(game: GameEntry) {
        // Matching the game against a different provider entry invalidates the artwork fetched for
        // the old one, so the top-up is re-armed. Changing the title re-arms it too: that is the
        // only way back for a game the provider could not find under its old name. Nothing else
        // does — a plain save must not, or every save would re-fetch and clobber chosen artwork.
        val stored = _games.value.firstOrNull { it.id == game.id }
        val rematched = stored != null && (
            stored.igdbId != game.igdbId ||
                stored.steamAppId != game.steamAppId ||
                !stored.title.equals(game.title, ignoreCase = true)
            )
        val updated = if (rematched) game.copy(logoLookupVersion = 0) else game
        _games.value = _games.value.map { if (it.id == game.id) updated else it }
        persistGames()
        scope.launch { refreshMetadata(listOf(updated), settings) }
    }

    fun delete(gameId: String) {
        _games.value = _games.value.filterNot { it.id == gameId }
        persistGames()
    }

    /** Files a game under [rowId], or back under automatic placement when null. */
    fun assignRow(gameId: String, rowId: String?) {
        val target = rowId?.takeIf { id -> _rows.value.any { it.id == id } }
        _games.value = _games.value.map { if (it.id == gameId) it.copy(rowId = target) else it }
        persistGames()
    }

    fun addRow(name: String): GameRow? {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return null
        val row = GameRow(id = UUID.randomUUID().toString(), name = trimmed)
        _rows.value = _rows.value + row
        persistRows()
        return row
    }

    fun renameRow(rowId: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        _rows.value = _rows.value.map { if (it.id == rowId) it.copy(name = trimmed) else it }
        persistRows()
    }

    fun setRowColor(rowId: String, color: Long?) {
        _rows.value = _rows.value.map { if (it.id == rowId) it.copy(color = color) else it }
        persistRows()
    }

    /**
     * Removes a user-made row. The default rows refuse: they are where the automatic placement
     * files games, and the library must always have somewhere to put one. Games in the removed
     * row keep their now-dangling id, which [GameEntry.effectiveRowId] already treats as automatic,
     * so nothing needs rewriting for them to reappear under Installed or Uninstalled.
     */
    fun removeRow(rowId: String) {
        val row = _rows.value.firstOrNull { it.id == rowId } ?: return
        if (row.defaultBucket != null) return
        _rows.value = _rows.value.filterNot { it.id == rowId }
        persistRows()
    }

    /** Swaps a row with its neighbour [delta] places away (-1 up, +1 down). Out of range is a no-op. */
    fun moveRow(rowId: String, delta: Int) {
        val current = _rows.value
        val from = current.indexOfFirst { it.id == rowId }
        val to = from + delta
        if (from < 0 || to !in current.indices) return
        _rows.value = current.toMutableList().apply { add(to, removeAt(from)) }
        persistRows()
    }

    private fun persistRows() {
        val snapshot = _rows.value
        scope.launch { repository.saveRows(snapshot) }
    }

    fun launch(game: GameEntry, onExit: (Int) -> Unit = {}): Result<Long?> =
        GameLauncher.launch(game, onExit)

    private fun persistGames() {
        val snapshot = _games.value
        scope.launch { repository.saveGames(snapshot) }
    }

    /**
     * Tops up artwork and metadata for entries that have not been through the current lookup.
     *
     * `logoLookupVersion` is the ratchet: 2 means metadata has been fetched, 3 means a SteamGridDB
     * logo was looked up as well. Adding a SteamGridDB key therefore re-runs only the logo half for
     * games already at 2, and each candidate is spaced out so a first-run library does not hammer
     * either API.
     *
     * Changing the metadata source is the one thing that re-arms the metadata half regardless of
     * the ratchet: the stored summary, genres and rating came from a provider the user has just
     * stopped using. Entries the new source cannot match keep what they have — with Steam selected
     * that is every game Steam does not sell, which is the documented cost of the setting.
     */
    private suspend fun refreshMetadata(
        candidates: List<GameEntry>,
        settings: GameLibrarySettings,
    ) {
        if (!settings.metadataConfigured) return
        var changed = false
        val source = settings.metadataSource
        val targetVersion = if (settings.steamGridDbConfigured) 3 else 2
        candidates.forEach { candidate ->
            val entry = _games.value.firstOrNull { it.id == candidate.id } ?: return@forEach
            val needsMetadata = entry.effectiveMetadataSource != source || entry.logoLookupVersion < 2
            if (!needsMetadata && entry.logoLookupVersion >= targetVersion) return@forEach

            val metadataResult = if (needsMetadata) {
                runCatching { fetchMetadata(entry, source, settings) }
            } else {
                Result.success(null)
            }
            if (metadataResult.isFailure) {
                delay(300)
                return@forEach
            }

            val metadata = metadataResult.getOrNull()
            val metadataLogo = if (needsMetadata) metadata?.logoUrl else entry.logoUrl
            // No request at all for a hand-picked logo: its result could only be discarded.
            val steamGridLogoResult = if (settings.steamGridDbConfigured && !entry.logoManuallyChosen) {
                runCatching {
                    steamGridDbClient.logoFor(metadata?.title ?: entry.title, settings.steamGridDbApiKey)
                }
            } else {
                Result.success(null)
            }
            val completedVersion = when {
                settings.steamGridDbConfigured && steamGridLogoResult.isSuccess -> 3
                needsMetadata && metadataResult.isSuccess -> 2
                else -> entry.logoLookupVersion
            }
            val refreshed = entry.copy(
                // Marked as reconciled against this source even when it found nothing, or every
                // game Steam does not sell would be searched again on every launch. Editing the
                // title re-arms the entry, which is the way back for a game that should have
                // matched and did not.
                metadataSource = source,
                steamAppId = metadata?.takeIf { it.source == GameMetadataSource.Steam }?.id
                    ?: entry.steamAppId,
                coverUrl = metadata?.coverUrl ?: entry.coverUrl,
                backdropUrl = entry.backdropUrl ?: metadata?.backdropUrl,
                logoUrl = entry.refreshedLogoUrl(steamGridLogoResult.getOrNull() ?: metadataLogo),
                summary = metadata?.summary ?: entry.summary,
                releaseDateEpochSeconds = metadata?.releaseDateEpochSeconds ?: entry.releaseDateEpochSeconds,
                genres = metadata?.genres?.ifEmpty { entry.genres } ?: entry.genres,
                platforms = metadata?.platforms?.ifEmpty { entry.platforms } ?: entry.platforms,
                rating = metadata?.rating ?: entry.rating,
                logoLookupCompleted = completedVersion >= 2,
                logoLookupVersion = completedVersion,
            )
            _games.value = _games.value.map { if (it.id == refreshed.id) refreshed else it }
            changed = true
            delay(300)
        }
        if (changed) {
            repository.saveGames(_games.value)
        }
    }

    /**
     * The provider's record for one entry, or null when it has nothing to match on.
     *
     * The Steam branch resolves an app id the first time it runs — from the executable's Steam
     * library, its launch arguments, or an exact title match — and the caller stores it, so the
     * resolution is paid once per game rather than on every top-up.
     */
    private suspend fun fetchMetadata(
        entry: GameEntry,
        source: GameMetadataSource,
        settings: GameLibrarySettings,
    ): GameMetadata? = when (source) {
        GameMetadataSource.Igdb -> entry.igdbId?.let { igdbClient.game(it, settings) }
        GameMetadataSource.Steam -> {
            val appId = entry.steamAppId ?: steamArtworkClient.appIdFor(entry.steamReference())
            appId?.let { steamMetadataClient.game(it) }
        }
    }

    companion object {
        val shared: GameLibraryController by lazy { GameLibraryController() }

        private const val STEAM_ARTWORK_PARALLELISM = 3
    }
}
