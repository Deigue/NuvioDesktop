package com.nuvio.app.features.games

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GameLibraryRepositoryTest {
    @Test
    fun gamesAndLastExecutableDirectorySurviveIndependentUpdates() = runBlocking {
        val directory = Files.createTempDirectory("nuvio-games-test")
        val repository = GameLibraryRepository(
            dataFile = directory.resolve("library.json"),
            legacyDataFiles = emptyList(),
        )
        val game = GameEntry(
            id = "game-1",
            title = "Test Game",
            executablePath = "C:\\Games\\Test\\game.exe",
            genres = listOf("Adventure"),
        )

        repository.saveGames(listOf(game))
        repository.saveLastExecutableDirectory("C:\\Games")
        val loaded = repository.load()

        assertEquals(listOf(game), loaded.games)
        assertEquals("C:\\Games", loaded.settings.lastExecutableDirectory)
    }

    @Test
    fun migratesAStandaloneLauncherLibraryWhenNoneExistsYet() = runBlocking {
        val directory = Files.createTempDirectory("nuvio-games-migration-test")
        val legacyFile = directory.resolve("umbra/library.json")
        val mergedFile = directory.resolve("nuvio/games/library.json")
        val legacyRepository = GameLibraryRepository(legacyFile, legacyDataFiles = emptyList())
        val game = GameEntry(id = "tracked", title = "Coming Soon", executablePath = null)
        legacyRepository.saveGames(listOf(game))

        val merged = GameLibraryRepository(mergedFile, legacyDataFiles = listOf(legacyFile))

        assertEquals(listOf(game), merged.load().games)
    }

    @Test
    fun firstExistingLegacyLocationWins() = runBlocking {
        val directory = Files.createTempDirectory("nuvio-games-legacy-order-test")
        val preferred = directory.resolve("umbra/library.json")
        val older = directory.resolve("nuvio-games/library.json")
        GameLibraryRepository(preferred, legacyDataFiles = emptyList())
            .saveGames(listOf(GameEntry(id = "preferred", title = "Preferred")))
        GameLibraryRepository(older, legacyDataFiles = emptyList())
            .saveGames(listOf(GameEntry(id = "older", title = "Older")))

        val merged = GameLibraryRepository(
            dataFile = directory.resolve("merged/library.json"),
            legacyDataFiles = listOf(preferred, older),
        )

        assertEquals(listOf("preferred"), merged.load().games.map(GameEntry::id))
    }
}

class GameLauncherTest {
    @Test
    fun parsesQuotedArgumentsWithoutUsingAShell() {
        assertEquals(
            listOf("--profile", "Living Room", "--safe"),
            parseArguments("--profile \"Living Room\" --safe"),
        )
    }

    @Test
    fun derivesReadableTitleFromExecutable() {
        assertEquals("Super game", executableDisplayName("C:\\Games\\Super_game.exe"))
    }

    @Test
    fun recognisesProtocolUrlsButNotWindowsPaths() {
        assertTrue(isProtocolLaunchTarget("steam://rungameid/620"))
        assertTrue(isProtocolLaunchTarget("  STEAM://run/620  "))
        assertTrue(isProtocolLaunchTarget("com.epicgames.launcher://apps/Fortnite?action=launch"))
        assertFalse(isProtocolLaunchTarget("C:\\Games\\Super_game.exe"))
        assertFalse(isProtocolLaunchTarget("D:/Steam/steamapps/common/Portal 2/portal2.exe"))
        assertFalse(isProtocolLaunchTarget("\\\\nas\\games\\game.exe"))
        assertFalse(isProtocolLaunchTarget("game.exe"))
    }

    @Test
    fun protocolLaunchRefusesLooseArgumentsInsteadOfDroppingThem() {
        val result = GameLauncher.launch(
            GameEntry(id = "1", title = "Portal 2", executablePath = "steam://rungameid/620", arguments = listOf("-windowed")),
        )
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("protocol URL"))
    }

    @Test
    fun installationStateIsDefinedOnlyByExecutablePresence() {
        assertEquals(false, GameEntry(id = "1", title = "Tracked", executablePath = null).isInstalled)
        assertEquals(false, GameEntry(id = "2", title = "Tracked", executablePath = "  ").isInstalled)
        assertEquals(true, GameEntry(id = "3", title = "Installed", executablePath = "game.exe").isInstalled)
    }
}

class GameArtworkTest {
    @Test
    fun upgradesPersistedIgdbCoverVariant() {
        assertEquals(
            "https://images.igdb.com/igdb/image/upload/t_1080p/co1v8w.jpg",
            highResolutionIgdbCoverUrl(
                "https://images.igdb.com/igdb/image/upload/t_cover_big_2x/co1v8w.jpg",
            ),
        )
    }

    @Test
    fun leavesNonIgdbArtworkUntouched() {
        val url = "https://cdn.example.test/covers/game.png"
        assertEquals(url, highResolutionIgdbCoverUrl(url))
        assertNull(highResolutionIgdbCoverUrl(null))
    }

    @Test
    fun formatsArtworkResolution() {
        assertEquals(
            "3840 × 2160",
            ArtworkCandidate("https://example.test/art.jpg", 3840, 2160, ArtworkSource.ARTWORK).resolutionLabel,
        )
        assertEquals(
            "Resolution unknown",
            ArtworkCandidate("https://example.test/art.jpg", 0, 0, ArtworkSource.SCREENSHOT).resolutionLabel,
        )
    }

    @Test
    fun formatsLogoPickerDetails() {
        val logo = LogoCandidate(
            url = "https://example.test/logo.png",
            width = 1600,
            height = 620,
            score = 12,
            language = "en",
        )

        assertEquals("1600 × 620", logo.resolutionLabel)
        assertEquals("EN", logo.languageLabel)
    }

    @Test
    fun omitsMissingLogoLanguage() {
        val logo = LogoCandidate(url = "https://example.test/logo.png", width = 0, height = 0, score = 0)

        assertEquals("Resolution unknown", logo.resolutionLabel)
        assertNull(logo.languageLabel)
    }
}

class ManualLogoTest {
    private val chosen = "https://cdn.example.test/valheim-white.png"

    @Test
    fun aHandPickedLogoSurvivesASaveThatChangesNothing() {
        // The editor auto-loads the existing IGDB match when it opens, so a no-op save used to
        // look like a re-match, reset the lookup version, and let the top-up overwrite the logo.
        assertTrue(
            logoRemainsManuallyChosen(
                pickedNow = false,
                previouslyManual = true,
                previousLogoUrl = chosen,
                logoUrlToSave = chosen,
            ),
        )
    }

    @Test
    fun pickingALogoNowMarksItManual() {
        assertTrue(
            logoRemainsManuallyChosen(
                pickedNow = true,
                previouslyManual = false,
                previousLogoUrl = null,
                logoUrlToSave = chosen,
            ),
        )
    }

    @Test
    fun rematchingToADifferentGameEndsTheManualChoice() {
        assertFalse(
            logoRemainsManuallyChosen(
                pickedNow = false,
                previouslyManual = true,
                previousLogoUrl = chosen,
                logoUrlToSave = "https://images.igdb.com/igdb/image/upload/t_1080p/other.png",
            ),
        )
    }

    @Test
    fun aBlankPreviousLogoIsNeverTreatedAsAManualChoice() {
        assertFalse(
            logoRemainsManuallyChosen(
                pickedNow = false,
                previouslyManual = true,
                previousLogoUrl = "",
                logoUrlToSave = "",
            ),
        )
    }

    @Test
    fun theTopUpLeavesAManualLogoAloneAndFillsAnAutomaticOne() {
        val fetched = "https://cdn.example.test/steamgriddb-default.png"
        val manual = GameEntry(id = "1", title = "Valheim", logoUrl = chosen, logoManuallyChosen = true)
        val automatic = GameEntry(id = "2", title = "Valheim", logoUrl = chosen)

        assertEquals(chosen, manual.refreshedLogoUrl(fetched))
        assertEquals(fetched, automatic.refreshedLogoUrl(fetched))
        // Nothing fetched must never blank an existing logo.
        assertEquals(chosen, automatic.refreshedLogoUrl(null))
    }

    @Test
    fun theManualFlagRoundTripsThroughTheLibraryFile() = runBlocking {
        val directory = Files.createTempDirectory("nuvio-games-manual-logo-test")
        val repository = GameLibraryRepository(
            dataFile = directory.resolve("library.json"),
            legacyDataFiles = emptyList(),
        )
        val game = GameEntry(id = "1", title = "Valheim", logoUrl = chosen, logoManuallyChosen = true)

        repository.saveGames(listOf(game))

        assertEquals(listOf(game), repository.load().games)
    }
}

class GameShelfRowsTest {
    private val installed = GameEntry(id = "a", title = "Installed one", executablePath = "C:\\g\\a.exe")
    private val tracked = GameEntry(id = "b", title = "Tracked one")

    @Test
    fun aLibraryWithoutRowsGetsInstalledThenUninstalled() {
        val rows = ensureDefaultGameRows(emptyList())
        assertEquals(listOf(GameInstalledRowId, GameUninstalledRowId), rows.map { it.id })
        assertEquals(GameRowBucket.Installed, rows[0].defaultBucket)
        assertEquals(GameRowBucket.Uninstalled, rows[1].defaultBucket)
    }

    @Test
    fun aRenamedAndReorderedDefaultRowIsKeptWhereTheUserPutIt() {
        val custom = listOf(
            GameRow("uninstalled", "Wishlist", defaultBucket = GameRowBucket.Uninstalled),
            GameRow("later", "Later"),
            GameRow("installed", "Ready to play", defaultBucket = GameRowBucket.Installed),
        )
        assertEquals(custom, ensureDefaultGameRows(custom))
    }

    @Test
    fun gamesFileIntoTheirBucketUntilMoved() {
        val rows = ensureDefaultGameRows(listOf(GameRow("later", "Later")))
        assertEquals(GameInstalledRowId, installed.effectiveRowId(rows))
        assertEquals(GameUninstalledRowId, tracked.effectiveRowId(rows))
        assertEquals("later", tracked.copy(rowId = "later").effectiveRowId(rows))
        // A pin outlives its executable: adding one does not pull the game back to Installed.
        assertEquals("later", installed.copy(rowId = "later").effectiveRowId(rows))
    }

    @Test
    fun aPinToADeletedRowFallsBackToTheBucket() {
        val rows = ensureDefaultGameRows(emptyList())
        assertEquals(GameUninstalledRowId, tracked.copy(rowId = "gone").effectiveRowId(rows))
    }

    @Test
    fun theShelfShowsRowsInOrderAndSkipsEmptyOnes() {
        val rows = listOf(
            GameRow("later", "Later"),
            GameRow(GameInstalledRowId, "Installed", defaultBucket = GameRowBucket.Installed),
            GameRow("empty", "Empty"),
            GameRow(GameUninstalledRowId, "Uninstalled", defaultBucket = GameRowBucket.Uninstalled),
        )
        val shelf = gameShelfRows(listOf(installed, tracked, tracked.copy(id = "c", rowId = "later")), rows)
        assertEquals(listOf("later", GameInstalledRowId, GameUninstalledRowId), shelf.map { it.row.id })
        assertEquals(listOf("c"), shelf[0].games.map { it.id })
        assertEquals(listOf("a"), shelf[1].games.map { it.id })
        assertEquals(listOf("b"), shelf[2].games.map { it.id })
    }

    @Test
    fun rowsRoundTripThroughTheLibraryFile() = runBlocking {
        val directory = Files.createTempDirectory("nuvio-games-rows-test")
        val repository = GameLibraryRepository(
            dataFile = directory.resolve("library.json"),
            legacyDataFiles = emptyList(),
        )
        val rows = ensureDefaultGameRows(emptyList()) + GameRow("later", "Later", color = 0xFF29B6F6)

        repository.saveGames(listOf(tracked.copy(rowId = "later")))
        repository.saveRows(rows)
        val loaded = repository.load()

        assertEquals(rows, loaded.rows)
        assertEquals("later", loaded.games.single().rowId)
    }
}

class SteamArtworkTest {
    @Test
    fun readsAppIdFromASteamProtocolLaunchTarget() {
        assertEquals(
            620L,
            steamAppIdFromLaunchTarget("steam://rungameid/620", emptyList()),
        )
        assertEquals(
            570L,
            steamAppIdFromLaunchTarget("C:\\Steam\\steam.exe", listOf("steam://run/570")),
        )
    }

    @Test
    fun readsAppIdFromAnApplaunchArgument() {
        assertEquals(
            292030L,
            steamAppIdFromLaunchTarget("C:\\Steam\\steam.exe", listOf("-applaunch", "292030", "-skipStartScreen")),
        )
    }

    @Test
    fun ignoresAnApplaunchWithoutANumber() {
        assertNull(steamAppIdFromLaunchTarget("C:\\Steam\\steam.exe", listOf("-applaunch")))
        assertNull(steamAppIdFromLaunchTarget("C:\\Games\\game.exe", listOf("--windowed")))
        assertNull(steamAppIdFromLaunchTarget(null, emptyList()))
    }

    @Test
    fun findsTheInstallDirectoryAGameSitsUnder() {
        assertEquals(
            "Hades",
            steamInstallDirectoryName("D:\\SteamLibrary\\steamapps\\common\\Hades\\x64\\Hades.exe"),
        )
        assertNull(steamInstallDirectoryName("D:\\Games\\Hades\\Hades.exe"))
    }

    @Test
    fun matchesTheGameToItsSteamInstallManifest() {
        val library = Files.createTempDirectory("nuvio-steam-library")
        val steamApps = library.resolve("steamapps")
        val installDirectory = steamApps.resolve("common/Hades/x64")
        Files.createDirectories(installDirectory)
        val executable = installDirectory.resolve("Hades.exe")
        Files.writeString(executable, "")
        // Two manifests, so the test proves it matches on installdir rather than on being first.
        Files.writeString(
            steamApps.resolve("appmanifest_1145360.acf"),
            """
            "AppState"
            {
            	"appid"		"1145360"
            	"name"		"Hades"
            	"installdir"		"Hades"
            }
            """.trimIndent(),
        )
        Files.writeString(
            steamApps.resolve("appmanifest_620.acf"),
            """
            "AppState"
            {
            	"appid"		"620"
            	"name"		"Portal 2"
            	"installdir"		"Portal 2"
            }
            """.trimIndent(),
        )

        assertEquals(1145360L, steamAppIdFromInstallLocation(executable.toString()))
    }

    @Test
    fun returnsNoAppIdForAGameOutsideASteamLibrary() {
        val directory = Files.createTempDirectory("nuvio-steam-none")
        val executable = directory.resolve("game.exe")
        Files.writeString(executable, "")

        assertNull(steamAppIdFromInstallLocation(executable.toString()))
    }

    @Test
    fun takesOnlyAnExactTitleMatchFromTheStoreSearch() {
        val results = listOf(
            SteamSearchApp(appid = "1145365", name = "Hades Soundtrack"),
            SteamSearchApp(appid = "1145360", name = "Hades"),
        )

        assertEquals("1145360", selectSteamApp(results, "hades")?.appid)
        assertNull(selectSteamApp(results, "Hades II"))
        assertNull(selectSteamApp(results, "Hades Sound"))
    }

    @Test
    fun ignoresPunctuationWhenMatchingTitles() {
        val results = listOf(SteamSearchApp(appid = "1817070", name = "Marvel's Spider-Man Remastered"))

        assertEquals("1817070", selectSteamApp(results, "Marvel s Spider Man Remastered")?.appid)
    }

    @Test
    fun readsHashedAndBarePathsOutOfTheStoreManifest() {
        // Mortal Shell II, as GetItems described it in 2026-09: every file under its own hash, and
        // the cover called library_capsule rather than library_600x900.
        val hashed = Json.parseToJsonElement(
            """
            {"appid": 2584270, "assets": {
              "asset_url_format": "steam/apps/2584270/${'$'}{FILENAME}?t=1788251111",
              "library_capsule": "e92804cbd8a37fbf08e19c55ab6a0ce7f0f25bf5/library_capsule.jpg",
              "library_capsule_2x": "e92804cbd8a37fbf08e19c55ab6a0ce7f0f25bf5/library_capsule_2x.jpg",
              "library_hero": "6797ef35ca25b0f2876c8a16bc5f8deffa19dbe1/library_hero.jpg",
              "community_icon": "43f972f0e64d5d8685563998e050e781e7a61122",
              "last_modified": 1788251111
            }}
            """.trimIndent(),
        ).jsonObject
        val manifest = parseSteamStoreAssets(hashed)!!

        assertEquals(2584270L, manifest.appId)
        assertEquals(
            "https://shared.steamstatic.com/store_item_assets/steam/apps/2584270/" +
                "e92804cbd8a37fbf08e19c55ab6a0ce7f0f25bf5/library_capsule_2x.jpg?t=1788251111",
            manifest.url("library_capsule_2x"),
        )
        assertNull(manifest.url("library_hero_2x"))
        assertNull(manifest.url("community_icon"))
        assertNull(manifest.url(null))

        // Portal 2: bare file names, which the same format still turns into a working URL.
        val bare = Json.parseToJsonElement(
            """
            {"appid": 620, "assets": {
              "asset_url_format": "steam/apps/620/${'$'}{FILENAME}?t=1745363004",
              "library_capsule": "library_600x900.jpg"
            }}
            """.trimIndent(),
        ).jsonObject
        assertEquals(
            "https://shared.steamstatic.com/store_item_assets/steam/apps/620/library_600x900.jpg?t=1745363004",
            parseSteamStoreAssets(bare)!!.url("library_capsule"),
        )
    }

    @Test
    fun yieldsNoManifestForAnAppTheStoreWillNotShow() {
        val hidden = Json.parseToJsonElement(
            """{"item_type": 0, "id": 999999999, "success": 15, "visible": false, "appid": 0}""",
        ).jsonObject

        assertNull(parseSteamStoreAssets(hidden))
    }

    @Test
    fun buildsBothCdnHostsForAnAsset() {
        assertEquals(
            listOf(
                "https://cdn.cloudflare.steamstatic.com/steam/apps/620/library_hero.jpg",
                "https://shared.steamstatic.com/store_item_assets/steam/apps/620/library_hero.jpg",
            ),
            steamAssetUrls(620L, "library_hero.jpg"),
        )
    }

    @Test
    fun offersOneCandidatePerAssetChainSoAResolutionPairIsNotShownTwice() {
        // A chain is one picture at descending sizes, so it yields one tile: the wide hero and the
        // store-page background are two chains, every logo name is one.
        assertEquals(2, STEAM_HERO_ASSETS.size)
        assertEquals(1, STEAM_LOGO_ASSETS.size)
        assertEquals(
            listOf("library_hero_2x.jpg", "library_hero.jpg"),
            STEAM_HERO_ASSETS.first().map(SteamAsset::fileName),
        )
        // logo.png is the name that actually resolves on the CDN, so it must be tried first.
        assertEquals("logo.png", STEAM_LOGO_ASSETS.first().first().fileName)
    }

    @Test
    fun readsLogoDimensionsFromAPngHeader() {
        val header = ByteArray(24)
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A).copyInto(header)
        // Width 640, height 360, big-endian, at the fixed IHDR offsets.
        byteArrayOf(0, 0, 0x02, 0x80.toByte()).copyInto(header, 16)
        byteArrayOf(0, 0, 0x01, 0x68).copyInto(header, 20)

        assertEquals(640 to 360, pngDimensions(header))
    }

    @Test
    fun measuresNothingThatIsNotAPng() {
        assertNull(pngDimensions(ByteArray(24)))
        assertNull(pngDimensions(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)))
    }

    @Test
    fun labelsSteamLogosByTheirSourceWhenTheyCarryNoLanguage() {
        val logo = LogoCandidate(
            url = "https://cdn.cloudflare.steamstatic.com/steam/apps/620/library_logo.png",
            width = 0,
            height = 0,
            score = 0,
            source = ArtworkSource.STEAM,
        )

        assertEquals("Steam", logo.source.label)
        assertNull(logo.languageLabel)
        assertEquals("Resolution unknown", logo.resolutionLabel)
    }
}

class SteamMetadataTest {
    @Test
    fun readsTheStoreDisplayDateInEitherOrder() {
        // Steam has no timestamp field, only the string its store page prints.
        assertEquals(1600300800L, parseSteamReleaseDate("17 Sep, 2020"))
        assertEquals(1600300800L, parseSteamReleaseDate("Sep 17, 2020"))
        assertEquals(1600300800L, parseSteamReleaseDate("17 September, 2020"))
        assertEquals(911433600L, parseSteamReleaseDate("19 Nov, 1998"))
    }

    @Test
    fun fallsBackToTheMonthOrTheYearWhenThatIsAllThereIs() {
        assertEquals(1598918400L, parseSteamReleaseDate("Sep 2020"))
        assertEquals(1577836800L, parseSteamReleaseDate("2020"))
    }

    @Test
    fun refusesToGuessAtAnUnreleasedDate() {
        // All legal values of the field. A guess here would print a wrong year in the editor.
        assertNull(parseSteamReleaseDate("Coming soon"))
        assertNull(parseSteamReleaseDate("To be announced"))
        assertNull(parseSteamReleaseDate("Q4 2026"))
        assertNull(parseSteamReleaseDate("Fall 2026"))
        assertNull(parseSteamReleaseDate(""))
        assertNull(parseSteamReleaseDate(null))
    }

    @Test
    fun entriesWrittenBeforeTheSettingExistedCountAsIgdb() {
        // Anything else would re-fetch the whole library on the first launch after the upgrade.
        assertEquals(
            GameMetadataSource.Igdb,
            GameEntry(id = "1", title = "Hades", igdbId = 100L).effectiveMetadataSource,
        )
        assertEquals(
            GameMetadataSource.Steam,
            GameEntry(
                id = "2",
                title = "Hades",
                metadataSource = GameMetadataSource.Steam,
            ).effectiveMetadataSource,
        )
    }

    @Test
    fun aMatchIsCheckedAgainstItsOwnProvidersIdSpace() {
        val entry = GameEntry(id = "1", title = "Hades", igdbId = 1145360L, steamAppId = 620L)
        val igdb = { id: Long -> steamlessMetadata(id, GameMetadataSource.Igdb) }
        val steam = { id: Long -> steamlessMetadata(id, GameMetadataSource.Steam) }

        assertTrue(entry.matchesMetadata(igdb(1145360L)))
        assertTrue(entry.matchesMetadata(steam(620L)))
        // The same number in the other provider's id space is a different game.
        assertFalse(entry.matchesMetadata(steam(1145360L)))
        assertFalse(entry.matchesMetadata(igdb(620L)))
    }

    @Test
    fun theSteamMatchAndItsSourceRoundTripThroughTheLibraryFile() = runBlocking {
        val directory = Files.createTempDirectory("nuvio-games-steam-source-test")
        val repository = GameLibraryRepository(
            dataFile = directory.resolve("library.json"),
            legacyDataFiles = emptyList(),
        )
        val game = GameEntry(
            id = "steam-1",
            title = "Hades",
            steamAppId = 1145360L,
            metadataSource = GameMetadataSource.Steam,
        )

        repository.saveGames(listOf(game))

        assertEquals(listOf(game), repository.load().games)
    }

    @Test
    fun steamIsAlwaysConfiguredAndIgdbNeedsBothHalves() {
        val noKeys = GameLibrarySettings()

        assertFalse(noKeys.metadataConfigured)
        assertTrue(noKeys.copy(metadataSource = GameMetadataSource.Steam).metadataConfigured)
        assertTrue(
            noKeys.copy(igdbClientId = "id", igdbClientSecret = "secret").metadataConfigured,
        )
    }

    @Test
    fun coversAreProbedLargestFirstAndNeverFallBackToLandscapeArt() {
        assertEquals(1, STEAM_COVER_ASSETS.size)
        assertEquals(
            listOf("library_600x900_2x.jpg", "library_600x900.jpg"),
            STEAM_COVER_ASSETS.first().map(SteamAsset::fileName),
        )
    }
}

private fun steamlessMetadata(id: Long, source: GameMetadataSource) = GameMetadata(
    id = id,
    source = source,
    title = "Hades",
    coverUrl = null,
    backdropUrl = null,
    logoUrl = null,
    summary = null,
    releaseDateEpochSeconds = null,
    genres = emptyList(),
    platforms = emptyList(),
    rating = null,
)
