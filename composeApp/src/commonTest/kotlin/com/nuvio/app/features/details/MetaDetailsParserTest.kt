package com.nuvio.app.features.details

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class MetaDetailsParserTest {

    @Test
    fun `parse rejects null meta object without json object cast crash`() {
        assertFailsWith<IllegalStateException> {
            MetaDetailsParser.parse("""{"meta":null}""")
        }
    }

    @Test
    fun `parse accepts bare meta object response`() {
        val result = MetaDetailsParser.parse(
            """
            {
              "id": "mal:62516",
              "type": "series",
              "name": "The Fragrant Flower Blooms with Dignity"
            }
            """.trimIndent(),
        )

        assertEquals("mal:62516", result.id)
        assertEquals("series", result.type)
        assertEquals("The Fragrant Flower Blooms with Dignity", result.name)
    }

    @Test
    fun `parse preserves anime provider ids and certification fallback`() {
        val result = MetaDetailsParser.parse(
            """
            {
              "meta": {
                "id": "kitsu:42898",
                "type": "series",
                "name": "World Trigger 2",
                "_imdbId": "tt3950102",
                "_malId": "40907",
                "_tmdbId": "61628",
                "_tvdbId": "368613",
                "certification": "TV-14"
              }
            }
            """.trimIndent(),
        )

        assertEquals("tt3950102", result.imdbId)
        assertEquals("40907", result.malId)
        assertEquals(61628, result.tmdbId)
        assertEquals("368613", result.tvdbId)
        assertEquals("TV-14", result.ageRating)
    }

    @Test
    fun `parse converts addon episode runtimes to minutes`() {
        val runtimes = listOf(
            JsonPrimitive(45) to 45,
            JsonPrimitive("45") to 45,
            JsonPrimitive(" 45 min ") to 45,
            JsonPrimitive("45 minutes") to 45,
            JsonPrimitive("2h 5m") to 125,
            JsonPrimitive("1 hr 30 min") to 90,
            JsonPrimitive("1 hour 30 minutes") to 90,
            JsonPrimitive("2 HOURS") to 120,
            JsonPrimitive("1:30") to 90,
        )

        runtimes.forEach { (runtime, expected) ->
            assertEquals(expected, parseEpisode(runtime).runtime, "Runtime: $runtime")
        }
    }

    @Test
    fun `parse keeps episodes with missing or invalid runtimes`() {
        val runtimes = listOf(
            null,
            JsonNull,
            JsonPrimitive(""),
            JsonPrimitive(" "),
            JsonPrimitive("unknown"),
            JsonPrimitive(true),
            JsonObject(emptyMap()),
            JsonArray(emptyList()),
        )

        runtimes.forEach { runtime ->
            val video = parseEpisode(runtime)

            assertEquals("show:1:1", video.id)
            assertNull(video.runtime, "Runtime: $runtime")
        }
    }

    private fun parseEpisode(runtime: JsonElement?): MetaVideo {
        val payload = buildJsonObject {
            put("id", "show")
            put("type", "series")
            put("name", "Show")
            put("videos", buildJsonArray {
                add(buildJsonObject {
                    put("id", "show:1:1")
                    put("title", "Episode 1")
                    if (runtime != null) put("runtime", runtime)
                })
            })
        }
        return MetaDetailsParser.parse(payload.toString()).videos.single()
    }
}
