package com.nuvio.app.features.mdblist

import com.nuvio.app.features.details.MetaExternalRating
import com.nuvio.app.features.mdblist.MdbListMetadataService.PROVIDER_AUDIENCE
import com.nuvio.app.features.mdblist.MdbListMetadataService.PROVIDER_TOMATOES

/** The badge Rotten Tomatoes itself would show next to a score. */
internal enum class RottenTomatoesStatus {
    FRESH,
    ROTTEN,
    CERTIFIED_FRESH,
    HOT,
    STALE,
    VERIFIED_HOT,
}

internal val MetaExternalRating.rottenTomatoesStatus: RottenTomatoesStatus?
    get() = when (source) {
        PROVIDER_TOMATOES -> when {
            isCertified && value >= 70 -> RottenTomatoesStatus.CERTIFIED_FRESH
            value >= 60 -> RottenTomatoesStatus.FRESH
            else -> RottenTomatoesStatus.ROTTEN
        }
        PROVIDER_AUDIENCE -> when {
            isCertified && value >= 80 -> RottenTomatoesStatus.VERIFIED_HOT
            value >= 60 -> RottenTomatoesStatus.HOT
            else -> RottenTomatoesStatus.STALE
        }
        else -> null
    }

/**
 * Marks the Tomatometer / Popcornmeter ratings certified from the MDBList keywords the same response
 * already carries (`certified-fresh`, `certified-hot`, sometimes namespaced like `rt.certified-fresh`).
 * Derived rather than stored, so ratings cached before this existed pick it up without a refetch.
 */
internal fun List<MetaExternalRating>.withRottenTomatoesCertification(
    keywords: Collection<String>,
): List<MetaExternalRating> {
    if (none { it.source == PROVIDER_TOMATOES || it.source == PROVIDER_AUDIENCE }) return this
    val names = keywords.mapTo(mutableSetOf()) { it.trim().lowercase().substringAfterLast('.') }
    val criticsCertified = "certified-fresh" in names
    val audienceCertified = "certified-hot" in names || "verified-hot" in names
    return map { rating ->
        when (rating.source) {
            PROVIDER_TOMATOES -> rating.copy(isCertified = criticsCertified)
            PROVIDER_AUDIENCE -> rating.copy(isCertified = audienceCertified)
            else -> rating
        }
    }
}
