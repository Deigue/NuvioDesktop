package com.nuvio.app.features.details

/** Which episode cards show their IMDb score on the details page. */
enum class EpisodeRatingsVisibility {
    SHOW_ALL,
    HIDE_EPISODES,

    /** A score can give away how an episode lands, so it waits until the episode is watched. */
    HIDE_UNWATCHED_EPISODES;

    /** Whether the ratings are worth fetching at all. */
    val showRatings: Boolean
        get() = this != HIDE_EPISODES

    fun showRating(isWatched: Boolean): Boolean = when (this) {
        SHOW_ALL -> true
        HIDE_EPISODES -> false
        HIDE_UNWATCHED_EPISODES -> isWatched
    }

    companion object {
        fun parse(value: String): EpisodeRatingsVisibility =
            entries.firstOrNull { it.name == value } ?: SHOW_ALL
    }
}
