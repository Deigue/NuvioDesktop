package com.nuvio.app.features.discord

/**
 * Whether Discord can actually fetch this artwork.
 *
 * Rich Presence artwork is not uploaded — Discord is handed a URL and fetches it from its own
 * servers, as does the resizing proxy in front of it. So an address that only resolves on the
 * user's machine or LAN is unusable here even though every image in the app loads from it
 * perfectly. That is the whole difference between a stock poster and a custom poster service: the
 * stock one is a public CDN URL, while a self-hosted PostersPlus lives at something like
 * `http://postersplus:8000` or `http://192.168.1.50:8000`, which the proxy answers with
 * "hostname unresolvable" or "IP address blocked by policy".
 *
 * Rejecting those here lets the caller fall through to the next artwork it has rather than
 * publishing a URL that can only ever resolve to an error, which is what made Rich Presence look
 * broken instead of merely un-postered.
 */
internal fun isExternallyFetchableArtworkUrl(url: String?): Boolean {
    val trimmed = url?.trim().orEmpty()
    if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) return false
    val host = artworkUrlHost(trimmed) ?: return false
    return !host.isPrivateArtworkHost()
}

/**
 * Discord rejects the whole activity (RPC error 4000) when `assets.large_image` is longer than
 * this, so an over-long URL is not merely un-rendered — it costs the entire presence update.
 */
internal const val DISCORD_ASSET_URL_LIMIT = 300

/** Whether Discord will accept this string as an asset key / external image URL at all. */
internal fun fitsDiscordAssetLimit(url: String?): Boolean =
    url != null && url.length <= DISCORD_ASSET_URL_LIMIT

/**
 * Squares a portrait poster without cropping it, via the images.weserv.nl resizing proxy.
 *
 * No `default=` fallback on purpose: the proxy serves that as a bare redirect to the raw fallback,
 * un-letterboxed and indistinguishable from the primary having worked. The caller walks the
 * candidates itself instead.
 *
 * The source URL is escaped only as far as it has to be to survive as one query value. Full
 * form-encoding turns every `/`, `:`, `?` and `=` into three characters, which pushed ordinary
 * poster-service URLs past [DISCORD_ASSET_URL_LIMIT] and got the presence rejected.
 */
internal fun fittedDiscordImageUrl(sourceUrl: String): String = buildString {
    append("https://images.weserv.nl/?url=")
    append(encodeArtworkQueryValue(sourceUrl))
    append("&w=512&h=512&fit=contain&bg=transparent")
}

/**
 * Percent-encodes [value] for use as a single query parameter value, leaving every character that
 * RFC 3986 permits literally in a query untouched except the ones that would split or reinterpret
 * it: `&` (parameter separator), `#` (fragment), `+` (form-decoded as a space) and `%` itself, so
 * escapes already present in the source survive the proxy's decode intact.
 */
internal fun encodeArtworkQueryValue(value: String): String = buildString {
    value.encodeToByteArray().forEach { byte ->
        val code = byte.toInt() and 0xFF
        val char = code.toChar()
        if (code < 0x80 && char in ARTWORK_QUERY_LITERAL_CHARS) {
            append(char)
        } else {
            append('%')
            append(HEX_DIGITS[code shr 4])
            append(HEX_DIGITS[code and 0x0F])
        }
    }
}

private const val HEX_DIGITS = "0123456789ABCDEF"

private val ARTWORK_QUERY_LITERAL_CHARS: Set<Char> = buildSet {
    addAll('A'..'Z')
    addAll('a'..'z')
    addAll('0'..'9')
    addAll("-._~".toList())
    // RFC 3986 sub-delims minus '&' and '+', plus the ':' '@' '/' '?' a query may carry literally.
    addAll("!$'()*,;=:@/?".toList())
}

/** The host of an absolute http(s) URL, lowercased, with any userinfo and port removed. */
private fun artworkUrlHost(url: String): String? {
    val authority = url.substringAfter("://", missingDelimiterValue = "")
        .substringBefore('/')
        .substringBefore('?')
        .substringBefore('#')
        .substringAfterLast('@')
    if (authority.isEmpty()) return null
    val host = if (authority.startsWith("[")) {
        authority.substringAfter('[').substringBefore(']')
    } else {
        authority.substringBefore(':')
    }
    return host.lowercase().takeIf { it.isNotEmpty() }
}

private fun String.isPrivateArtworkHost(): Boolean {
    if (this == "localhost" || endsWith(".localhost")) return true
    // Suffixes that only mean anything inside one network.
    if (endsWith(".local") || endsWith(".internal") || endsWith(".lan") || endsWith(".home")) return true

    if (contains(':')) {
        // IPv6 loopback, unique-local (fc00::/7) and link-local (fe80::/10).
        return this == "::1" || startsWith("fc") || startsWith("fd") || startsWith("fe8") ||
            startsWith("fe9") || startsWith("fea") || startsWith("feb")
    }

    val octets = split('.')
    if (octets.size == 4 && octets.all { (it.toIntOrNull() ?: -1) in 0..255 }) {
        val first = octets[0].toInt()
        val second = octets[1].toInt()
        return first == 0 || first == 10 || first == 127 ||
            (first == 169 && second == 254) ||
            (first == 172 && second in 16..31) ||
            (first == 192 && second == 168)
    }

    // A single-label hostname ("postersplus", "nas") resolves only on the user's own network.
    return !contains('.')
}
