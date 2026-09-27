package com.nuvio.app.features.player.skip

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PostCreditsSkipTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun creditsLandOnTheSceneAfterThem() {
        val credits = interval(6000.0, 6300.0, "outro")
        val scene = interval(6300.0, 6360.0, POST_CREDITS_SKIP_TYPE)

        val landing = credits.skipLanding(listOf(credits, scene), durationMs = 6_400_000L, isMovie = true)

        assertEquals(6_300_000L, landing.targetMs)
        assertTrue(landing.landsOnPostCredits)
    }

    @Test
    fun aSceneBeforeTheCreditsIsNotALandingPoint() {
        // The Avengers on IntroDB: a mid-credits scene ends where the main crawl begins.
        val scene = interval(8107.0, 8155.0, POST_CREDITS_SKIP_TYPE)
        val credits = interval(8155.0, 8574.0, "outro")

        val landing = credits.skipLanding(listOf(scene, credits), durationMs = 8_575_000L, isMovie = true)

        assertEquals(8_574_000L, landing.targetMs)
        assertFalse(landing.landsOnPostCredits)
    }

    @Test
    fun aLongFilmTailCountsAsAScene() {
        val credits = interval(6000.0, 6300.0, "outro")

        val landing = credits.skipLanding(listOf(credits), durationMs = 6_400_000L, isMovie = true)

        assertEquals(6_300_000L, landing.targetMs)
        assertTrue(landing.landsOnPostCredits)
    }

    @Test
    fun aShortTailOrAnEpisodeTailIsNot() {
        val credits = interval(6000.0, 6300.0, "outro")

        assertFalse(credits.skipLanding(listOf(credits), 6_310_000L, isMovie = true).landsOnPostCredits)
        assertFalse(credits.skipLanding(listOf(credits), 6_400_000L, isMovie = false).landsOnPostCredits)
    }

    @Test
    fun introsAlwaysLandOnTheirEnd() {
        val intro = interval(60.0, 120.0, "intro")
        val scene = interval(130.0, 200.0, POST_CREDITS_SKIP_TYPE)

        val landing = intro.skipLanding(listOf(intro, scene), durationMs = 3_000_000L, isMovie = true)

        assertEquals(120_000L, landing.targetMs)
        assertFalse(landing.landsOnPostCredits)
    }

    @Test
    fun introDbEpisodeResponseYieldsEveryKind() {
        val response = json.decodeFromString<IntroDbSegmentsResponse>(
            """{"imdb_id":"tt0944947","media_type":"tv","is_movie":false,"season":1,"episode":1,
               "intro":{"start_sec":437,"end_sec":531,"start_ms":437000,"end_ms":531000},
               "recap":null,
               "outro":{"start_sec":3631.5,"end_sec":3699.5},
               "post_credits":null}""",
        )

        val intervals = response.toEpisodeSkipIntervals()

        assertEquals(listOf("intro", "outro"), intervals.map { it.type })
        assertEquals(3631.5, intervals.last().startTime)
    }

    @Test
    fun introDbMovieCreditsStopWhereAnEmbeddedSceneStarts() {
        val response = json.decodeFromString<IntroDbSegmentsResponse>(
            """{"outro":{"start_ms":6000000,"end_ms":6400000},
               "post_credits":{"start_ms":6200000,"end_ms":6260000}}""",
        )

        val intervals = response.toMovieSkipIntervals()

        assertEquals(interval(6000.0, 6200.0, "outro", INTRODB_PROVIDER), intervals[0])
        assertEquals(POST_CREDITS_SKIP_TYPE, intervals[1].type)
    }

    @Test
    fun unknownIntroDbTitleYieldsNothing() {
        val response = json.decodeFromString<IntroDbSegmentsResponse>(
            """{"imdb_id":"tt4154796","is_movie":true,"intro":null,"recap":null,"outro":null,"post_credits":null}""",
        )

        assertTrue(response.toMovieSkipIntervals().isEmpty())
    }

    @Test
    fun skipDbCreditsWinButIntroDbScenesAreKept() {
        val skipDbCredits = interval(6010.0, 6300.0, "outro", SKIPDB_PROVIDER)
        val introDb = listOf(
            interval(6000.0, 6290.0, "outro", INTRODB_PROVIDER),
            interval(6305.0, 6360.0, POST_CREDITS_SKIP_TYPE, INTRODB_PROVIDER),
        )

        val merged = mergeMovieSkipIntervals(listOf(skipDbCredits), introDb)

        assertEquals(listOf(skipDbCredits, introDb[1]), merged)
    }

    @Test
    fun aSceneInsideTheChosenCreditsIsDropped() {
        val skipDbCredits = interval(6000.0, 6400.0, "outro", SKIPDB_PROVIDER)
        val scene = interval(6200.0, 6260.0, POST_CREDITS_SKIP_TYPE, INTRODB_PROVIDER)

        assertEquals(listOf(skipDbCredits), mergeMovieSkipIntervals(listOf(skipDbCredits), listOf(scene)))
    }

    @Test
    fun introDbFillsKindsSkipDbLacks() {
        val skipDbIntro = interval(30.0, 90.0, "intro", SKIPDB_PROVIDER)
        val introDbCredits = interval(6000.0, 6300.0, "outro", INTRODB_PROVIDER)

        assertEquals(
            listOf(skipDbIntro, introDbCredits),
            mergeMovieSkipIntervals(listOf(skipDbIntro), listOf(introDbCredits)),
        )
    }

    private fun interval(start: Double, end: Double, type: String, provider: String = "test") =
        SkipInterval(startTime = start, endTime = end, type = type, provider = provider)
}
