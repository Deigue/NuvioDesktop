package com.nuvio.app.features.addons

import com.nuvio.app.features.catalog.supportsPagination
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AddonManifestParserTest {
    @Test
    fun `catalog showInHome is parsed and defaults to true`() {
        val manifest = AddonManifestParser.parse(
            manifestUrl = "https://example.test/manifest.json",
            payload = """
                {
                  "id": "example",
                  "name": "Example",
                  "version": "1.0.0",
                  "resources": ["catalog"],
                  "types": ["movie"],
                  "catalogs": [
                    { "type": "movie", "id": "visible", "name": "Visible", "showInHome": true },
                    { "type": "movie", "id": "collection-only", "name": "Collection Only", "showInHome": false },
                    { "type": "series", "id": "default-visible", "name": "Default Visible" }
                  ]
                }
            """.trimIndent(),
        )

        assertTrue(manifest.catalogs.first { it.id == "visible" }.showInHome)
        assertFalse(manifest.catalogs.first { it.id == "collection-only" }.showInHome)
        assertTrue(manifest.catalogs.first { it.id == "default-visible" }.showInHome)
    }

    @Test
    fun `legacy extraSupported and extraRequired count as catalog extras`() {
        val manifest = AddonManifestParser.parse(
            manifestUrl = "https://example.test/manifest.json",
            payload = """
                {
                  "id": "example",
                  "name": "Example",
                  "version": "1.0.0",
                  "resources": ["catalog"],
                  "types": ["movie"],
                  "catalogs": [
                    {
                      "type": "movie", "id": "legacy", "name": "Legacy",
                      "extraSupported": ["skip", "search", "genre"],
                      "extraRequired": ["genre"]
                    },
                    {
                      "type": "movie", "id": "mixed", "name": "Mixed",
                      "extra": [{ "name": "genre", "options": ["Action"], "isRequired": true }],
                      "extraSupported": ["genre", "skip"]
                    },
                    { "type": "movie", "id": "modern", "name": "Modern", "extra": [{ "name": "skip" }] }
                  ]
                }
            """.trimIndent(),
        )

        val legacy = manifest.catalogs.first { it.id == "legacy" }
        assertTrue(legacy.supportsPagination())
        assertEquals(listOf("skip", "search", "genre"), legacy.extra.map { it.name })
        assertTrue(legacy.extra.first { it.name == "genre" }.isRequired)
        assertFalse(legacy.extra.first { it.name == "skip" }.isRequired)

        // The declared entry keeps its options; the legacy list only adds what `extra` lacks.
        val mixed = manifest.catalogs.first { it.id == "mixed" }
        assertEquals(listOf("genre", "skip"), mixed.extra.map { it.name })
        assertEquals(listOf("Action"), mixed.extra.first { it.name == "genre" }.options)
        assertTrue(mixed.supportsPagination())

        assertTrue(manifest.catalogs.first { it.id == "modern" }.supportsPagination())
    }
}
