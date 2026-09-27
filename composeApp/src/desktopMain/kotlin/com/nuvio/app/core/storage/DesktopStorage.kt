package com.nuvio.app.core.storage

import co.touchlab.kermit.Logger
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.Comparator
import java.util.Locale
import java.util.Properties
import java.util.UUID
import kotlin.io.path.exists

internal object DesktopStorage {
    private val log = Logger.withTag("DesktopStorage")
    private const val SLOW_LOAD_LOG_THRESHOLD_MS = 250L
    private val json = Json { ignoreUnknownKeys = true }
    private val stores = mutableMapOf<String, Store>()

    val rootDir: Path by lazy {
        resolveAppDataDir()
    }

    @Volatile
    private var freshInstall = false

    /**
     * True when neither a Nuvio HTPC data directory nor a legacy Nuvio directory existed when
     * storage was first resolved — i.e. a genuinely new user, not an upgrade and not someone who
     * simply never changed a given setting. Touching this resolves [rootDir], so the answer is
     * captured before anything has had a chance to create the directory.
     */
    val isFreshInstall: Boolean
        get() {
            rootDir
            return freshInstall
        }

    fun store(name: String): Store = synchronized(stores) {
        stores.getOrPut(name) { Store(rootDir.resolve("$name.properties")) }
    }

    fun wipe(preservedStoreNames: Set<String> = emptySet()) {
        synchronized(stores) {
            val iterator = stores.iterator()
            while (iterator.hasNext()) {
                val (name, store) = iterator.next()
                if (name !in preservedStoreNames) {
                    store.clearInMemory()
                    iterator.remove()
                }
            }
        }
        if (!rootDir.exists()) return
        val preservedFiles = preservedStoreNames
            .map { name -> rootDir.resolve("$name.properties").normalize() }
            .toSet()
        Files.walk(rootDir).use { stream ->
            stream
                .sorted(Comparator.reverseOrder())
                .filter { it != rootDir }
                .filter { it.normalize() !in preservedFiles }
                .forEach { path -> runCatching { Files.deleteIfExists(path) } }
        }
    }

    private fun resolveAppDataDir(): Path {
        val osName = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
        val userHome = Paths.get(System.getProperty("user.home").orEmpty())
        if (!osName.contains("win")) {
            val parent = if (osName.contains("mac")) {
                userHome.resolve("Library/Application Support")
            } else {
                System.getenv("XDG_CONFIG_HOME")
                    ?.takeIf { it.isNotBlank() }
                    ?.let(Paths::get)
                    ?: userHome.resolve(".config")
            }
            val destination = parent.resolve(if (osName.contains("mac")) "NuvioHTPC" else "nuviohtpc")
            val legacy = parent.resolve(if (osName.contains("mac")) "Nuvio" else "nuvio")
            freshInstall = !destination.exists() && !legacy.exists()
            migrateLegacyDirectories(destination, legacy)
            return destination
        }

        val localAppData = System.getenv("LOCALAPPDATA")
            ?.takeIf { it.isNotBlank() }
            ?.let(Paths::get)
            ?: userHome.resolve("AppData/Local")
        val destination = localAppData.resolve("NuvioHTPC")
        if (destination.exists()) return destination

        // Nuvio Desktop stored preferences in roaming AppData while its logs, custom badges,
        // and updater files lived in Local AppData. Nuvio HTPC uses a single Local AppData
        // directory, so bring both old locations forward once before creating any new files.
        val roamingNuvio = System.getenv("APPDATA")
            ?.takeIf { it.isNotBlank() }
            ?.let(Paths::get)
            ?.resolve("Nuvio")
            ?: userHome.resolve("AppData/Roaming/Nuvio")
        val localNuvio = localAppData.resolve("Nuvio")
        // These are also the *official* Nuvio Desktop's directories (settings in roaming, cache in
        // local), so their existence alone only proves the user has tried upstream. That user is
        // new to this fork and gets the wizard and new-install defaults; only a directory holding a
        // store this fork alone ever wrote marks an upgrade from a pre-1.10 HTPC build.
        freshInstall = listOf(roamingNuvio, localNuvio).none(::holdsHtpcOnlyStore)
        migrateLegacyDirectories(destination, roamingNuvio, localNuvio)
        return destination
    }

    /**
     * Stores HTPC wrote into the shared `Nuvio` directory up to 1.9 (1.10 moved to `NuvioHTPC`)
     * that upstream Nuvio Desktop has never had. Checked against upstream/Dev on 2026-09-25; if
     * upstream later adopts one of these names, drop it from the list.
     */
    private val HTPC_ONLY_LEGACY_STORES = listOf(
        "nuvio_api_keys_onboarding",
        "nuvio_discord_presence",
        "nuvio_mdblist_ratings_cache",
        "nuvio_player_shortcuts",
        "nuvio_settings_category_order",
        "nuvio_settings_favorites",
        "nuvio_simkl_settings",
        "nuvio_tvdb_settings",
    )

    internal fun holdsHtpcOnlyStore(directory: Path): Boolean =
        directory.exists() && HTPC_ONLY_LEGACY_STORES.any { name -> directory.resolve("$name.properties").exists() }

    private fun migrateLegacyDirectories(destination: Path, vararg sources: Path) {
        synchronized(stores) {
            if (destination.exists()) return
            val staging = destination.resolveSibling("${destination.fileName}.migrating-${UUID.randomUUID()}")
            try {
                Files.createDirectories(staging)
                sources
                    .filter { it.exists() && it != destination }
                    .forEach { source ->
                        Files.walk(source).use { paths ->
                            paths.forEach { current ->
                                val target = staging.resolve(source.relativize(current).toString())
                                if (Files.isDirectory(current)) {
                                    Files.createDirectories(target)
                                } else if (!target.exists()) {
                                    Files.createDirectories(target.parent)
                                    Files.copy(current, target)
                                }
                            }
                        }
                    }
                runCatching { Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE) }
                    .getOrElse { Files.move(staging, destination) }
            } catch (error: Exception) {
                // Migration problems must not block startup; the original Nuvio folders remain
                // untouched and the app starts with a fresh destination instead.
                System.err.println("Unable to migrate Nuvio data to $destination: ${error.message}")
                Files.createDirectories(destination)
            }
        }
    }

    internal class Store(
        private val file: Path,
    ) {
        private val lock = Any()
        private val properties = Properties()
        private var loaded = false

        fun contains(key: String): Boolean = synchronized(lock) {
            ensureLoaded()
            properties.containsKey(key)
        }

        fun getString(key: String): String? = synchronized(lock) {
            ensureLoaded()
            properties.getProperty(key)
        }

        fun putString(key: String, value: String?) = synchronized(lock) {
            ensureLoaded()
            if (value == null) {
                properties.remove(key)
            } else {
                properties.setProperty(key, value)
            }
            persist()
        }

        fun getBoolean(key: String): Boolean? =
            getString(key)?.toBooleanStrictOrNull()

        fun putBoolean(key: String, value: Boolean) {
            putString(key, value.toString())
        }

        fun getInt(key: String): Int? =
            getString(key)?.toIntOrNull()

        fun putInt(key: String, value: Int) {
            putString(key, value.toString())
        }

        fun getFloat(key: String): Float? =
            getString(key)?.toFloatOrNull()

        fun putFloat(key: String, value: Float) {
            putString(key, value.toString())
        }

        fun getStringSet(key: String): Set<String>? =
            getString(key)?.let { payload ->
                runCatching { json.decodeFromString<List<String>>(payload).toSet() }.getOrNull()
            }

        fun putStringSet(key: String, values: Set<String>) {
            putString(key, json.encodeToString(values.toList()))
        }

        fun remove(key: String) = synchronized(lock) {
            ensureLoaded()
            properties.remove(key)
            persist()
        }

        fun removeAll(keys: Iterable<String>) = synchronized(lock) {
            ensureLoaded()
            keys.forEach(properties::remove)
            persist()
        }

        fun clearInMemory() = synchronized(lock) {
            properties.clear()
            loaded = false
        }

        private fun ensureLoaded() {
            if (loaded) return
            loaded = true
            properties.clear()
            if (!file.exists()) return
            // Timed separately because the open alone has stalled the UI thread for seconds
            // (2026-09-14 launch sampler: 5.75s in `CreateFile0` under this method). These files
            // are rewritten on every save, so an on-access scanner treats each open as a fresh
            // file; knowing which store and whether it was the open or the parse is what the next
            // slow launch needs to say.
            val startedAt = System.nanoTime()
            var openedAt = startedAt
            runCatching {
                Files.newInputStream(file).use { input ->
                    openedAt = System.nanoTime()
                    properties.load(input)
                }
            }
            val totalMs = (System.nanoTime() - startedAt) / 1_000_000
            if (totalMs >= SLOW_LOAD_LOG_THRESHOLD_MS) {
                val openMs = (openedAt - startedAt) / 1_000_000
                log.w {
                    "slow store load: ${file.fileName} took ${totalMs}ms " +
                        "(open ${openMs}ms, parse ${totalMs - openMs}ms) on ${Thread.currentThread().name}"
                }
            }
        }

        private fun persist() {
            Files.createDirectories(file.parent)
            // Write to a sibling temp file and atomically swap it in, rather than truncating the
            // real file and writing in place. The old in-place write left a window where a crash
            // (the app has intermittent silent CTDs) or kill mid-write would leave a half-written,
            // unparseable file — on next launch that store would silently reset to empty, which for
            // the large MDBList ratings/cast cache meant losing the whole cache and refetching
            // everything. With the swap, an interrupted write only ever leaves a stale .tmp; the
            // real file stays intact and complete.
            val tmp = file.resolveSibling("${file.fileName}.tmp")
            runCatching {
                Files.newOutputStream(tmp).use { output ->
                    properties.store(output, "Nuvio desktop preferences")
                }
                runCatching {
                    Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE)
                }.recoverCatching {
                    // Rare: some filesystems reject ATOMIC_MOVE onto an existing target. A plain
                    // replace still swaps in a fully-written temp file, so the destination is never
                    // left half-written the way the old truncate-in-place write could.
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
                }.getOrThrow()
            }.onFailure { error ->
                runCatching { Files.deleteIfExists(tmp) }
                throw error
            }
        }
    }
}
