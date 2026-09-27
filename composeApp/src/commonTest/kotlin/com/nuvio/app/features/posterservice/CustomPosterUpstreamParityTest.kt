package com.nuvio.app.features.posterservice

import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.PosterShape
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Upstream's custom poster URL pattern language (NuvioMobile `CustomPosterUrlResolver`) and screens. */
class CustomPosterUpstreamParityTest {
    private fun on(template: String, landscape: String = "") = CustomPosterSettings(
        enabled = true,
        posterUrlTemplate = template,
        landscapeUrlTemplate = landscape,
    )

    @Test
    fun `typed id and id type follow upstream for tmdb ids`() {
        val url = customPosterUrl(
            on("https://p.test/{id_type}/{typed_id}.jpg"),
            imdbId = null,
            tmdbId = null,
            type = "tv",
            stremioId = "tmdb:1396",
        )
        assertEquals("https://p.test/tmdb/series-1396.jpg", url)
    }

    @Test
    fun `typed id is the bare imdb id`() {
        val url = customPosterUrl(on("https://p.test/{typed_id}"), imdbId = null, tmdbId = null, type = "movie", stremioId = "tt0137523")
        assertEquals("https://p.test/tt0137523", url)
    }

    @Test
    fun `split ids are read from the raw id namespace`() {
        val url = customPosterUrl(
            on("https://p.test/{tvdb_id}/{kitsu_id?}"),
            imdbId = null,
            tmdbId = null,
            type = "series",
            stremioId = "tvdb:81189",
        )
        assertEquals("https://p.test/81189/", url)
    }

    @Test
    fun `type is normalised to movie or series`() {
        val url = customPosterUrl(on("https://p.test/{type}/{imdb_id}"), imdbId = "tt1", tmdbId = null, type = "anime")
        assertEquals("https://p.test/series/tt1", url)
    }

    @Test
    fun `a pipe list takes the first id the item has`() {
        val url = customPosterUrl(
            on("https://p.test/{imdb_id|kitsu_id}.jpg"),
            imdbId = null,
            tmdbId = null,
            type = "series",
            stremioId = "kitsu:395",
        )
        assertEquals("https://p.test/395.jpg", url)
    }

    @Test
    fun `a pipe list with no known member suppresses the url even beside a raw id`() {
        val url = customPosterUrl(
            on("https://p.test/{imdb_id|tvdb_id}?s={id}"),
            imdbId = null,
            tmdbId = null,
            type = "series",
            stremioId = "kitsu:395",
        )
        assertNull(url)
    }

    @Test
    fun `rpdb templates fall back from imdb to tmdb`() {
        val url = customPosterUrl(
            on("https://api.ratingposterdb.com/KEY/imdb/poster-default/{imdb_id}.jpg"),
            imdbId = null,
            tmdbId = "1396",
            type = "series",
        )
        assertEquals("https://api.ratingposterdb.com/KEY/tmdb/poster-default/series-1396.jpg", url)
    }

    @Test
    fun `non rpdb templates do not fall back`() {
        val url = customPosterUrl(on("https://p.test/imdb/{imdb_id}.jpg"), imdbId = null, tmdbId = "1396", type = "series")
        assertNull(url)
    }

    @Test
    fun `unknown braces are left as the user wrote them`() {
        val url = customPosterUrl(on("https://p.test/{api_key}/{imdb_id}"), imdbId = "tt1", tmdbId = null, type = "movie")
        assertEquals("https://p.test/{api_key}/tt1", url)
    }

    @Test
    fun `a poster template naming shape also serves landscape`() {
        val settings = on("https://p.test/{shape}/{imdb_id}")
        assertEquals("https://p.test/landscape/tt1", customPosterUrl(settings, "tt1", null, "movie", shape = CustomPosterShape.Landscape))
        assertEquals("https://p.test/poster/tt1", customPosterUrl(settings, "tt1", null, "movie"))
    }

    @Test
    fun `an explicit landscape template wins over shape`() {
        val settings = on("https://p.test/{shape}/{imdb_id}", landscape = "https://l.test/{imdb_id}")
        assertEquals("https://l.test/tt1", customPosterUrl(settings, "tt1", null, "movie", shape = CustomPosterShape.Landscape))
    }

    @Test
    fun `landscape declared rows take no portrait art`() {
        val preview = MetaPreview(id = "tt1", type = "movie", name = "x", poster = "plain", posterShape = PosterShape.Landscape)
        val styled = preview.withCustomPosters(on("https://p.test/{imdb_id}"), imdbId = "tt1", tmdbId = null)
        assertEquals("plain", styled.poster)
    }

    @Test
    fun `resolved filename rows are addressed by their lookup id`() {
        val preview = MetaPreview(
            id = "torbox:abc123",
            type = "other",
            name = "x",
            poster = "plain",
            metaLookupId = "tt0137523",
            metaLookupType = "movie",
        )
        val styled = preview.withCustomPosters(on("https://p.test/{type}/{id}"), imdbId = null, tmdbId = null)
        assertEquals("https://p.test/movie/tt0137523", styled.poster)
        assertEquals("plain", styled.posterFallback)
    }

    // Guinness World Records: Primetime — TMDB 16146 has no IMDb link, so only AIOMetadata's
    // `_tmdbId` can fill tmdb_id; without it the service answered with an unknown-title card.
    private val guinnessTemplate =
        "https://posters.example/poster?tmdb_id={tmdb_id?}&imdb_id={imdb_id?}&stremio_id={id}&type={type}&shape={shape}"

    @Test
    fun `the addon's tmdb id fills tmdb_id for an imdb addressed row`() {
        val preview = MetaPreview(id = "tt0197156", type = "series", name = "x", addonTmdbId = 16146)
        val styled = preview.withCachedCustomPosters(on(guinnessTemplate), CustomPosterKeys())
        assertEquals(
            "https://posters.example/poster?tmdb_id=16146&imdb_id=tt0197156&stremio_id=tt0197156&type=series&shape=poster",
            styled.poster,
        )
    }

    @Test
    fun `the addon's tvdb id fills tvdb_id`() {
        val preview = MetaPreview(id = "tt0197156", type = "series", name = "x", addonTvdbId = "70429")
        val styled = preview.withCustomPosters(on("https://p.test/{tvdb_id}"), imdbId = null, tmdbId = null)
        assertEquals("https://p.test/70429", styled.poster)
    }

    @Test
    fun `a filename resolved row ignores the addon ids of its file entry`() {
        val preview = MetaPreview(
            id = "torbox:1",
            type = "other",
            name = "x",
            metaLookupId = "tt0137523",
            metaLookupType = "movie",
            addonTmdbId = 999,
        )
        val styled = preview.withCustomPosters(on("https://p.test/{tmdb_id?}/{id}"), imdbId = null, tmdbId = null)
        assertEquals("https://p.test//tt0137523", styled.poster)
    }

    @Test
    fun `continue watching is off on a fresh setup but on for upstream's empty list`() {
        assertEquals(CustomPosterContinueWatchingMode.Off, CustomPosterSettings().continueWatchingMode)
        assertTrue(CustomPosterScreen.ContinueWatching in CustomPosterScreen.fromKeys(emptyList()))
    }

    @Test
    fun `continue watching mode is a view over the screen set and the stills flag`() {
        val on = CustomPosterSettings(enabledScreens = setOf(CustomPosterScreen.ContinueWatching))
        assertEquals(CustomPosterContinueWatchingMode.BaseArt, on.continueWatchingMode)
        assertEquals(CustomPosterContinueWatchingMode.All, on.copy(continueWatchingOverStills = true).continueWatchingMode)
        assertEquals(
            CustomPosterContinueWatchingMode.Off,
            on.copy(enabledScreens = emptySet(), continueWatchingOverStills = true).continueWatchingMode,
        )
    }

    @Test
    fun `continue watching rows get custom art and the all flag only in all mode`() {
        val item = com.nuvio.app.features.watchprogress.ContinueWatchingItem(
            parentMetaId = "tt0903747",
            parentMetaType = "series",
            videoId = "tt0903747:1:1",
            title = "x",
            subtitle = "",
            imageUrl = null,
            resumePositionMs = 0,
            durationMs = 0,
            progressFraction = 0f,
        )
        val base = on("https://p.test/{shape}/{id}").copy(enabledScreens = setOf(CustomPosterScreen.ContinueWatching))
        val baseArt = item.withCachedCustomPosters(base.forScreen(CustomPosterScreen.ContinueWatching), CustomPosterKeys())
        assertEquals("https://p.test/poster/tt0903747", baseArt.customPoster)
        assertEquals("https://p.test/landscape/tt0903747", baseArt.customLandscape)
        assertFalse(baseArt.customArtFirst)
        val all = item.withCachedCustomPosters(
            base.copy(continueWatchingOverStills = true).forScreen(CustomPosterScreen.ContinueWatching),
            CustomPosterKeys(),
        )
        assertTrue(all.customArtFirst)
        val off = item.withCachedCustomPosters(
            base.copy(enabledScreens = emptySet()).forScreen(CustomPosterScreen.ContinueWatching),
            CustomPosterKeys(),
        )
        assertSame(item, off)
    }

    @Test
    fun `an excluded screen sees the service as off`() {
        val settings = on("https://p.test/{imdb_id}").copy(enabledScreens = setOf(CustomPosterScreen.Home))
        assertTrue(settings.forScreen(CustomPosterScreen.Home).isActive)
        assertFalse(settings.forScreen(CustomPosterScreen.Search).isActive)
    }

    @Test
    fun `cached overlay returns the same list when nothing applies`() {
        val items = listOf(MetaPreview(id = "tt1", type = "movie", name = "x"))
        assertSame(items, items.withCachedCustomPosters(CustomPosterSettings(), CustomPosterKeys()))
    }

    @Test
    fun `upstream screen keys parse and an empty list means every screen`() {
        assertEquals(CustomPosterScreen.ALL, CustomPosterScreen.fromKeys(emptyList()))
        assertEquals(setOf(CustomPosterScreen.Home, CustomPosterScreen.Search), CustomPosterScreen.fromKeys(listOf("home", "search")))
        assertEquals(emptySet(), CustomPosterScreen.fromKeys(listOf("none")))
    }

    @Test
    fun `sync export sends the portrait template and only upstream screens`() {
        val settings = on("https://p.test/{imdb_id}").copy(
            enabledScreens = setOf(CustomPosterScreen.Home, CustomPosterScreen.Discover),
        )
        assertEquals("https://p.test/{imdb_id}", CustomPosterSync.exportPattern(settings))
        assertEquals("home", CustomPosterSync.exportScreens(settings))
    }

    @Test
    fun `sync export says none rather than an empty list`() {
        val settings = on("https://p.test/{imdb_id}").copy(enabledScreens = setOf(CustomPosterScreen.Discover))
        assertEquals("none", CustomPosterSync.exportScreens(settings))
    }

    @Test
    fun `sync export is blank while off and withheld for key templates`() {
        assertEquals("", CustomPosterSync.exportPattern(on("https://p.test/{imdb_id}").copy(enabled = false)))
        assertNull(CustomPosterSync.exportPattern(on("https://p.test/{imdb_id}?k={tmdb_key}")))
    }
}
