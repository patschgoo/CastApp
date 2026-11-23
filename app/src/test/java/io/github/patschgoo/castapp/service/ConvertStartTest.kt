package io.github.patschgoo.castapp.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Conversions that copy the picture as it is: where they start, and what happens when the TV refuses one. */
class ConvertStartTest {
    @Test
    fun aCopiedPictureStartsAtTheKeyFrameBefore() {
        // Seek to 10:00.0 in a TV recording: its key frame is at 9:59.52, and so is the TV's clock.
        assertEquals(599_520L, PlaybackChecks.convertStartMs(600_000, copies = true, keyFrameMs = 599_520))
        assertEquals(600_000L, PlaybackChecks.convertStartMs(600_000, copies = true, keyFrameMs = 600_000))
        // A full conversion starts exactly where asked (it decodes from the key frame and drops what's before).
        assertEquals(600_000L, PlaybackChecks.convertStartMs(600_000, copies = false, keyFrameMs = 599_520))
        // Unknown, after the position, or implausibly far back (no usable index): left alone.
        assertEquals(600_000L, PlaybackChecks.convertStartMs(600_000, copies = true, keyFrameMs = null))
        assertEquals(600_000L, PlaybackChecks.convertStartMs(600_000, copies = true, keyFrameMs = 600_400))
        assertEquals(600_000L, PlaybackChecks.convertStartMs(600_000, copies = true, keyFrameMs = 100_000))
        // An x264 film with 10 s between key frames is still fine.
        assertEquals(590_000L, PlaybackChecks.convertStartMs(600_000, copies = true, keyFrameMs = 590_000))
    }

    @Test
    fun aRefusedCopyIsConvertedInFullOnce() {
        assertTrue(PlaybackChecks.retryWithoutCopy(Delivery.CONVERT, pictureCopied = true, refusedBefore = false))
        // Converted in full already: nothing else to try with converting.
        assertFalse(PlaybackChecks.retryWithoutCopy(Delivery.CONVERT, pictureCopied = false, refusedBefore = false))
        assertFalse(PlaybackChecks.retryWithoutCopy(Delivery.CONVERT, pictureCopied = true, refusedBefore = true))
        // Other ways have their own next steps.
        assertFalse(PlaybackChecks.retryWithoutCopy(Delivery.SERVE, pictureCopied = true, refusedBefore = false))
        assertFalse(PlaybackChecks.retryWithoutCopy(Delivery.ORIGINAL, pictureCopied = true, refusedBefore = false))
    }
}
