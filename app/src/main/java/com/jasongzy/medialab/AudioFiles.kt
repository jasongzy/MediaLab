package com.jasongzy.medialab

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.arthenica.ffmpegkit.MediaInformation
import com.kyant.taglib.AudioProperties
import com.kyant.taglib.Picture
import com.kyant.taglib.TagLib
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

internal data class AudioDocument(
    val file: File,
    val name: String,
    val properties: AudioProperties,
    val metadata: AudioMetadata,
    val technical: List<Pair<String, String>>,
    val encoding: AudioEncoding? = null,
    val playable: File = file,
    val saved: AudioFrame = AudioFrame(AudioMedia(file, properties, technical), metadata),
    val editor: AudioEditorState = AudioEditorState(saved, AudioExportOptions(
        bits = encoding?.bits?.takeIf { it in listOf(16, 24, 32) } ?: 24,
    )),
    val sourceUri: Uri? = null,
    val sourceBytes: Long? = null,
) {
    val tags: Map<String, List<String>>
        get() = editor.history.value.metadata.tags
    val pictures: List<Picture>
        get() = editor.history.value.metadata.pictures
    val current get() = editor.history.value.media
    val sourceFormat get() = encoding?.extension ?: file.extension
    val sourceMime get() = MediaFiles.mime(sourceFormat, ImportType.AUDIO)
        .takeIf { it.startsWith("audio/") || sourceFormat in mp4AudioExtensions } ?: "audio/*"
    val playbackFile get() = if (current.file == file) playable else current.file
    val files get() = editor.files + playable + editor.preview?.files.orEmpty()
    val workingFiles get() = editor.files + playable + editor.preview?.takeIf { editor.showPreview }?.files.orEmpty()
    val needsReplacementConfirmation: Boolean
        get() = editor.history.value != saved || editor.history.canRedo ||
            editor.tag != null || editor.picture != null || editor.pending.isNotEmpty()
}

internal object AudioFiles {
    private const val MAX_ARTWORK_MIB = 64

    suspend fun inspect(file: File): Pair<AudioProperties, MediaInformation?> {
        val properties = TagLib.getAudioProperties(fd(file))
            ?: throw UnsupportedMediaException(R.string.select_audio_only)
        val info = MediaProcessing.inspect(file)
        if (info != null && (info.videoTrack != null || info.streams.none { it.type == "audio" }))
            throw UnsupportedMediaException(R.string.select_audio_only)
        return properties to info
    }

    private fun fd(file: File, writable: Boolean = false): Int =
        ParcelFileDescriptor.open(
                file,
                if (writable) ParcelFileDescriptor.MODE_READ_WRITE
                else ParcelFileDescriptor.MODE_READ_ONLY,
            )
            .detachFd()

    private fun readMetadata(file: File): AudioMetadata? = TagLib.getMetadata(fd(file))?.let {
        AudioMetadata(it.propertyMap.mapValues { (_, values) -> values.toList() }, it.pictures.toList())
    }

    suspend fun read(file: File, name: String): AudioDocument {
        var (properties, info) = inspect(file)
        val metadata = readMetadata(file) ?: throw UnsupportedMediaException(R.string.select_audio_only)
        val propertyKeys = setOf(
            MediaFormat.KEY_DURATION, MediaFormat.KEY_BIT_RATE, MediaFormat.KEY_SAMPLE_RATE, MediaFormat.KEY_CHANNEL_COUNT,
        )
        val extractor = MediaExtractor()
        val technical = mutableListOf<Pair<String, String>>()
        try {
            extractor.setDataSource(file.path)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    properties = AudioProperties(
                        length = properties.length.takeIf { it > 0 }
                            ?: (format.getLong(MediaFormat.KEY_DURATION, 0L) / 1000)
                                .takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt() ?: 0,
                        bitrate = properties.bitrate.takeIf { it > 0 }
                            ?: format.getInteger(MediaFormat.KEY_BIT_RATE, 0) / 1000,
                        sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE, 0).takeIf { it > 0 }
                            ?: properties.sampleRate,
                        channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT, 0).takeIf { it > 0 }
                            ?: properties.channels,
                    )
                    for (key in format.keys.sorted()) {
                        if (key.startsWith("csd-") || key in propertyKeys) continue
                        val value =
                            when (format.getValueTypeForKey(key)) {
                                MediaFormat.TYPE_INTEGER -> format.getInteger(key).toString()
                                MediaFormat.TYPE_LONG -> format.getLong(key).toString()
                                MediaFormat.TYPE_FLOAT -> format.getFloat(key).toString()
                                MediaFormat.TYPE_STRING -> format.getString(key).orEmpty()
                                else -> continue
                            }
                        technical += key to value
                    }
                    break
                }
            }
        } catch (_: java.io.IOException) {
            // TagLib also supports containers that the Android media extractor cannot open.
        } finally {
            extractor.release()
        }
        val stream = info?.streams?.firstOrNull { it.type == "audio" }
        val encoding = stream?.let {
            AudioEncoding(it.codec.orEmpty(), info.container(),
                it.allProperties.optInt("bits_per_raw_sample", 0).takeIf { bits -> bits > 0 }
                    ?: it.allProperties.optInt("bits_per_sample", 0))
        }
        val playable = preparePlayback(file, encoding)
        try {
            if (playable != file) properties = checkNotNull(TagLib.getAudioProperties(fd(playable)))
            stream?.allProperties?.let { track ->
                properties = properties.copy(
                    channels = properties.channels.takeIf { it > 0 } ?: track.optInt("channels"),
                    sampleRate = properties.sampleRate.takeIf { it > 0 } ?: track.optInt("sample_rate"),
                )
            }
            currentCoroutineContext().ensureActive()
            return AudioDocument(
                file,
                name,
                properties,
                metadata,
                technical,
                encoding,
                playable,
            )
        } catch (error: Throwable) {
            if (playable != file) playable.delete()
            throw error
        }
    }

    suspend fun preparePlayback(file: File, encoding: AudioEncoding?, directory: File = checkNotNull(file.parentFile)): File {
        if (encoding?.container != "aac") return file
        val target = MediaFiles.temporary(directory, "m4a")
        try {
            // MP4 indexes every AAC packet, giving the player exact duration and seek positions.
            MediaProcessing.execute(listOf("-protocol_whitelist", "file,pipe", "-i", file.path,
                "-map", "0:a:0", "-c:a", "copy", "-map_metadata", "-1", target.path))
            return target
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    fun decodeArtwork(data: ByteArray, maxEdge: Int? = null): Bitmap? {
        if (data.isEmpty()) return null
        return try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(data)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                if (maxEdge != null) decoder.fitWithin(info.size, maxEdge)
            }
        } catch (_: IOException) { null }
    }

    fun pictureMime(data: ByteArray): String {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, options)
        if (options.outWidth <= 0 || options.outHeight <= 0 || options.outMimeType == null)
            throw UnsupportedMediaException(R.string.select_image_only)
        return options.outMimeType
    }

    fun pngArtwork(bitmap: Bitmap, directory: File, limitMiB: Int): ByteArray {
        val file = MediaFiles.temporary(directory, "png")
        try {
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            checkArtworkSize(file.length(), limitMiB)
            return file.readBytes()
        } finally { file.delete() }
    }

    private fun checkArtworkSize(bytes: Long, limitMiB: Int) {
        if (bytes > limitMiB * 1024L * 1024)
            throw UnsupportedMediaException(R.string.artwork_size_limit, limitMiB.toString())
    }

    private fun mp4Picture(picture: Picture, directory: File): Picture {
        val mime = pictureMime(picture.data)
        if (mime in setOf("image/jpeg", "image/png", "image/gif", "image/bmp"))
            return picture.copy(description = "", pictureType = "", mimeType = mime)
        val bitmap = decodeArtwork(picture.data) ?: throw UnsupportedMediaException(R.string.select_image_only)
        val data = try { pngArtwork(bitmap, directory, MAX_ARTWORK_MIB) } finally { bitmap.recycle() }
        return picture.copy(data = data, description = "", pictureType = "", mimeType = "image/png")
    }

    suspend fun saveOriginal(
        directory: File,
        document: AudioDocument,
        tags: Map<String, List<String>>,
        pictures: List<Picture>,
    ): File {
        val target = MediaFiles.temporary(directory, document.sourceFormat)
        try {
            document.file.inputStream().use { input ->
                target.outputStream().use { MediaFiles.copy(input, it) }
            }
            writeTags(target, tags, pictures, document.metadata)
            return target
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    suspend fun writeTags(
        target: File,
        tags: Map<String, List<String>>,
        pictures: List<Picture>,
        original: AudioMetadata = checkNotNull(readMetadata(target)),
    ) {
        val tagsChanged = original.tags != tags
        val outputPictures = if (target.extension in mp4AudioExtensions) pictures.mapIndexed { index, picture ->
            original.pictures.getOrNull(index)?.takeIf { it.data.contentEquals(picture.data) }
                ?: mp4Picture(picture, target.parentFile!!)
        } else pictures
        val picturesChanged = !samePictures(original.pictures, outputPictures)
        currentCoroutineContext().ensureActive()
        if (!tagsChanged && !picturesChanged) return
        if (tagsChanged) {
            if (!TagLib.savePropertyMap(fd(target, true), HashMap(tags.mapValues { it.value.toTypedArray() })))
                throw UnsupportedMediaException(R.string.metadata_not_saved)
        }
        if (picturesChanged) {
            checkArtworkSize(outputPictures.sumOf { it.data.size.toLong() }, MAX_ARTWORK_MIB)
            if (!TagLib.savePictures(fd(target, true), outputPictures.toTypedArray()))
                throw UnsupportedMediaException(R.string.artwork_not_saved)
        }
        val result = checkNotNull(readMetadata(target))
        val rejected = tags.entries.firstOrNull { (key, values) ->
            !sameTagValues(key, values, result.tags[key].orEmpty())
        }?.key ?: original.tags.keys.firstOrNull { it !in tags && it in result.tags }
        if (rejected != null) throw UnsupportedMediaException(R.string.audio_tag_not_saved, rejected)
        if (!samePictures(result.pictures, outputPictures))
            throw UnsupportedMediaException(R.string.artwork_not_saved)
        currentCoroutineContext().ensureActive()
    }

    fun sameTagValues(key: String, expected: List<String>, actual: List<String>): Boolean {
        if (expected == actual) return true
        if (key !in setOf("DISCNUMBER", "TRACKNUMBER") || expected.size != actual.size) return false
        // MP4 stores number/total as integers, discarding padding and an unspecified zero total.
        fun numbers(value: String): Pair<Int, Int>? {
            val parts = value.split('/')
            if (parts.size !in 1..2) return null
            val number = parts[0].trim().toIntOrNull()?.takeIf { it >= 0 } ?: return null
            val total = if (parts.size == 1) 0
                else parts[1].trim().toIntOrNull()?.takeIf { it >= 0 } ?: return null
            return number to total
        }
        return expected.indices.all { index ->
            val number = numbers(expected[index])
            number != null && number == numbers(actual[index])
        }
    }

    private fun samePictures(first: List<Picture>, second: List<Picture>): Boolean =
        first.size == second.size &&
            first.indices.all {
                first[it].data.contentEquals(second[it].data) &&
                    first[it].description == second[it].description &&
                    first[it].pictureType == second[it].pictureType &&
                    first[it].mimeType == second[it].mimeType
            }

    suspend fun unlock(
        source: InputStream,
        directory: File,
        size: Long,
        restoreMetadata: Boolean = true,
        onFormat: (String) -> Unit = {},
        progress: (Float?) -> Unit,
    ): File {
        val coroutine = currentCoroutineContext()
        val temporary = File(directory, "decoded.bin")
        val result =
            Ncm.decode(source, temporary, size, onFormat) { done, total ->
                coroutine.ensureActive()
                progress(if (total > 0) done.toFloat() / total else null)
            }
        val target =
            File(temporary.parentFile, temporary.nameWithoutExtension + "." + result.extension)
        check(temporary.renameTo(target)) { "Cannot publish decoded audio" }
        if (!restoreMetadata) return target
        try {
            val original = checkNotNull(readMetadata(target)) { "Cannot read decoded audio" }
            val tags = original.tags.toMutableMap()
            result.metadata?.let {
                val json = JSONObject(it)
                for ((field, key) in listOf("musicName" to "TITLE", "album" to "ALBUM")) {
                    val text = json.opt(field) as? String
                    if (!text.isNullOrBlank()) tags[key] = listOf(text)
                }
                json.optJSONArray("artist")?.let { array ->
                    val artists =
                        (0 until array.length()).mapNotNull { i ->
                            (array.optJSONArray(i)?.opt(0) as? String)?.takeIf { it.isNotBlank() }
                        }
                    if (artists.isNotEmpty()) tags["ARTIST"] = artists
                }
            }
            val pictures =
                if (result.cover.isNotEmpty()) {
                    listOf(
                        Picture(
                            result.cover,
                            "",
                            "Front Cover",
                            pictureMime(result.cover),
                        )
                    )
                } else original.pictures
            writeTags(target, tags, pictures, original)
            return target
        } catch (error: Exception) {
            target.delete()
            throw error
        }
    }
}
