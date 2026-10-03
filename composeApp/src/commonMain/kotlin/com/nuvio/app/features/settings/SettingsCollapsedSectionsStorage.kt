package com.nuvio.app.features.settings

internal expect object SettingsCollapsedSectionsStorage {
    fun loadPayload(): String?
    fun savePayload(payload: String)
}
