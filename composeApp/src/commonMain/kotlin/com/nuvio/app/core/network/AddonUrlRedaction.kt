package com.nuvio.app.core.network

/**
 * Stremio addon URLs carry their configuration in the path: AIOStreams puts an instance UUID and
 * an encrypted config blob there, Torrentio-style addons put `realdebrid=<key>`. Anyone holding the
 * URL can use the user's instance and the debrid account behind it, and users are asked to send
 * their logs in. So a log line keeps the scheme, host and the Stremio resource part
 * (`manifest.json`, `stream/movie/tt123.json`, …) and replaces everything in between with `…`.
 *
 * `https://aio.example/stremio/<uuid>/<config>/stream/movie/tt1.json?x=1`
 * → `https://aio.example/…/stream/movie/tt1.json`
 */
fun redactAddonUrl(url: String): String {
    val trimmed = url.trim()
    val schemeEnd = trimmed.indexOf("://")
    if (schemeEnd <= 0) return "<redacted>"
    val scheme = trimmed.substring(0, schemeEnd)
    val rest = trimmed.substring(schemeEnd + 3).substringBefore('?').substringBefore('#')
    val authority = rest.substringBefore('/')
    val host = authority.substringAfterLast('@')
    val segments = rest.substringAfter('/', missingDelimiterValue = "")
        .split('/')
        .filter { it.isNotEmpty() }
    if (segments.isEmpty()) return "$scheme://$host"
    val resourceStart = segments.indexOfFirst { segment ->
        segment.equals("manifest.json", ignoreCase = true) ||
            segment.lowercase() in StremioResourceSegments
    }
    val kept = if (resourceStart >= 0) segments.drop(resourceStart) else listOf(segments.last())
    val elided = segments.size > kept.size
    return buildString {
        append(scheme).append("://").append(host)
        if (elided) append("/…")
        kept.forEach { append('/').append(it) }
    }
}

/**
 * Addon ids are built as `addon:<manifestId>:<manifestUrl>` (see `StreamFetchSupport`); plugin
 * scraper ids as `<repoUrl>:<scraperId>`. Redacts any URL inside, leaving the readable parts.
 */
fun redactAddonId(id: String): String {
    val schemeSeparator = id.indexOf("://")
    if (schemeSeparator <= 0) return id
    var urlStart = schemeSeparator
    while (urlStart > 0 && (id[urlStart - 1].isLetterOrDigit() || id[urlStart - 1] in "+.-")) urlStart--
    // A plugin id continues after the URL with `:<scraperId>`; a URL has no ':' after its path
    // starts, so the first one past the authority ends it.
    val pathStart = id.indexOf('/', schemeSeparator + 3).takeIf { it >= 0 } ?: id.length
    val urlEnd = id.indexOf(':', pathStart).takeIf { it >= 0 } ?: id.length
    return id.substring(0, urlStart) + redactAddonUrl(id.substring(urlStart, urlEnd)) + id.substring(urlEnd)
}

private val StremioResourceSegments = setOf("catalog", "meta", "stream", "subtitles", "addon_catalog")
