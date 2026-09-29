package com.nuvio.app.features.playlist

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.storage.ProfileScopedKey

internal actual object PlaylistStorage {
    private val store = DesktopStorage.store("nuvio_playlists")

    actual fun loadPayload(): String? =
        store.getString(ProfileScopedKey.of("playlists"))

    actual fun savePayload(payload: String) {
        store.putString(ProfileScopedKey.of("playlists"), payload)
    }
}
