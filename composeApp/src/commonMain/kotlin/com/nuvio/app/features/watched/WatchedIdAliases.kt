package com.nuvio.app.features.watched

/**
 * Content ids known to name the same title, so watched state recorded under one answers under the
 * others.
 *
 * Watched rows are keyed by the id the page that wrote them used. A metadata addon that ids titles
 * `tmdb:1668` and a provider import that ids them `tt0108778` therefore never meet: a whole-season
 * mark made here comes back from SIMKL as a second, identical set of rows under the IMDb id, and
 * SIMKL history imported under the IMDb id never shows on the `tmdb:` details page. Measured on a
 * real account, every bulk mark of 40–81 episodes was echoed back as as many duplicate rows.
 *
 * Fed from the import's own id pairs (SIMKL reports both ids for each title), never guessed. Like
 * [animeAlternateWatchedKeys] this is **keys only**: an alias makes a row answer under another id,
 * it never adds a row, so Continue Watching does not grow a duplicate entry for one episode.
 *
 * Anime is left to its own aliasing — one IMDb id spans several anime entries, so pairing it with
 * one entry's TMDB id is not a statement of identity.
 */
internal object WatchedIdAliases {
    @Volatile
    private var aliases: Map<String, Set<String>> = emptyMap()

    /** Bumped whenever the alias set actually changes, so the store knows to republish its keys. */
    @Volatile
    var version: Long = 0L
        private set

    /** Replaces the alias set with [groups], each a set of ids for one title. */
    fun replace(groups: Collection<Set<String>>) {
        val next = buildAliasMap(groups)
        if (next == aliases) return
        aliases = next
        version += 1
    }

    fun aliasesOf(id: String): Set<String> = aliases[id.trim()].orEmpty()

    fun clear() = replace(emptyList())
}

internal fun buildAliasMap(groups: Collection<Set<String>>): Map<String, Set<String>> {
    val map = mutableMapOf<String, MutableSet<String>>()
    groups.forEach { group ->
        val ids = group.map(String::trim).filter(String::isNotEmpty).toSet()
        if (ids.size < 2) return@forEach
        ids.forEach { id -> map.getOrPut(id) { linkedSetOf() } += ids - id }
    }
    return map
}

/** The keys each item also answers under, through [aliasesOf]. */
internal fun aliasedWatchedKeys(
    items: Collection<WatchedItem>,
    aliasesOf: (String) -> Set<String>,
): Set<String> {
    val keys = linkedSetOf<String>()
    items.forEach { item ->
        aliasesOf(item.id).forEach { alias ->
            keys += watchedItemKey(item.type, alias, item.season, item.episode)
        }
    }
    return keys
}

/**
 * [items] without imported rows that duplicate one of the user's own rows under an alias id.
 *
 * Only imported rows ever go, and only when the user's own row for the same episode exists: that
 * row is the one the page they use reads, and it stays the authority. Two imported copies cannot
 * occur — an import writes each title under a single id.
 */
internal fun dropAliasedImportDuplicates(
    items: Map<String, WatchedItem>,
    aliasesOf: (String) -> Set<String>,
): Map<String, WatchedItem> {
    val duplicateKeys = items.filter { (_, item) ->
        item.importedFrom != null && aliasesOf(item.id).any { alias ->
            items[watchedItemKey(item.type, alias, item.season, item.episode)]
                ?.let { other -> other.importedFrom == null } == true
        }
    }.keys
    if (duplicateKeys.isEmpty()) return items
    return items - duplicateKeys
}
