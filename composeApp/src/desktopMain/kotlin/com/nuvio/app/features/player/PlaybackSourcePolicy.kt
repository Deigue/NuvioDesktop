package com.nuvio.app.features.player

import com.nuvio.app.features.locallibrary.LocalLibraryRepository
import java.net.URI
import java.net.URLDecoder
import java.util.Locale

/**
 * The one gate every player path runs a source through before handing it to anything that can
 * interpret it. Stream URLs come from installed addons and from plugin scrapers — arbitrary
 * third-party JavaScript — and the only earlier filter was "not blank, not a magnet".
 *
 * What it stops:
 * - **Option injection.** An external player's argv ends with the URL, so `--script=…` (mpv runs
 *   Lua), `--extraintf=…` (VLC) or `/dub …` (MPC, PotPlayer) would be parsed as a switch.
 * - **Launching files.** The system-handler fallback uses ShellExecute, which *runs* an
 *   `.exe`/`.lnk`/`.bat`, and `rundll32 url.dll,FileProtocolHandler` does the same for any path.
 * - **Credential leaks.** Opening `\\host\share\x.mkv` or `file://host/share/x.mkv` makes Windows
 *   authenticate to that host over SMB (or WebDAV), handing it the user's NetNTLMv2 hash.
 *
 * Local drive paths stay allowed: downloads, the local library and dropped files are played that
 * way, and a local path given to a media player is only ever opened as media. Network shares are
 * allowed only under a folder the user added to the local library (a NAS library).
 */
internal object PlaybackSourcePolicy {
    sealed interface Verdict {
        data object Allowed : Verdict
        data class Rejected(val reason: String) : Verdict
    }

    /** Network streaming schemes a media player may be handed. Everything else is refused. */
    private val streamingSchemes = setOf(
        "http", "https",
        "rtmp", "rtmps", "rtmpe", "rtmpt",
        "rtsp", "rtsps", "rtp", "srt", "udp",
        "mms", "mmsh", "mmst",
    )

    private val localMediaExtensions = setOf(
        "mkv", "mp4", "m4v", "avi", "mov", "wmv", "webm", "ts", "m2ts", "mts", "mpg", "mpeg",
        "flv", "ogv", "3gp", "mp3", "flac", "m4a", "aac", "ogg", "opus", "wav",
    )

    private val driveLetterPath = Regex("""^[A-Za-z]:[\\/]""")

    fun isLocalDrivePath(source: String): Boolean = driveLetterPath.containsMatchIn(source.trim())

    /** Full check, for anything that leaves the process: external players and the OS handler. */
    fun check(rawSource: String, trustedShareRoots: List<String> = localLibraryRoots()): Verdict {
        val source = rawSource.trim()
        if (source.isEmpty()) return Verdict.Rejected("blank")
        if (source.first() == '-') return Verdict.Rejected("looks like a command-line option")
        if (isNetworkShare(source)) {
            return if (isUnderTrustedRoot(source, trustedShareRoots)) {
                Verdict.Allowed
            } else {
                Verdict.Rejected("network share path")
            }
        }
        if (source.first() == '/') return Verdict.Rejected("looks like a command-line switch")
        if (isLocalDrivePath(source)) return Verdict.Allowed
        val uri = runCatching { URI(source) }.getOrNull() ?: return Verdict.Rejected("unparseable")
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return Verdict.Rejected("no scheme")
        return when {
            scheme in streamingSchemes -> Verdict.Allowed
            scheme == "file" -> Verdict.Allowed // isNetworkShare already handled file://host/
            else -> Verdict.Rejected("scheme $scheme")
        }
    }

    /**
     * The in-process mpv takes the source through `loadfile`, not argv, so option syntax is inert
     * there; only network-share paths (the NTLM leak) are refused. Its own protocols (`ytdl://`,
     * `edl://` and so on) keep working.
     */
    fun checkInProcess(rawSource: String, trustedShareRoots: List<String> = localLibraryRoots()): Verdict {
        val source = rawSource.trim()
        if (!isNetworkShare(source) || isUnderTrustedRoot(source, trustedShareRoots)) return Verdict.Allowed
        return Verdict.Rejected("network share path")
    }

    /**
     * Whether the OS handler (ShellExecute, rundll32) may be given this source: a web stream, or a
     * local file whose extension makes Windows open it in a media app rather than run it.
     */
    fun allowsSystemHandler(rawSource: String, trustedShareRoots: List<String> = localLibraryRoots()): Boolean {
        if (check(rawSource, trustedShareRoots) != Verdict.Allowed) return false
        val source = rawSource.trim()
        val isFile = isLocalDrivePath(source) || isNetworkShare(source) ||
            source.startsWith("file:", ignoreCase = true)
        if (isFile) {
            val extension = source.replace('\\', '/')
                .substringAfterLast('/')
                .substringAfterLast('.', missingDelimiterValue = "")
                .lowercase(Locale.ROOT)
            return extension in localMediaExtensions
        }
        val scheme = runCatching { URI(source).scheme }.getOrNull()?.lowercase(Locale.ROOT)
        return scheme == "http" || scheme == "https"
    }

    private fun localLibraryRoots(): List<String> =
        runCatching { LocalLibraryRepository.uiState.value.folders.map { it.path } }
            .getOrDefault(emptyList())

    /** `\\host\…`, `//host/…`, `file://host/…` and `file:////host/…`; not `file:///C:/…`. */
    private fun isNetworkShare(source: String): Boolean = uncPathOf(source) != null

    private fun isUnderTrustedRoot(source: String, roots: List<String>): Boolean {
        val path = uncPathOf(source)?.lowercase(Locale.ROOT) ?: return false
        return roots.any { root ->
            val normalizedRoot = uncPathOf(root.trim())?.lowercase(Locale.ROOT)?.trimEnd('/')
                ?: return@any false
            path == normalizedRoot || path.startsWith("$normalizedRoot/")
        }
    }

    /** The `host/share/…` part of a network-share source with forward slashes, or null. */
    private fun uncPathOf(source: String): String? {
        val slashed = source.replace('\\', '/')
        val tail = when {
            slashed.startsWith("file:////", ignoreCase = true) -> slashed.substring("file:////".length)
            slashed.startsWith("file:///", ignoreCase = true) -> return null
            slashed.startsWith("file://", ignoreCase = true) -> slashed.substring("file://".length)
                .takeUnless { it.substringBefore('/').equals("localhost", ignoreCase = true) }
                ?: return null
            slashed.startsWith("//") -> slashed.substring(2)
            else -> return null
        }
        val decoded = if (slashed.startsWith("file:", ignoreCase = true)) {
            runCatching { URLDecoder.decode(tail.replace("+", "%2B"), Charsets.UTF_8) }.getOrDefault(tail)
        } else {
            tail
        }
        return decoded.trimStart('/')
    }
}
