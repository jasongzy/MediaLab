package com.jasongzy.medialab

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioPlayerTest {
    @Test
    fun playbackTimeHandlesUnknownDurationAndHourBoundary() {
        assertEquals("0:00", playbackTime(Long.MIN_VALUE))
        assertEquals("0:59", playbackTime(59_999))
        assertEquals("1:00", playbackTime(60_000))
        assertEquals("59:59", playbackTime(3_599_999))
        assertEquals("1:00:00", playbackTime(3_600_000))
        assertEquals("12:34:56", playbackTime(45_296_000))
    }
}
