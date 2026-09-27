package com.nuvio.app.features.player

import com.nuvio.app.features.watchprogress.isLocalFileSourceUrl
import io.ktor.http.Url

/**
 * Whether seek-bar previews run for the current source. Metered always wins (previews are a second
 * download). In [DesktopSeekThumbnailMode.Local] only sources the user controls qualify: a file on
 * disk, or an http server on this PC / the home network (a NAS, Jellyfin, a local share). Torrents
 * never do, even though the engine serves them from 127.0.0.1 — every preview would pull pieces
 * from the swarm, competing with playback.
 */
internal fun seekThumbnailsAllowed(
    mode: DesktopSeekThumbnailMode,
    bufferPreset: DesktopBufferPreset,
    sourceUrl: String,
    isTorrent: Boolean,
): Boolean {
    if (bufferPreset == DesktopBufferPreset.Metered) return false
    return when (mode) {
        DesktopSeekThumbnailMode.Off -> false
        DesktopSeekThumbnailMode.Streaming -> true
        DesktopSeekThumbnailMode.Local -> !isTorrent && isUserControlledSource(sourceUrl)
    }
}

internal fun isUserControlledSource(sourceUrl: String): Boolean {
    val url = sourceUrl.trim()
    if (url.startsWith("torrent:", ignoreCase = true) || url.startsWith("magnet:", ignoreCase = true)) {
        return false
    }
    if (isLocalFileSourceUrl(url)) return true
    val host = runCatching { Url(url).host }.getOrNull()?.lowercase()?.trim('[', ']') ?: return false
    return isPrivateNetworkHost(host)
}

/** Loopback, RFC 1918 / link-local IPv4, IPv6 loopback / ULA / link-local, and mDNS/LAN names. */
internal fun isPrivateNetworkHost(host: String): Boolean {
    if (host == "localhost" || host.endsWith(".local") || host.endsWith(".lan") || host.endsWith(".home.arpa")) {
        return true
    }
    if (host == "::1" || host.startsWith("fc") || host.startsWith("fd") || host.startsWith("fe80:")) {
        return ':' in host
    }
    val octets = host.split('.').map { it.toIntOrNull() ?: return false }
    if (octets.size != 4 || octets.any { it !in 0..255 }) return false
    val (a, b) = octets
    return a == 127 || a == 10 || (a == 192 && b == 168) || (a == 172 && b in 16..31) || (a == 169 && b == 254)
}
