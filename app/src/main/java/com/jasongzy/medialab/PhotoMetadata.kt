package com.jasongzy.medialab

import androidx.exifinterface.media.ExifInterface
import java.io.File

internal object PhotoMetadata {
    private val captureTags = setOf(
        ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME,
        ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
        ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
        ExifInterface.TAG_SUBSEC_TIME,
        ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
        ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_SOFTWARE,
        ExifInterface.TAG_LENS_MAKE,
        ExifInterface.TAG_LENS_MODEL,
        ExifInterface.TAG_LENS_SPECIFICATION,
        ExifInterface.TAG_LENS_SERIAL_NUMBER,
        ExifInterface.TAG_BODY_SERIAL_NUMBER,
        ExifInterface.TAG_CAMERA_OWNER_NAME,
        ExifInterface.TAG_IMAGE_DESCRIPTION,
        ExifInterface.TAG_ARTIST,
        ExifInterface.TAG_COPYRIGHT,
        ExifInterface.TAG_USER_COMMENT,
        ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_EXPOSURE_PROGRAM,
        ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        ExifInterface.TAG_SENSITIVITY_TYPE,
        ExifInterface.TAG_STANDARD_OUTPUT_SENSITIVITY,
        ExifInterface.TAG_RECOMMENDED_EXPOSURE_INDEX,
        ExifInterface.TAG_ISO_SPEED,
        ExifInterface.TAG_SHUTTER_SPEED_VALUE,
        ExifInterface.TAG_APERTURE_VALUE,
        ExifInterface.TAG_BRIGHTNESS_VALUE,
        ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
        ExifInterface.TAG_MAX_APERTURE_VALUE,
        ExifInterface.TAG_SUBJECT_DISTANCE,
        ExifInterface.TAG_METERING_MODE,
        ExifInterface.TAG_LIGHT_SOURCE,
        ExifInterface.TAG_FLASH,
        ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_EXPOSURE_MODE,
        ExifInterface.TAG_WHITE_BALANCE,
        ExifInterface.TAG_DIGITAL_ZOOM_RATIO,
        ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
        ExifInterface.TAG_SCENE_CAPTURE_TYPE,
    )

    // Geometry, thumbnails, MakerNote and XMP can refer to pixels removed by editing.
    fun retained(metadata: List<Pair<String, String>>): List<Pair<String, String>> =
        metadata.filter { (tag, _) -> tag in captureTags || tag.startsWith("GPS") }

    fun write(file: File, metadata: List<Pair<String, String>>, width: Int, height: Int) {
        val values = retained(metadata)
        if (values.isEmpty()) return
        ExifInterface(file).apply {
            values.forEach { (tag, value) -> setAttribute(tag, value) }
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            setAttribute(ExifInterface.TAG_IMAGE_WIDTH, width.toString())
            setAttribute(ExifInterface.TAG_IMAGE_LENGTH, height.toString())
            setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, width.toString())
            setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, height.toString())
            saveAttributes()
        }
    }
}
