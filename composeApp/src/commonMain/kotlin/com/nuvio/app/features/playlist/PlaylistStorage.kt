package com.nuvio.app.features.playlist

internal expect object PlaylistStorage {
    fun loadPayload(): String?
    fun savePayload(payload: String)
}
