package com.nuvio.app.features.player.skip

/**
 * [SkipInterval.type] of a post-credits scene. It is a landing point, not something to skip: it
 * never raises a prompt of its own, it only moves where skipping the credits before it lands.
 */
internal const val POST_CREDITS_SKIP_TYPE = "post-credits"

/**
 * A film running on this long past its credits is taken to hold a scene there. Wider than the 15s
 * a SkipDB `shifted` match may be off by, so a slightly longer cut is not mistaken for one.
 */
internal const val POST_CREDITS_TAIL_MS = 20_000L

internal fun SkipInterval.isPostCreditsScene(): Boolean =
    type.equals(POST_CREDITS_SKIP_TYPE, ignoreCase = true)

/** Where accepting a skip prompt seeks to, and whether that is a post-credits scene. */
internal data class SkipLanding(
    val targetMs: Long,
    val landsOnPostCredits: Boolean,
)

/**
 * Accepting a credits/outro prompt lands on the first known post-credits scene after it instead of
 * the credits' end, so a film's stinger is never skipped along with the crawl.
 *
 * Without a known scene a film still reports [SkipLanding.landsOnPostCredits] when the credits end
 * more than [POST_CREDITS_TAIL_MS] before the file does: the timings say the crawl is over but the
 * film is not, and that is where the scene sits. Episodes do not get that guess — the tail of an
 * episode is usually the next-episode preview, and calling it a post-credits scene would be wrong.
 */
internal fun SkipInterval.skipLanding(
    intervals: List<SkipInterval>,
    durationMs: Long,
    isMovie: Boolean,
): SkipLanding {
    val endMs = (endTime * 1000.0).toLong()
    if (!isOutroKind()) return SkipLanding(targetMs = endMs, landsOnPostCredits = false)
    val scene = intervals
        .filter { candidate ->
            candidate.isPostCreditsScene() &&
                candidate.startTime >= endTime &&
                (durationMs <= 0L || candidate.startTime * 1000.0 < durationMs)
        }
        .minByOrNull(SkipInterval::startTime)
    if (scene != null) {
        return SkipLanding(targetMs = (scene.startTime * 1000.0).toLong(), landsOnPostCredits = true)
    }
    val hasTail = isMovie && durationMs > 0L && durationMs - endMs > POST_CREDITS_TAIL_MS
    return SkipLanding(targetMs = endMs, landsOnPostCredits = hasTail)
}

internal fun SkipInterval.isOutroKind(): Boolean =
    type.lowercase() in setOf("outro", "ed", "mixed-ed", "credits")
