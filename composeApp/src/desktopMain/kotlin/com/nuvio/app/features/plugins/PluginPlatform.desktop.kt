package com.nuvio.app.features.plugins

import com.nuvio.app.core.storage.DesktopStorage
import java.security.MessageDigest
import java.util.Locale

internal object PluginStorage {
    private const val pluginsStateKey = "plugins_state"
    private val store = DesktopStorage.store("nuvio_plugins")

    fun loadState(profileId: Int): String? =
        store.getString("${pluginsStateKey}_$profileId")

    fun saveState(profileId: Int, payload: String) {
        store.putString("${pluginsStateKey}_$profileId", payload)
    }

    /**
     * Scraper source, keyed by SHA-256 and shared by every profile. Refreshing a repository whose
     * scrapers did not change writes nothing here.
     */
    private val codeStore = DesktopStorage.store("nuvio_plugin_code")
    private val codeHashPattern = Regex(""""codeHash"\s*:\s*"([0-9a-f]{64})"""")

    fun saveCode(code: String): String {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(code.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        if (!codeStore.contains(hash)) codeStore.putString(hash, code)
        return hash
    }

    fun loadCode(hash: String): String? = codeStore.getString(hash)

    /** Drops sources no profile's state refers to any more (removed repos, superseded versions). */
    fun pruneUnreferencedCode() {
        val referenced = store.keys()
            .filter { key -> key.startsWith("${pluginsStateKey}_") }
            .flatMapTo(mutableSetOf()) { key ->
                codeHashPattern.findAll(store.getString(key).orEmpty()).map { it.groupValues[1] }
            }
        val stale = codeStore.keys() - referenced
        if (stale.isNotEmpty()) codeStore.removeAll(stale)
    }

    fun loadScraperSettings(scraperId: String): String? =
        store.getString("settings_$scraperId")

    fun saveScraperSettings(scraperId: String, payload: String) {
        store.putString("settings_$scraperId", payload)
    }
}

internal fun currentPluginPlatform(): String = "desktop"

internal fun currentPluginPlatformTags(): Set<String> {
    val osName = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
    val osTag = when {
        osName.contains("mac") || osName.contains("darwin") -> "macos"
        osName.contains("win") -> "windows"
        osName.contains("linux") -> "linux"
        else -> null
    }
    return buildSet {
        add(currentPluginPlatform())
        add("jvm")
        osTag?.let(::add)
    }
}

internal fun currentEpochMillis(): Long = System.currentTimeMillis()
