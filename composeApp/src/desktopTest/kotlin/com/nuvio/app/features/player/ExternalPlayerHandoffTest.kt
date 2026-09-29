package com.nuvio.app.features.player

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExternalPlayerHandoffTest {
    private val request = ExternalPlayerPlaybackRequest(sourceUrl = "https://example.test/video.mkv", title = "Show")

    // Local paths, so the external-player cache passes them through without any network access.
    private fun subtitle(id: String, language: String, display: String = language) = AddonSubtitle(
        id = id,
        url = "C:\\subs\\$id.srt",
        language = language,
        display = display,
    )

    private fun prepare(
        settings: PlayerSettingsUiState,
        loaded: List<AddonSubtitle>,
        pinned: AddonSubtitle? = null,
        forward: Boolean = true,
    ) = runBlocking {
        prepareExternalPlayerLaunch(
            request = request,
            type = "",
            videoId = "",
            forwardSubtitles = forward,
            settings = settings,
            originalLanguage = null,
            loadedSubtitles = loaded,
            pinnedSubtitle = pinned,
            onOverlayMessage = {},
        )
    }

    @Test
    fun `loaded subtitles are filtered by language and reject keywords, then capped`() {
        val loaded = listOf(subtitle("fr", "fre"), subtitle("signs", "eng", "English Signs")) +
            (1..20).map { subtitle("en$it", "eng") }
        val settings = PlayerSettingsUiState(
            preferredSubtitleLanguage = "en",
            rejectedSubtitleKeywords = setOf(SubtitleRejectKeyword.SIGNS),
        )

        val urls = prepare(settings, loaded).subtitles.orEmpty().map { it.url }

        assertEquals((1..12).map { "C:\\subs\\en$it.srt" }, urls)
    }

    @Test
    fun `the track the viewer had on goes first even outside the language settings`() {
        val pinned = subtitle("fr", "fre")
        val settings = PlayerSettingsUiState(preferredSubtitleLanguage = "en")

        val urls = prepare(settings, listOf(subtitle("en1", "eng"), pinned), pinned).subtitles.orEmpty().map { it.url }

        assertEquals(listOf("C:\\subs\\fr.srt", "C:\\subs\\en1.srt"), urls)
    }

    @Test
    fun `no subtitle language set falls back to the device languages`() {
        val settings = PlayerSettingsUiState(preferredSubtitleLanguage = SubtitleLanguageOption.NONE)

        assertEquals(
            listOf("en-gb"),
            externalPlayerSubtitleTargets(settings, originalLanguage = null, deviceLanguages = { listOf("en-GB") }),
        )
        assertEquals(
            listOf("es"),
            externalPlayerSubtitleTargets(
                settings.copy(secondaryPreferredSubtitleLanguage = "es"),
                originalLanguage = null,
                deviceLanguages = { listOf("en-GB") },
            ),
        )
    }

    @Test
    fun `forwarding off and nothing pinned leaves the request without subtitles`() {
        val settings = PlayerSettingsUiState(preferredSubtitleLanguage = "en")

        assertNull(prepare(settings, listOf(subtitle("en1", "eng")), forward = false).subtitles)
    }
}
