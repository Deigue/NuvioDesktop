package com.nuvio.app.features.player.skip

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SkipSubmitModelsTest {

    @Test
    fun `only intros and recaps are offered after a skip`() {
        assertTrue("intro".isOfferableSkipKind())
        assertTrue("op".isOfferableSkipKind(), "anime aliases map to the same kind")
        assertTrue("recap".isOfferableSkipKind())
        assertFalse("outro".isOfferableSkipKind(), "seeking to the end hands the key to the next-episode card")
        assertFalse("credits".isOfferableSkipKind())
        assertFalse("preview".isOfferableSkipKind())
    }

    @Test
    fun `a captured span in the last 40 percent is an outro`() {
        assertEquals("outro", inferCapturedSegmentType(startSec = 40 * 60.0, durationSec = 45 * 60.0))
        assertEquals("outro", inferCapturedSegmentType(startSec = 27 * 60.0, durationSec = 45 * 60.0))
    }

    @Test
    fun `everything else, including an unknown runtime, is an intro`() {
        assertEquals("intro", inferCapturedSegmentType(startSec = 5.0, durationSec = 45 * 60.0))
        assertEquals("intro", inferCapturedSegmentType(startSec = 26 * 60.0, durationSec = 45 * 60.0))
        assertEquals("intro", inferCapturedSegmentType(startSec = 40 * 60.0, durationSec = null))
        assertEquals("intro", inferCapturedSegmentType(startSec = 40 * 60.0, durationSec = 0.0))
    }

    @Test
    fun `an offer starts unlanded so the pending seek is not read as a seek back`() {
        val offer = SkipSubmitOffer(SkipInterval(5.0, 95.0, "intro", CHAPTER_SKIP_PROVIDER))

        assertFalse(offer.landed)
        assertEquals(SkipSubmitToastPhase.OFFER, offer.phase)
    }
}
