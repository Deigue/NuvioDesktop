package com.nuvio.app.features.posterservice

internal expect object CustomPosterSettingsStorage {
    fun loadEnabled(): Boolean?
    fun saveEnabled(enabled: Boolean)
    fun loadPosterUrlTemplate(): String?
    fun savePosterUrlTemplate(template: String)
    fun loadLandscapeUrlTemplate(): String?
    fun saveLandscapeUrlTemplate(template: String)
    /** Upstream's screen keys; null when never chosen (= every screen). */
    fun loadEnabledScreens(): List<String>?
    fun saveEnabledScreens(keys: List<String>)
    fun loadContinueWatchingOverStills(): Boolean
    fun saveContinueWatchingOverStills(overStills: Boolean)
    /** Whether this profile's poster settings have been reconciled with the synced blob once. */
    fun loadSyncSeeded(): Boolean
    fun saveSyncSeeded(seeded: Boolean)
}
