package com.nuvio.app.features.watched

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WatchedIdAliasesTest {
    private val aliases = buildAliasMap(listOf(setOf("tt0108778", "tmdb:1668")))
    private val aliasesOf: (String) -> Set<String> = { aliases[it].orEmpty() }

    private fun episode(id: String, number: Int, importedFrom: String? = null) = WatchedItem(
        id = id,
        type = "series",
        name = "Friends",
        season = 1,
        episode = number,
        markedAtEpochMs = 1L,
        importedFrom = importedFrom,
    )

    private fun Collection<WatchedItem>.keyed() =
        associateBy { watchedItemKey(it.type, it.id, it.season, it.episode) }

    @Test
    fun `aliases run both ways and ignore single ids`() {
        val map = buildAliasMap(listOf(setOf("tt1", "tmdb:1"), setOf("tt2")))
        assertEquals(setOf("tmdb:1"), map["tt1"])
        assertEquals(setOf("tt1"), map["tmdb:1"])
        assertTrue("tt2" !in map)
    }

    @Test
    fun `an imported row answers under the id the details page uses`() {
        val keys = aliasedWatchedKeys(listOf(episode("tt0108778", 3, importedFrom = "simkl")), aliasesOf)
        assertEquals(setOf(watchedItemKey("series", "tmdb:1668", 1, 3)), keys)
    }

    @Test
    fun `an import echo of the user's own mark is dropped`() {
        val own = episode("tmdb:1668", 1)
        val echo = episode("tt0108778", 1, importedFrom = "simkl")
        val importedOnly = episode("tt0108778", 2, importedFrom = "simkl")

        val result = dropAliasedImportDuplicates(listOf(own, echo, importedOnly).keyed(), aliasesOf)

        assertEquals(setOf(own, importedOnly), result.values.toSet())
    }

    @Test
    fun `the user's own rows are never dropped`() {
        val items = listOf(episode("tmdb:1668", 1), episode("tt0108778", 1)).keyed()
        assertEquals(items, dropAliasedImportDuplicates(items, aliasesOf))
    }
}
