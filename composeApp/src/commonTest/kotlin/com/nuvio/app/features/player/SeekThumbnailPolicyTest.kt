package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SeekThumbnailPolicyTest {

    private fun allowed(
        mode: DesktopSeekThumbnailMode,
        url: String,
        isTorrent: Boolean = false,
        preset: DesktopBufferPreset = DesktopBufferPreset.Balanced,
    ) = seekThumbnailsAllowed(mode, preset, url, isTorrent)

    @Test
    fun localModeCoversFilesAndHomeNetworkOnly() {
        val local = DesktopSeekThumbnailMode.Local
        assertTrue(allowed(local, "D:\\Media\\Show\\S01E01.mkv"))
        assertTrue(allowed(local, "\\\\nas\\media\\film.mkv"))
        assertTrue(allowed(local, "http://192.168.1.20:8096/Videos/abc/stream.mkv"))
        assertTrue(allowed(local, "http://10.0.0.5/film.mkv"))
        assertTrue(allowed(local, "http://172.20.1.1/film.mkv"))
        assertTrue(allowed(local, "http://nas.local:8096/film.mkv"))
        assertTrue(allowed(local, "http://[::1]:8080/film.mkv"))

        assertFalse(allowed(local, "https://nexus-133.neur.tb-cdn.st/dld/abc?token=x"))
        assertFalse(allowed(local, "https://aio.example/api/v1/debrid/playback/a/b/c/d/f.mkv"))
        assertFalse(allowed(local, "http://172.32.0.1/film.mkv"))
        assertFalse(allowed(local, "http://192.169.0.1/film.mkv"))
        assertFalse(allowed(local, "http://fdroid.example/film.mkv"))
    }

    /** The torrent engine serves from 127.0.0.1, but previews would pull swarm pieces. */
    @Test
    fun torrentsAreNeverLocal() {
        assertFalse(allowed(DesktopSeekThumbnailMode.Local, "http://127.0.0.1:8097/stream/abc", isTorrent = true))
        assertFalse(allowed(DesktopSeekThumbnailMode.Local, "torrent://abcdef?index=2"))
        assertTrue(allowed(DesktopSeekThumbnailMode.Streaming, "torrent://abcdef?index=2", isTorrent = true))
    }

    @Test
    fun offAndMeteredWin() {
        assertFalse(allowed(DesktopSeekThumbnailMode.Off, "D:\\Media\\film.mkv"))
        assertFalse(allowed(DesktopSeekThumbnailMode.Streaming, "D:\\Media\\film.mkv", preset = DesktopBufferPreset.Metered))
        assertTrue(allowed(DesktopSeekThumbnailMode.Streaming, "https://cdn.example/film.mkv"))
    }
}
