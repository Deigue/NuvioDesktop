package com.nuvio.app.features.player

import com.nuvio.app.features.player.PlaybackSourcePolicy.Verdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PlaybackSourcePolicyTest {
    private val noRoots = emptyList<String>()

    private fun allowed(source: String, roots: List<String> = noRoots) =
        assertEquals(Verdict.Allowed, PlaybackSourcePolicy.check(source, roots), source)

    private fun rejected(source: String, roots: List<String> = noRoots) =
        assertIs<Verdict.Rejected>(PlaybackSourcePolicy.check(source, roots), source)

    @Test
    fun `ordinary stream and local sources pass`() {
        allowed("https://cdn.example.com/video.mkv?token=abc")
        allowed("http://127.0.0.1:11470/abc/0")
        allowed("rtmp://live.example.com/app/stream")
        allowed("C:\\Users\\me\\Videos\\film.mkv")
        allowed("D:/Media/film.mp4")
        allowed("file:///C:/Users/me/Videos/film.mkv")
    }

    @Test
    fun `option-shaped sources are refused`() {
        rejected("--script=\\\\evil\\share\\x.lua")
        rejected("-v")
        rejected("/dub evil")
        rejected("  --include=x")
    }

    @Test
    fun `network shares are refused unless under a library folder`() {
        rejected("\\\\attacker.example\\share\\a.mkv")
        rejected("//attacker.example/share/a.mkv")
        rejected("file://attacker.example/share/a.mkv")
        rejected("file:////attacker.example/share/a.mkv")

        val roots = listOf("\\\\nas\\Media")
        allowed("\\\\nas\\Media\\Films\\a.mkv", roots)
        allowed("file://nas/Media/Films/a%20b.mkv", roots)
        rejected("\\\\nas\\MediaOther\\a.mkv", roots)
        rejected("\\\\nas\\Other\\a.mkv", roots)
    }

    @Test
    fun `other schemes are refused`() {
        rejected("ms-settings:privacy")
        rejected("search-ms:query=x")
        rejected("javascript:alert(1)")
    }

    @Test
    fun `in-process playback only refuses network shares`() {
        assertEquals(Verdict.Allowed, PlaybackSourcePolicy.checkInProcess("ytdl://abc", noRoots))
        assertEquals(Verdict.Allowed, PlaybackSourcePolicy.checkInProcess("C:\\a.mkv", noRoots))
        assertIs<Verdict.Rejected>(PlaybackSourcePolicy.checkInProcess("\\\\host\\s\\a.mkv", noRoots))
        assertEquals(
            Verdict.Allowed,
            PlaybackSourcePolicy.checkInProcess("\\\\nas\\Media\\a.mkv", listOf("\\\\nas\\Media\\")),
        )
    }

    @Test
    fun `the OS handler only gets web streams and media files`() {
        assertTrue(PlaybackSourcePolicy.allowsSystemHandler("https://x.example/a.mkv", noRoots))
        assertTrue(PlaybackSourcePolicy.allowsSystemHandler("C:\\Videos\\a.mkv", noRoots))
        assertTrue(PlaybackSourcePolicy.allowsSystemHandler("file:///C:/Videos/a.mp4", noRoots))
        assertFalse(PlaybackSourcePolicy.allowsSystemHandler("file:///C:/Windows/System32/calc.exe", noRoots))
        assertFalse(PlaybackSourcePolicy.allowsSystemHandler("C:\\x\\run.bat", noRoots))
        assertFalse(PlaybackSourcePolicy.allowsSystemHandler("rtsp://x.example/live", noRoots))
    }
}
