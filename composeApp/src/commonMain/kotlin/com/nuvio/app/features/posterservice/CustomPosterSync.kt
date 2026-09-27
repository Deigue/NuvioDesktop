package com.nuvio.app.features.posterservice

/**
 * Maps the poster service onto upstream's two profile-settings keys, so a pattern set in the
 * official apps shows up here and vice versa. Upstream has one pattern (blank = off) and a screen
 * list; this fork has a switch, two templates, and fork-only screens, so the mapping is:
 *
 * - **Pattern** = the portrait template while the switch is on, blank while it is off. A portrait
 *   template naming `{shape}` already carries upstream's landscape form; the separate landscape
 *   template and the fork-only screens stay on this device.
 * - A template using this fork's key placeholders (`{tmdb_key}`, `{mdblist_key}`) is **not sent**:
 *   upstream would request the literal placeholder. The profile keeps whatever pattern it had, and
 *   a remote pattern does not replace such a template here either.
 * - **Screens**: upstream reads an empty list as "every screen", so "none" is sent as an unknown key
 *   (`none`), which upstream's parser reduces to an empty selection.
 *
 * A key missing from the remote blob means the writer predates the feature: nothing is changed.
 */
internal object CustomPosterSync {
    const val PATTERN_KEY = "custom_poster_url_pattern"
    const val SCREENS_KEY = "custom_poster_enabled_screens"
    private const val NO_SCREENS = "none"

    /** The pattern to publish, or null when the profile's current value must be left in place. */
    fun exportPattern(settings: CustomPosterSettings = CustomPosterSettingsRepository.snapshot()): String? {
        val template = settings.posterUrlTemplate
        return when {
            !settings.enabled || template.isBlank() -> ""
            customPosterTemplateIsPortable(template) -> template
            else -> null
        }
    }

    fun exportScreens(settings: CustomPosterSettings = CustomPosterSettingsRepository.snapshot()): String {
        val synced = settings.enabledScreens.filter { it.synced }.toSet()
        return if (synced.isEmpty()) NO_SCREENS else CustomPosterScreen.toKeys(synced).joinToString(",")
    }

    /** What a push would carry, for the sync observer's change signature. */
    fun signature(): String {
        val settings = CustomPosterSettingsRepository.snapshot()
        return "${exportPattern(settings)}|${exportScreens(settings)}"
    }

    /**
     * Applies the remote values ([pattern]/[screens] null when the key is absent). Returns true when
     * the local settings were kept instead and should be pushed: the first sync after this feature
     * arrived, with an active local template and nothing on the profile yet — an existing desktop
     * setup must not be switched off by a blob that simply never had the key filled in.
     */
    fun applyRemote(pattern: String?, screens: String?): Boolean {
        val local = CustomPosterSettingsRepository.snapshot()
        val seeded = CustomPosterSettingsStorage.loadSyncSeeded()
        if (!seeded) CustomPosterSettingsStorage.saveSyncSeeded(true)
        val remotePattern = pattern?.trim()
        if (!seeded && local.isActive && remotePattern.isNullOrBlank()) return true

        val localTemplate = local.posterUrlTemplate
        val localIsPrivate = local.enabled && localTemplate.isNotBlank() && !customPosterTemplateIsPortable(localTemplate)
        var enabled = local.enabled
        var template = localTemplate
        when {
            remotePattern == null || localIsPrivate -> Unit
            remotePattern.isNotBlank() -> {
                enabled = true
                template = remotePattern
            }
            // Blank remote = off upstream. A local switch that is on only for a landscape template
            // was never published as a pattern, so a blank one says nothing about it.
            localTemplate.isNotBlank() -> enabled = false
        }
        CustomPosterSettingsRepository.applySynced(
            enabled = enabled,
            posterUrlTemplate = template,
            syncedScreens = screens?.let(::parseScreens) ?: local.enabledScreens,
        )
        return false
    }

    private fun parseScreens(raw: String): Set<CustomPosterScreen> =
        CustomPosterScreen.fromKeys(raw.split(',').map { it.trim() }.filter { it.isNotEmpty() })
}
