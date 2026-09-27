package com.nuvio.app.features.games

import kotlinx.serialization.Serializable

/**
 * User-facing configuration for game mode, stored alongside every other Nuvio setting
 * rather than inside the game library file.
 *
 * The library itself — the games, their artwork and the last browsed executable folder — stays in
 * its own JSON document, because it is content rather than configuration. These four values are
 * the ones a settings page has any business editing, so they live where the rest of the app's
 * settings live and are edited through the ordinary settings rows.
 */
data class GameLibrarySettings(
    val igdbClientId: String = "",
    val igdbClientSecret: String = "",
    val steamGridDbApiKey: String = "",
    val backdropStyle: GameBackdropStyle = GameBackdropStyle.BlackShelf,
    val metadataSource: GameMetadataSource = GameMetadataSource.Igdb,
    // Nuvio exits once a game has been started. The client idles at close to a gigabyte, which
    // is a gigabyte the game would rather have; the tray icon is not enough, since the heap
    // stays resident behind it.
    val closeAfterLaunch: Boolean = false,
) {
    /** IGDB needs both halves of a Twitch confidential application; one alone buys nothing. */
    val igdbConfigured: Boolean
        get() = igdbClientId.isNotBlank() && igdbClientSecret.isNotBlank()

    val steamGridDbConfigured: Boolean
        get() = steamGridDbApiKey.isNotBlank()

    /** Whether the chosen provider can actually be asked anything right now. */
    val metadataConfigured: Boolean
        get() = when (metadataSource) {
            GameMetadataSource.Igdb -> igdbConfigured
            GameMetadataSource.Steam -> true
        }
}

/**
 * Where a game's title, summary, genres, release date, rating and artwork come from.
 *
 * Not a fallback chain, deliberately. Two providers filling one entry between them would make the
 * result depend on the order the top-up happened to run in, and a game would silently change its
 * description when a match appeared elsewhere. One source answers for the whole library, and the
 * user is told what each costs.
 */
@Serializable
enum class GameMetadataSource {
    /** Every platform, but needs a Twitch client id and secret. */
    Igdb,

    /**
     * No key of any kind, and no coverage outside Steam's own store: a game Steam does not sell
     * cannot be matched at all, so this suits a Steam-heavy library and nothing else.
     */
    Steam,
}

/** How far the artwork behind the game library extends before the cover rail takes over. */
enum class GameBackdropStyle {
    /** Artwork is confined above the rail and fades to black — the default, TV-style presentation. */
    BlackShelf,

    /** Artwork fills the window and the rail floats over it. */
    FullBackdrop,
}
