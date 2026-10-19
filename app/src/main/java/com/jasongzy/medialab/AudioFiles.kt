package com.jasongzy.medialab

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.kyant.taglib.AudioProperties
import com.kyant.taglib.Picture
import com.kyant.taglib.TagLib
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

internal data class AudioDocument(
    val file: File,
    val name: String,
    val properties: AudioProperties?,
    val saved: AudioMetadata,
    val technical: List<Pair<String, String>>,
    val editor: AudioEditorState = AudioEditorState(saved),
    val sourceUri: Uri? = null,
    val sourceBytes: Long? = null,
    val sourceDuration: Long? = properties?.length?.toLong(),
) {
    val tags: Map<String, List<String>>
        get() = editor.history.value.tags
    val pictures: List<Picture>
        get() = editor.history.value.pictures
    val dirty: Boolean
        get() = editor.history.value != saved
}

internal object AudioFiles {
    fun validate(file: File) {
        if (TagLib.getAudioProperties(fd(file)) == null)
            throw UnsupportedMediaException(R.string.select_audio_only)
    }

    private fun fd(file: File, writable: Boolean = false): Int =
        ParcelFileDescriptor.open(
                file,
                if (writable) ParcelFileDescriptor.MODE_READ_WRITE
                else ParcelFileDescriptor.MODE_READ_ONLY,
            )
            .detachFd()

    fun read(file: File, name: String): AudioDocument {
        val metadata =
            TagLib.getMetadata(fd(file))
                ?: throw UnsupportedMediaException(R.string.select_audio_only)
        val properties = TagLib.getAudioProperties(fd(file))
        val extractor = MediaExtractor()
        val technical = mutableListOf<Pair<String, String>>()
        try {
            extractor.setDataSource(file.path)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    for (key in format.keys.sorted()) {
                        if (key.startsWith("csd-")) continue
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
        return AudioDocument(
            file,
            name,
            properties,
            AudioMetadata(metadata.propertyMap.mapValues { it.value.toList() }, metadata.pictures.toList()),
            technical,
        )
    }

    fun thumbnail(data: ByteArray, size: Int = 512): Bitmap? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, options)
        options.inSampleSize = maxOf(1, maxOf(options.outWidth, options.outHeight) / size)
        options.inJustDecodeBounds = false
        return BitmapFactory.decodeByteArray(data, 0, data.size, options)
    }

    fun pictureMime(data: ByteArray): String {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, options)
        if (options.outWidth <= 0 || options.outHeight <= 0 || options.outMimeType == null)
            throw UnsupportedMediaException(R.string.select_image_only)
        return options.outMimeType
    }

    suspend fun save(
        directory: File,
        document: AudioDocument,
        tags: Map<String, List<String>>,
        pictures: List<Picture>,
    ): File {
        val target = MediaFiles.temporary(directory, document.file.extension)
        try {
            document.file.inputStream().use { input ->
                target.outputStream().use { MediaFiles.copy(input, it) }
            }
            writeTags(target, tags, pictures)
            return target
        } catch (error: Exception) {
            target.delete()
            throw error
        }
    }

    private suspend fun writeTags(
        target: File,
        tags: Map<String, List<String>>,
        pictures: List<Picture>,
    ) {
        val original = requireNotNull(TagLib.getMetadata(fd(target)))
        val originalKeys = original.propertyMap.keys
        require(
            tags.keys.all { it.isNotBlank() && it.length <= 256 } &&
                pictures.sumOf { it.data.size.toLong() } <= 64 * 1024 * 1024
        )
        currentCoroutineContext().ensureActive()
        check(
            TagLib.savePropertyMap(
                fd(target, true),
                HashMap(tags.mapValues { it.value.toTypedArray() }),
            )
        ) {
            "Cannot save audio tags"
        }
        if (!samePictures(original.pictures.toList(), pictures))
            check(TagLib.savePictures(fd(target, true), pictures.toTypedArray())) {
                "Cannot save cover art"
            }
        val result = requireNotNull(TagLib.getMetadata(fd(target)))
        for ((key, values) in tags) check(result.propertyMap[key]?.toList().orEmpty() == values) {
            "Tag verification failed: $key"
        }
        check((originalKeys - tags.keys).none { it in result.propertyMap }) {
            "Tag removal verification failed"
        }
        if (!samePictures(result.pictures.toList(), pictures))
            throw UnsupportedMediaException(R.string.artwork_not_saved)
        currentCoroutineContext().ensureActive()
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
            val original =
                requireNotNull(TagLib.getMetadata(fd(target))) { "Cannot read decoded audio" }
            val tags = original.propertyMap.mapValues { it.value.toList() }.toMutableMap()
            result.metadata?.let {
                val json = JSONObject(it)
                for ((field, key) in listOf("musicName" to "TITLE", "album" to "ALBUM")) {
                    json
                        .optString(field)
                        .takeIf { text -> text.isNotBlank() }
                        ?.let { text -> tags[key] = listOf(text) }
                }
                json.optJSONArray("artist")?.let { array ->
                    val artists =
                        (0 until array.length()).mapNotNull { i ->
                            array.optJSONArray(i)?.optString(0)?.takeIf { it.isNotBlank() }
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
                } else original.pictures.toList()
            writeTags(target, tags, pictures)
            return target
        } catch (error: Exception) {
            target.delete()
            throw error
        }
    }
}
