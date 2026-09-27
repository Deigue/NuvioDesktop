package com.nuvio.app.features.posterservice

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.storage.ProfileScopedKey

/**
 * The portrait template and the screen selection also travel in the profile settings blob under
 * upstream's keys when Appearance sync is on (see `CustomPosterSync`); this store stays the local
 * source of truth either way.
 */
internal actual object CustomPosterSettingsStorage {
    private const val enabledKey = "poster_service_enabled"
    private const val posterUrlTemplateKey = "poster_service_poster_url"
    private const val landscapeUrlTemplateKey = "poster_service_landscape_url"
    private const val enabledScreensKey = "poster_service_enabled_screens"
    private const val syncSeededKey = "poster_service_sync_seeded"
    private const val continueWatchingOverStillsKey = "poster_service_cw_over_stills"
    private val store = DesktopStorage.store("nuvio_poster_service_settings")

    // The feature shipped inside the TMDB settings store. A value found only there is moved over
    // once, per profile, the first time it is read, so an existing template survives the move.
    private const val legacyEnabledKey = "tmdb_library_poster_enabled"
    private const val legacyPosterUrlTemplateKey = "tmdb_library_poster_url"
    private val legacyStore by lazy { DesktopStorage.store("nuvio_tmdb_settings") }

    actual fun loadEnabled(): Boolean? = loadString(enabledKey, legacyEnabledKey)?.toBooleanStrictOrNull()
    actual fun saveEnabled(enabled: Boolean) = store.putBoolean(ProfileScopedKey.of(enabledKey), enabled)
    actual fun loadPosterUrlTemplate(): String? = loadString(posterUrlTemplateKey, legacyPosterUrlTemplateKey)
    actual fun savePosterUrlTemplate(template: String) = store.putString(ProfileScopedKey.of(posterUrlTemplateKey), template)
    actual fun loadLandscapeUrlTemplate(): String? = store.getString(ProfileScopedKey.of(landscapeUrlTemplateKey))
    actual fun saveLandscapeUrlTemplate(template: String) = store.putString(ProfileScopedKey.of(landscapeUrlTemplateKey), template)

    actual fun loadEnabledScreens(): List<String>? =
        store.getString(ProfileScopedKey.of(enabledScreensKey))?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
    actual fun saveEnabledScreens(keys: List<String>) =
        store.putString(ProfileScopedKey.of(enabledScreensKey), keys.joinToString(","))
    actual fun loadContinueWatchingOverStills(): Boolean =
        store.getBoolean(ProfileScopedKey.of(continueWatchingOverStillsKey)) ?: false
    actual fun saveContinueWatchingOverStills(overStills: Boolean) =
        store.putBoolean(ProfileScopedKey.of(continueWatchingOverStillsKey), overStills)
    actual fun loadSyncSeeded(): Boolean = store.getBoolean(ProfileScopedKey.of(syncSeededKey)) ?: false
    actual fun saveSyncSeeded(seeded: Boolean) = store.putBoolean(ProfileScopedKey.of(syncSeededKey), seeded)

    private fun loadString(key: String, legacyKey: String): String? {
        val scoped = ProfileScopedKey.of(key)
        store.getString(scoped)?.let { return it }
        val legacyScoped = ProfileScopedKey.of(legacyKey)
        val legacy = legacyStore.getString(legacyScoped) ?: return null
        store.putString(scoped, legacy)
        legacyStore.remove(legacyScoped)
        return legacy
    }
}
