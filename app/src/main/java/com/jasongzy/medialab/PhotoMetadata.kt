package com.jasongzy.medialab

import androidx.exifinterface.media.ExifInterface
import android.graphics.Bitmap
import java.io.File
import java.io.RandomAccessFile
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import kotlin.math.abs
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal object PhotoMetadata {
    fun group(key: String, metadata: List<Pair<String, String>>): List<Pair<String, String>> {
        val groups = listOf(
            listOf("GPSLatitude", "GPSLatitudeRef", "GPSLongitude", "GPSLongitudeRef"),
            listOf("GPSAltitude", "GPSAltitudeRef"),
            listOf("GPSDateStamp", "GPSTimeStamp"),
            listOf("DateTime", "OffsetTime", "SubSecTime"),
            listOf("DateTimeOriginal", "OffsetTimeOriginal", "SubSecTimeOriginal"),
            listOf("DateTimeDigitized", "OffsetTimeDigitized", "SubSecTimeDigitized"),
        )
        val keys = groups.firstOrNull { key in it } ?: listOf(key)
        return keys.mapNotNull { tag -> metadata.firstOrNull { it.first == tag } }
    }

    suspend fun normalize(directory: File, changes: Map<String, String?>): Map<String, String?> {
        if (retained(changes.map { it.key to it.value.orEmpty() }).size != changes.size ||
            changes.any { (key, value) -> !validValue(key, value) })
            throw UnsupportedMediaException(R.string.invalid_metadata)
        val file = MediaFiles.temporary(directory, "png")
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        try {
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val exif = ExifInterface(file)
            changes.forEach { (key, value) -> exif.setAttribute(key, value) }
            return changes.mapValues { (key, value) ->
                val normalized = exif.getAttribute(key)
                if (!sameValue(value, normalized)) throw UnsupportedMediaException(R.string.invalid_metadata)
                normalized
            }
        } finally {
            bitmap.recycle()
            file.delete()
        }
    }

    private fun validValue(key: String, value: String?): Boolean {
        if (value == null) return true
        if (value.length > 32768 || '\u0000' in value) return false
        return try {
            when {
                key.startsWith("DateTime") -> LocalDateTime.parse(value,
                    DateTimeFormatter.ofPattern("uuuu:MM:dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT))
                key.startsWith("OffsetTime") -> java.time.ZoneOffset.of(value)
                key.startsWith("SubSecTime") -> require(value.all(Char::isDigit))
                key == "GPSLatitudeRef" -> require(value in listOf("N", "S"))
                key == "GPSLongitudeRef" -> require(value in listOf("E", "W"))
                key == "GPSLatitude" || key == "GPSLongitude" -> {
                    val parts = value.split(',').map { requireNotNull(number(it)) }
                    val limit = if (key == "GPSLatitude") 90.0 else 180.0
                    require(parts.size == 3 && parts[0] in 0.0..limit &&
                        parts[1] >= 0 && parts[1] < 60 && parts[2] >= 0 && parts[2] < 60 &&
                        parts[0] + parts[1] / 60 + parts[2] / 3600 <= limit)
                }
                key == "GPSAltitudeRef" -> require(value in listOf("0", "1"))
                key == "GPSAltitude" -> require(requireNotNull(number(value)) >= 0)
            }
            true
        } catch (_: IllegalArgumentException) { false }
        catch (_: java.time.DateTimeException) { false }
    }

    private fun sameValue(expected: String?, actual: String?): Boolean {
        if (expected == actual) return true
        if (expected == null || actual == null) return false
        val a = expected.split(',')
        val b = actual.split(',')
        return a.size == b.size && a.indices.all {
            val x = number(a[it])
            val y = number(b[it])
            x != null && y != null && abs(x - y) <= maxOf(1e-9, abs(x) * 1e-6)
        }
    }

    private fun number(value: String): Double? {
        val parts = value.trim().split('/')
        val numerator = parts.first().toDoubleOrNull() ?: return null
        val result = when (parts.size) {
            1 -> numerator
            2 -> numerator / (parts[1].toDoubleOrNull() ?: return null)
            else -> return null
        }
        return result.takeIf { it.isFinite() }
    }

    suspend fun update(source: File, target: File, changes: Map<String, String?>) {
        val coroutine = currentCoroutineContext()
        val container = Containers.inspect(source)
        val working = if (container == null) target else MediaFiles.temporary(checkNotNull(target.parentFile), "jpg")
        try {
            working.outputStream().use { output ->
                if (container == null) source.inputStream().use { MediaFiles.copy(it, output) }
                else RandomAccessFile(source, "r").use {
                    Containers.copyRange(it, output, ByteRange(0, container.jpeg.end)) { coroutine.ensureActive() }
                }
            }
            ExifInterface(working).apply {
                changes.forEach { (key, value) -> setAttribute(key, value) }
                saveAttributes()
                changes.forEach { (key, value) ->
                    if (!sameValue(value, getAttribute(key))) throw UnsupportedMediaException(R.string.metadata_not_saved)
                }
            }
            if (container != null) {
                val exif = Containers.jpeg(working).parts.first {
                    it.marker == 0xe1 && it.payload.take(6) == "Exif\u0000\u0000".toByteArray().toList()
                }.payload
                Containers.replaceExif(source, target, exif) { coroutine.ensureActive() }
            }
            val result = ExifInterface(target)
            changes.forEach { (key, value) ->
                if (!sameValue(value, result.getAttribute(key))) throw UnsupportedMediaException(R.string.metadata_not_saved)
            }
        } finally {
            if (working != target) working.delete()
        }
    }
    fun read(exif: ExifInterface): List<Pair<String, String>> {
        return ExifInterface::class.java.fields
            .filter { it.name.startsWith("TAG_") && it.type == String::class.java }
            .mapNotNull { field ->
                val tag = field.get(null) as String
                exif.getAttribute(tag)?.let { tag to it }
            }
            .distinctBy { it.first }
            .sortedBy { it.first }
    }

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

    suspend fun write(file: File, metadata: List<Pair<String, String>>, width: Int, height: Int,
        changes: Map<String, String?> = emptyMap()) {
        val values = retained(metadata).toMap() + changes
        if (values.isEmpty()) return
        if (Containers.inspect(file)?.gainmap != null) {
            val target = MediaFiles.temporary(checkNotNull(file.parentFile), "jpg")
            try {
                update(file, target, values + mapOf(
                    ExifInterface.TAG_ORIENTATION to ExifInterface.ORIENTATION_NORMAL.toString(),
                    ExifInterface.TAG_IMAGE_WIDTH to width.toString(),
                    ExifInterface.TAG_IMAGE_LENGTH to height.toString(),
                    ExifInterface.TAG_PIXEL_X_DIMENSION to width.toString(),
                    ExifInterface.TAG_PIXEL_Y_DIMENSION to height.toString(),
                ))
                java.nio.file.Files.move(target.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            } finally { target.delete() }
            return
        }
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
