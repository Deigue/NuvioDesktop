package com.nuvio.app.features.player.desktop

import co.touchlab.kermit.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * TorBox's CDN rate-limits per node, not per account or per link (measured 2026-09-26): after a
 * burst of range opens, `nexus-133.neur.tb-cdn.st` refused *every* file for our IP for ~75 minutes
 * with a bare nginx 429 — no `Retry-After` — while the very same `/dld/<id>?token=` path answered
 * 206 at once through `nexus-130` and `nexus-272`. Re-resolving does not help either: the addon
 * (StremThru, AIOStreams) hands back its cached link on the same node.
 *
 * So the useful "reconnect" for a TorBox link is the same URL on another node. This is not a
 * documented TorBox feature — it is how their nodes behave today — so every hop is verified with a
 * one-byte probe before mpv is pointed at it, and when nothing answers the caller falls back to
 * failover. Nothing here outlives [BAN_MS]: a node is never written off for good.
 */
internal object TorBoxNodeHop {
    private val log = Logger.withTag("TorBoxNodeHop")

    private val NODE_HOST = Regex("""^nexus-(\d+)\.([a-z0-9-]+)\.tb-cdn\.st$""", RegexOption.IGNORE_CASE)

    /** Comfortably past the ~75 min ban observed; a node older than this is trusted again. */
    const val BAN_MS: Long = 90L * 60L * 1000L

    /** Probes per hop. Node numbers are sparse (nexus-134 did not resolve), so allow a few misses. */
    const val MAX_PROBES = 8

    /** Numbers either side of the banned node to try once no known-good node is left. */
    private const val NEIGHBOUR_SPAN = 6

    private val PROBE_TIMEOUT: Duration = Duration.ofMillis(2500)

    private val bannedUntilMs = ConcurrentHashMap<String, Long>()
    private val workingSeenAtMs = ConcurrentHashMap<String, Long>()

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(PROBE_TIMEOUT)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    internal data class Node(val number: Int, val region: String)

    internal fun hostOf(url: String): String =
        runCatching { URI(url).host.orEmpty() }.getOrDefault("").lowercase(Locale.ROOT)

    internal fun nodeOf(url: String): Node? =
        NODE_HOST.matchEntire(hostOf(url))?.let { match ->
            Node(match.groupValues[1].toInt(), match.groupValues[2].lowercase(Locale.ROOT))
        }

    fun isTorBoxNode(url: String): Boolean = nodeOf(url) != null

    fun markBanned(url: String, nowMs: Long = System.currentTimeMillis()) {
        if (!isTorBoxNode(url)) return
        val host = hostOf(url)
        bannedUntilMs[host] = nowMs + BAN_MS
        workingSeenAtMs.remove(host)
        log.i { "TorBox node $host rate-limited us; avoiding it for ${BAN_MS / 60_000} min" }
    }

    fun markWorking(url: String, nowMs: Long = System.currentTimeMillis()) {
        if (!isTorBoxNode(url) || isBanned(url, nowMs)) return
        workingSeenAtMs[hostOf(url)] = nowMs
    }

    fun isBanned(url: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val host = hostOf(url)
        val until = bannedUntilMs[host] ?: return false
        if (nowMs < until) return true
        bannedUntilMs.remove(host, until)
        return false
    }

    /** [url] with only its host swapped; path and token stay byte-identical. */
    internal fun withHost(url: String, host: String): String {
        val uri = URI(url)
        val authority = uri.rawAuthority ?: return url
        val replacement = if (uri.port >= 0) "$host:${uri.port}" else host
        return url.replaceFirst(authority, replacement)
    }

    /**
     * Hosts to try instead of [url]'s node, best first: nodes in the same region that recently
     * served us, then the banned node's numeric neighbours. Never the current node, never a node
     * inside its ban window.
     */
    internal fun candidates(url: String, nowMs: Long = System.currentTimeMillis()): List<String> {
        val node = nodeOf(url) ?: return emptyList()
        val current = hostOf(url)
        fun usable(host: String) = host != current && !isBanned("https://$host/", nowMs)
        val known = workingSeenAtMs.entries
            .filter { (host, _) -> nodeOf("https://$host/")?.region == node.region }
            .sortedByDescending { it.value }
            .map { it.key }
            .filter(::usable)
        val neighbours = (1..NEIGHBOUR_SPAN)
            .flatMap { offset -> listOf(node.number - offset, node.number + offset) }
            .filter { it > 0 }
            .map { "nexus-$it.${node.region}.tb-cdn.st" }
            .filter(::usable)
        return (known + neighbours).distinct().take(MAX_PROBES)
    }

    /**
     * The same link on another node that answers a one-byte range request with 206, or null.
     * A candidate answering 429 is itself recorded as banned.
     */
    suspend fun findAlternative(url: String, userAgent: String): String? = withContext(Dispatchers.IO) {
        for (host in candidates(url)) {
            val candidate = runCatching { withHost(url, host) }.getOrNull() ?: continue
            val status = runCatching { probe(candidate, userAgent) }.getOrElse { error ->
                log.d { "probe $host failed: ${error.message}" }
                null
            }
            log.i { "hop candidate $host -> ${status ?: "no answer"}" }
            when (status) {
                206 -> {
                    markWorking(candidate)
                    return@withContext candidate
                }
                429 -> markBanned(candidate)
            }
        }
        null
    }

    private fun probe(url: String, userAgent: String): Int {
        val request = HttpRequest.newBuilder()
            .uri(URI(url))
            .timeout(PROBE_TIMEOUT)
            .header("Range", "bytes=0-0")
            .header("User-Agent", userAgent)
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        runCatching { response.body().close() }
        return response.statusCode()
    }

    /** Test hook: forget every ban and sighting. */
    internal fun resetForTest() {
        bannedUntilMs.clear()
        workingSeenAtMs.clear()
    }
}
