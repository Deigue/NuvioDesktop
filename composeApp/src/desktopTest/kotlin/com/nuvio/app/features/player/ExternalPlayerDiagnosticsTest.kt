package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExternalPlayerDiagnosticsTest {

    @Test
    fun sourceSummaryKeepsOnlyOrigin() {
        val source = "https://media.example.test:8443/private/token/video.mkv?api_key=secret"

        val summary = externalPlayerSourceSummary(source)

        assertEquals("https://media.example.test:8443", summary)
        assertFalse(summary.contains("token"))
        assertFalse(summary.contains("secret"))
    }

    @Test
    fun sourceSummaryDoesNotExposeLocalPaths() {
        assertEquals("file:(no-host)", externalPlayerSourceSummary("file:///C:/private/video.mkv"))
    }

    @Test
    fun malformedSourceIsReportedWithoutEchoingIt() {
        assertEquals("invalid-uri", externalPlayerSourceSummary("https://bad host/?token=secret"))
    }

    @Test
    fun vlcSlaveSubtitlesNeedAnExtensionVlcCanType() {
        assertTrue("C:\\cache\\03 en - English (SubMaker ElfHosted).srt".hasVlcSubtitleExtension())
        assertTrue("https://subs.example.com/v1.4/subtitle/8095426/eng.SRT?x=1".hasVlcSubtitleExtension())
        // Extensionless addon endpoints would be loaded by VLC as audio tracks.
        assertFalse("https://subs.example.com/v1.4.94/sub-toolbox/tt0898266:1:1?filename=".hasVlcSubtitleExtension())
        assertFalse("C:\\cache.d\\subtitle".hasVlcSubtitleExtension())
    }
}
