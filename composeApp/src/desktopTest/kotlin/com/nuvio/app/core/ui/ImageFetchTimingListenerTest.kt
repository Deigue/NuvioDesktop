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
}
