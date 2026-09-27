package com.nuvio.app.features.details

import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.usesInfiniteHomeRow
import com.nuvio.app.features.tmdb.TmdbEntityBrowseData
import com.nuvio.app.features.tmdb.TmdbEntityHeader
import com.nuvio.app.features.tmdb.TmdbEntityKind
import com.nuvio.app.features.tmdb.TmdbEntityMediaType
import com.nuvio.app.features.tmdb.TmdbEntityRail
import com.nuvio.app.features.tmdb.TmdbEntityRailType
import com.nuvio.app.features.posterservice.CustomPosterSettings
import com.nuvio.app.features.posterservice.withCustomPosters
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TmdbEntityBrowseCatalogTest {
    @Test
    fun `entity rails become inline home catalogs with stable paging state`() {
        val preview = MetaPreview(
            id = "tmdb:1399",
            type = "series",
            name = "Game of Thrones",
            poster = "plain-poster",
        )
        val data = TmdbEntityBrowseData(
            header = TmdbEntityHeader(
                id = 49,
                kind = TmdbEntityKind.NETWORK,
                name = "HBO",
                logo = "hbo-logo",
                originCountry = "US",
                secondaryLabel = null,
                description = null,
            ),
            rails = listOf(
                TmdbEntityRail(
                    mediaType = TmdbEntityMediaType.TV,
                    railType = TmdbEntityRailType.POPULAR,
                    items = listOf(preview),
                    currentPage = 2,
                    hasMore = true,
                    isLoading = true,
                ),
            ),
        )

        val section = buildEntityCatalogSections(
            rails = data.rails,
            name = data.header.name,
            labels = EntityCatalogLabels(
                movies = "Movies",
                series = "Series",
                popular = "Popular",
                topRated = "Top rated",
                recent = "Recent",
            ),
        ).single()

        assertEquals("tmdb-entity:tv:popular", section.key)
        assertEquals("Series • Popular • HBO", section.title)
        assertEquals("", section.subtitle)
        assertEquals(listOf(preview), section.items)
        assertNull(section.target)
        assertTrue(section.inlineOnly)
        assertTrue(section.paginates)
        assertTrue(section.hasMore)
        assertTrue(section.isLoadingMore)
        assertEquals(3, section.nextSkip)
        assertTrue(section.usesInfiniteHomeRow(catalogSeeMoreEnabled = true))
    }

    @Test
    fun `badge rails label winners and drop the rail word for featured lists`() {
        val preview = MetaPreview(id = "tmdb:1054867", type = "movie", name = "One Battle After Another", poster = "p")
        val labels = EntityCatalogLabels(
            movies = "Movies",
            series = "Series",
            popular = "Popular",
            topRated = "Top rated",
            recent = "Recent",
            winners = "Winners",
            nominees = "Nominees",
        )
        val rails = listOf(
            TmdbEntityRail(mediaType = TmdbEntityMediaType.MOVIE, railType = TmdbEntityRailType.WINNERS, items = listOf(preview)),
            TmdbEntityRail(mediaType = TmdbEntityMediaType.TV, railType = TmdbEntityRailType.FEATURED, items = listOf(preview)),
        )

        val sections = buildEntityCatalogSections(rails = rails, name = "Best Picture", labels = labels)

        assertEquals("tmdb-entity:movie:winners", sections[0].key)
        assertEquals("Movies • Winners • Best Picture", sections[0].title)
        assertEquals("tmdb-entity:tv:featured", sections[1].key)
        assertEquals("Series • Best Picture", sections[1].title)
        assertFalse(sections[0].hasMore)
        assertNull(sections[0].nextSkip)
    }

    @Test
    fun `entity poster uses custom library template and keeps TMDB fallback`() {
        val preview = MetaPreview(
            id = "tmdb:1399",
            type = "series",
            name = "Game of Thrones",
            poster = "https://image.tmdb.org/t/p/w500/plain.jpg",
        )

        val styled = preview.withCustomPosters(
            settings = CustomPosterSettings(
                enabled = true,
                posterUrlTemplate = "https://posters.example/{type}/{tmdb_id}",
            ),
            imdbId = null,
            tmdbId = 1399,
        )

        assertEquals("https://posters.example/series/1399", styled.poster)
        assertEquals(preview.poster, styled.posterFallback)
    }

    @Test
    fun `disabled custom library poster setting leaves TMDB poster unchanged`() {
        val preview = MetaPreview(
            id = "tmdb:1399",
            type = "series",
            name = "Game of Thrones",
            poster = "plain-poster",
        )

        val styled = preview.withCustomPosters(
            settings = CustomPosterSettings(
                enabled = false,
                posterUrlTemplate = "https://posters.example/{tmdb_id}",
            ),
            imdbId = null,
            tmdbId = 1399,
        )

        assertEquals(preview, styled)
        assertFalse(styled.posterFallback != null)
    }
}
