package com.nuvio.app.features.posterservice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object CustomPosterSettingsRepository {
    private val _uiState = MutableStateFlow(CustomPosterSettings())
    val uiState: StateFlow<CustomPosterSettings> = _uiState.asStateFlow()

    private var hasLoaded = false

    private var enabled = false
    private var posterUrlTemplate = ""
    private var landscapeUrlTemplate = ""
    private var enabledScreens: Set<CustomPosterScreen> = CustomPosterScreen.DEFAULT
    private var continueWatchingOverStills = false

    fun ensureLoaded() {
        if (hasLoaded) return
        loadFromDisk()
    }

    fun onProfileChanged() {
        loadFromDisk()
    }

    fun snapshot(): CustomPosterSettings {
        ensureLoaded()
        return _uiState.value
    }

    /** The settings as [screen] should apply them — off when the user excluded that screen. */
    fun snapshot(screen: CustomPosterScreen): CustomPosterSettings = snapshot().forScreen(screen)

    fun setEnabled(value: Boolean) {
        ensureLoaded()
        if (value && posterUrlTemplate.isBlank() && landscapeUrlTemplate.isBlank()) return
        if (enabled == value) return
        enabled = value
        publish()
        CustomPosterSettingsStorage.saveEnabled(value)
    }

    fun setPosterUrlTemplate(value: String) {
        ensureLoaded()
        val normalized = value.trim()
        if (posterUrlTemplate == normalized) return
        posterUrlTemplate = normalized
        disableIfNoTemplates()
        publish()
        CustomPosterSettingsStorage.savePosterUrlTemplate(normalized)
    }

    fun setLandscapeUrlTemplate(value: String) {
        ensureLoaded()
        val normalized = value.trim()
        if (landscapeUrlTemplate == normalized) return
        landscapeUrlTemplate = normalized
        disableIfNoTemplates()
        publish()
        CustomPosterSettingsStorage.saveLandscapeUrlTemplate(normalized)
    }

    fun setScreenEnabled(screen: CustomPosterScreen, value: Boolean) {
        ensureLoaded()
        val updated = if (value) enabledScreens + screen else enabledScreens - screen
        if (updated == enabledScreens) return
        enabledScreens = updated
        publish()
        CustomPosterSettingsStorage.saveEnabledScreens(CustomPosterScreen.toKeys(updated))
    }

    fun setContinueWatchingMode(mode: CustomPosterContinueWatchingMode) {
        ensureLoaded()
        val screens = if (mode == CustomPosterContinueWatchingMode.Off) {
            enabledScreens - CustomPosterScreen.ContinueWatching
        } else {
            enabledScreens + CustomPosterScreen.ContinueWatching
        }
        val overStills = mode == CustomPosterContinueWatchingMode.All
        if (screens == enabledScreens && overStills == continueWatchingOverStills) return
        enabledScreens = screens
        continueWatchingOverStills = overStills
        publish()
        CustomPosterSettingsStorage.saveEnabledScreens(CustomPosterScreen.toKeys(screens))
        CustomPosterSettingsStorage.saveContinueWatchingOverStills(overStills)
    }

    /**
     * Applies the synced half of the settings from the profile blob — see [CustomPosterSync], which
     * decides what the remote values mean. Fork-only screens keep their local state.
     */
    internal fun applySynced(
        enabled: Boolean,
        posterUrlTemplate: String,
        syncedScreens: Set<CustomPosterScreen>,
    ) {
        ensureLoaded()
        val template = posterUrlTemplate.trim()
        val screens = syncedScreens.filter { it.synced }.toSet() +
            enabledScreens.filter { !it.synced }
        val effectiveEnabled = enabled && (template.isNotBlank() || landscapeUrlTemplate.isNotBlank())
        if (template == this.posterUrlTemplate && effectiveEnabled == this.enabled && screens == enabledScreens) return
        this.posterUrlTemplate = template
        this.enabled = effectiveEnabled
        enabledScreens = screens
        publish()
        CustomPosterSettingsStorage.savePosterUrlTemplate(template)
        CustomPosterSettingsStorage.saveEnabled(effectiveEnabled)
        CustomPosterSettingsStorage.saveEnabledScreens(CustomPosterScreen.toKeys(screens))
    }

    /** Clearing the last template leaves nothing to route through, so the switch follows it off. */
    private fun disableIfNoTemplates() {
        if (!enabled || posterUrlTemplate.isNotBlank() || landscapeUrlTemplate.isNotBlank()) return
        enabled = false
        CustomPosterSettingsStorage.saveEnabled(false)
    }

    private fun loadFromDisk() {
        hasLoaded = true
        posterUrlTemplate = CustomPosterSettingsStorage.loadPosterUrlTemplate()?.trim().orEmpty()
        landscapeUrlTemplate = CustomPosterSettingsStorage.loadLandscapeUrlTemplate()?.trim().orEmpty()
        enabled = (CustomPosterSettingsStorage.loadEnabled() ?: false) &&
            (posterUrlTemplate.isNotBlank() || landscapeUrlTemplate.isNotBlank())
        enabledScreens = CustomPosterSettingsStorage.loadEnabledScreens()
            ?.let(::screensFromStoredKeys)
            ?: CustomPosterScreen.DEFAULT
        continueWatchingOverStills = CustomPosterSettingsStorage.loadContinueWatchingOverStills()
        publish()
    }

    /**
     * A stored selection is explicit even when empty (every screen switched off), unlike upstream's
     * wire format where empty means "never chosen" — [CustomPosterScreen.fromKeys] would turn a
     * deliberate "none" back into "all".
     */
    private fun screensFromStoredKeys(keys: List<String>): Set<CustomPosterScreen> =
        keys.mapNotNull { key -> CustomPosterScreen.entries.firstOrNull { it.key == key } }.toSet()

    private fun publish() {
        _uiState.value = CustomPosterSettings(
            enabled = enabled,
            posterUrlTemplate = posterUrlTemplate,
            landscapeUrlTemplate = landscapeUrlTemplate,
            enabledScreens = enabledScreens,
            continueWatchingOverStills = continueWatchingOverStills,
        )
    }
}
