package com.nuvio.app.features.mdblist

import com.nuvio.app.features.details.MetaExternalRating
import com.nuvio.app.features.mdblist.MdbListMetadataService.PROVIDER_AUDIENCE
import com.nuvio.app.features.mdblist.MdbListMetadataService.PROVIDER_IMDB
import com.nuvio.app.features.mdblist.MdbListMetadataService.PROVIDER_TOMATOES
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RottenTomatoesRatingTest {
    @Test
    fun criticsScoreMapsToFreshRottenAndCertified() {
        assertEquals(RottenTomatoesStatus.ROTTEN, rating(PROVIDER_TOMATOES, 59.0).rottenTomatoesStatus)
        assertEquals(RottenTomatoesStatus.FRESH, rating(PROVIDER_TOMATOES, 60.0).rottenTomatoesStatus)
        assertEquals(RottenTomatoesStatus.CERTIFIED_FRESH, rating(PROVIDER_TOMATOES, 70.0, true).rottenTomatoesStatus)
        // Certification never lifts a score below the threshold into the certified badge.
        assertEquals(RottenTomatoesStatus.FRESH, rating(PROVIDER_TOMATOES, 65.0, true).rottenTomatoesStatus)
    }

    @Test
    fun audienceScoreMapsToHotStaleAndVerified() {
        assertEquals(RottenTomatoesStatus.STALE, rating(PROVIDER_AUDIENCE, 59.0).rottenTomatoesStatus)
        assertEquals(RottenTomatoesStatus.HOT, rating(PROVIDER_AUDIENCE, 60.0).rottenTomatoesStatus)
        assertEquals(RottenTomatoesStatus.VERIFIED_HOT, rating(PROVIDER_AUDIENCE, 80.0, true).rottenTomatoesStatus)
    }

    @Test
    fun otherProvidersHaveNoStatus() {
        assertNull(rating(PROVIDER_IMDB, 90.0).rottenTomatoesStatus)
    }

    @Test
    fun keywordsCertifyTheMatchingSourceOnly() {
        val ratings = listOf(
            rating(PROVIDER_TOMATOES, 94.0),
            rating(PROVIDER_AUDIENCE, 91.0),
            rating(PROVIDER_IMDB, 8.1),
        ).withRottenTomatoesCertification(listOf("certified-fresh", "time-travel"))

        assertTrue(ratings.single { it.source == PROVIDER_TOMATOES }.isCertified)
        assertFalse(ratings.single { it.source == PROVIDER_AUDIENCE }.isCertified)
        assertFalse(ratings.single { it.source == PROVIDER_IMDB }.isCertified)
    }

    @Test
    fun namespacedKeywordsAreAccepted() {
        val ratings = listOf(rating(PROVIDER_AUDIENCE, 91.0))
            .withRottenTomatoesCertification(listOf("mdblist.certified-hot"))

        assertEquals(RottenTomatoesStatus.VERIFIED_HOT, ratings.single().rottenTomatoesStatus)
    }

    @Test
    fun unrelatedKeywordsLeaveRatingsUncertified() {
        val ratings = listOf(rating(PROVIDER_TOMATOES, 90.0))
            .withRottenTomatoesCertification(listOf("fresh"))

        assertFalse(ratings.single().isCertified)
    }

    private fun rating(source: String, value: Double, certified: Boolean = false) =
        MetaExternalRating(source = source, value = value, isCertified = certified)
}
