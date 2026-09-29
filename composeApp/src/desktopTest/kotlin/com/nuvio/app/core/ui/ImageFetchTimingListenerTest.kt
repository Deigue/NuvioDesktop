package com.nuvio.app.core.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class ImageFetchTimingListenerTest {

    @Test
    fun `credential-looking query values are masked, the rest of the URL is kept`() {
        val url = "https://posters.example/poster/tt123?tmdb_key=abc123&size=w500&mdblist_key=zzz&token=t0k"
        assertEquals(
            "https://posters.example/poster/tt123?tmdb_key=***&size=w500&mdblist_key=***&token=***",
            ImageFetchTimingListener.describeData(url),
        )
        assertEquals(
            "https://image.tmdb.org/t/p/w500/abc.jpg",
            ImageFetchTimingListener.describeData("https://image.tmdb.org/t/p/w500/abc.jpg"),
        )
    }

    @Test
    fun `long poster-service urls become host, last segment and a hash`() {
        val url = "https://posters.example/" + "cfg".repeat(60) + "/poster/tt123.jpg?tmdb_key=abc"
        val described = ImageFetchTimingListener.describeData(url)
        assert(described.startsWith("https://posters.example/…/tt123.jpg #")) { described }
        assert("abc" !in described.substringBefore(" #")) { described }
    }
}
