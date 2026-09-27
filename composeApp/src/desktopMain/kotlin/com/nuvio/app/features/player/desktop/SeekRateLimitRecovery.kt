package com.nuvio.app.features.player.desktop

import com.nuvio.app.features.player.DesktopRateLimitRecoveryMode
import com.nuvio.app.features.player.PlaybackSourceFailure
import com.nuvio.app.features.player.playbackErrorFailure

/**
 * Seek-bar previews are a second stream opening ranges against the same host as the main player.
 * Once that host has answered 429, every preview is one more open at a host that is already
 * refusing, so previews stand down for a while. Time-bounded and process-wide — never a lasting
 * mark against a host or a link.
 */
internal object SeekThumbnailRateLimitGate {
    private const val SUPPRESS_MS = 5L * 60L * 1000L

    @Volatile
    private var suppressedUntilMs = 0L

    fun onRateLimited(nowMs: Long = System.currentTimeMillis()) {
        suppressedUntilMs = nowMs + SUPPRESS_MS
    }

    fun isSuppressed(nowMs: Long = System.currentTimeMillis()): Boolean = nowMs < suppressedUntilMs
}

/**
 * Policy for a 429 that lands on a stream which was already playing — almost always a seek outside
 * the demuxer cache, whose range open the host throttled. Depending on
 * [DesktopRateLimitRecoveryMode] the player either leaves it to the common layer (failover, or
 * exit) or reconnects: a TorBox link is moved to another CDN node at once (see [TorBoxNodeHop]),
 * anything else is reopened after the user's configured waits.
 *
 * Deliberately narrow, because the reverted `reconnect_on_http_error=429` (2026-07-25) showed what
 * blind 429 retries do: they happen invisibly inside startup and failover walks and deepen the
 * throttle. This only runs for a surface that has rendered frames of this attempt, a bounded number
 * of times, and never on the initial open — that stays with the common layer's provider-scoped
 * failover.
 */
internal object SeekRateLimitRecovery {
    /** Reconnects per incident; each TorBox hop or timed reopen spends one. */
    const val MAX_ATTEMPTS: Int = 2

    /**
     * A 429 within this long of the previous recovery belongs to the same incident and spends
     * what is left of its budget; a later one starts a fresh budget.
     */
    const val EPISODE_WINDOW_MS: Long = 2L * 60L * 1000L

    /** A resume target this close to the end is treated as bogus rather than obeyed. */
    const val END_GUARD_MS: Long = 30_000L

    /** Whether this incident is the player's to handle, or goes straight to failover / exit. */
    fun shouldReconnect(mode: DesktopRateLimitRecoveryMode, streamFailoverEnabled: Boolean): Boolean =
        when (mode) {
            DesktopRateLimitRecoveryMode.Off -> false
            DesktopRateLimitRecoveryMode.PreferFailover -> !streamFailoverEnabled
            DesktopRateLimitRecoveryMode.PreferReconnect -> true
        }

    /**
     * Where a reopen resumes. The seek target when there is a plausible one, else the last real
     * playhead. Never within [END_GUARD_MS] of the end: a reopen there plays out immediately,
     * and an ended file is taken as watched and scrobbled — far worse than landing a little
     * behind where the user was aiming. Null when neither is usable (genuinely in the last
     * seconds of the file): then there is nothing worth reopening and the error goes on to the
     * common layer as before.
     */
    fun safeResumeMs(seekTargetMs: Long?, lastKnownPositionMs: Long, durationMs: Long): Long? {
        fun plausible(positionMs: Long): Boolean =
            positionMs >= 0L && (durationMs <= 0L || positionMs < durationMs - END_GUARD_MS)
        return when {
            seekTargetMs != null && plausible(seekTargetMs) -> seekTargetMs
            plausible(lastKnownPositionMs) -> lastKnownPositionMs
            else -> null
        }
    }

    fun isRateLimited(message: String?): Boolean =
        message != null && playbackErrorFailure(message) == PlaybackSourceFailure.DebridRateLimited

    /**
     * Whether a reopen of [resolution] can be trusted without asking the resolver again. A pinned
     * URL is the CDN link itself and a skipped one is a known direct-media host: neither can
     * answer with a provider's status clip. Anything that goes through a resolver or proxy
     * (AIOStreams' playback endpoint, its stream proxy) can — when its whole failover chain fails
     * it 3xx's onto `/static/429.mp4` — so it is re-probed first.
     */
    fun canReopenWithoutProbe(resolution: PlaybackRedirectResolution): Boolean =
        resolution.pinned || resolution.outcome == PlaybackRedirectResolution.Outcome.Skipped

    /**
     * Whether a fresh probe result is safe to reopen. A placeholder means the resolver itself is
     * now failing (its chain hit the limit): opening it would play the status clip from the resume
     * position, which ends immediately and would look like the title finished. A failed probe is
     * worth another round, not a reopen.
     */
    fun isReopenable(resolution: PlaybackRedirectResolution): Boolean =
        when (resolution.outcome) {
            PlaybackRedirectResolution.Outcome.Pinned,
            PlaybackRedirectResolution.Outcome.NoRedirect,
            PlaybackRedirectResolution.Outcome.CacheableRedirect,
            PlaybackRedirectResolution.Outcome.Skipped,
            -> true
            PlaybackRedirectResolution.Outcome.Placeholder,
            PlaybackRedirectResolution.Outcome.Failed,
            PlaybackRedirectResolution.Outcome.Remembered,
            -> false
        }
}
