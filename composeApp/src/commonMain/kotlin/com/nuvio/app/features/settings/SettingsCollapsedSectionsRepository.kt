package com.nuvio.app.features.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class StoredSettingsCollapsedSectionsPayload(
    val anchors: List<String> = emptyList(),
)

/**
 * Desktop settings sections the user has collapsed by clicking their heading. Persisted per
 * profile.
 *
 * Entries are the heading anchor ids [SettingsSection] derives (`heading:<PageName>:<title>`), the
 * same ids favourites use. Unknown ids are kept rather than pruned: a renamed section simply comes
 * back expanded, and nothing breaks if a stale id lingers.
 */
internal object SettingsCollapsedSectionsRepository {
    private val json = Json { ignoreUnknownKeys = true }

    private val _collapsed = MutableStateFlow<Set<String>>(emptySet())
    val collapsed: StateFlow<Set<String>> = _collapsed.asStateFlow()

    private var hasLoaded = false

    fun ensureLoaded() {
        if (hasLoaded) return
        hasLoaded = true

        val parsed = SettingsCollapsedSectionsStorage.loadPayload()?.let {
            runCatching {
                json.decodeFromString<StoredSettingsCollapsedSectionsPayload>(it)
            }.getOrNull()
        }
        _collapsed.value = parsed?.anchors.orEmpty().toSet()
    }

    fun onProfileChanged() {
        hasLoaded = false
        _collapsed.value = emptySet()
        ensureLoaded()
    }

    fun setCollapsed(anchor: String, collapsed: Boolean) {
        ensureLoaded()
        val current = _collapsed.value
        val next = if (collapsed) current + anchor else current - anchor
        if (next == current) return
        _collapsed.value = next
        persist()
    }

    private fun persist() {
        SettingsCollapsedSectionsStorage.savePayload(
            json.encodeToString(
                StoredSettingsCollapsedSectionsPayload(anchors = _collapsed.value.sorted()),
            ),
        )
    }
}
