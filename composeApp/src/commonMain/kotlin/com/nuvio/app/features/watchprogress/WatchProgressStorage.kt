package com.nuvio.app.features.watchprogress

internal expect object WatchProgressStorage {
    fun loadPayload(profileId: Int): String?
    fun savePayload(profileId: Int, payload: String)

    /** Writes any pending [savePayload] to disk now rather than on the storage layer's debounce. */
    fun flush()
}
