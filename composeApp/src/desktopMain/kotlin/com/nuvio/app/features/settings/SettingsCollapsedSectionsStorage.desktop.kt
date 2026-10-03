package com.nuvio.app.features.settings

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.storage.ProfileScopedKey

internal actual object SettingsCollapsedSectionsStorage {
    private val store = DesktopStorage.store("nuvio_settings_collapsed_sections")

    actual fun loadPayload(): String? =
        store.getString(ProfileScopedKey.of("settings_collapsed_sections"))

    actual fun savePayload(payload: String) {
        store.putString(ProfileScopedKey.of("settings_collapsed_sections"), payload)
    }
}
