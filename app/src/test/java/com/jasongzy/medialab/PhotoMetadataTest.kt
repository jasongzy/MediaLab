package com.jasongzy.medialab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoMetadataTest {
    @Test
    fun retainsCaptureInformationWithoutStalePixelData() {
        val capture = listOf(
            "DateTime" to "2026:10:18 12:34:56",
            "Software" to "Camera",
            "ExposureTime" to "0.01",
            "GPSLatitude" to "31/1,12/1,0/1",
            "UserComment" to "a".repeat(9000),
        )
        val stale = listOf(
            "Orientation" to "6",
            "PixelXDimension" to "4000",
            "ThumbnailImageWidth" to "160",
            "SubjectArea" to "200,200,10,10",
            "MakerNote" to "original pixels",
            "Xmp" to "edit recovery data",
        )
        assertEquals(capture, PhotoMetadata.retained(capture + stale))
        assertTrue(PhotoMetadata.retained(emptyList()).isEmpty())
    }
}
