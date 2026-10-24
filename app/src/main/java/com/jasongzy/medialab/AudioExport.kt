package com.jasongzy.medialab

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal enum class AudioFormat(val label: String) {
    ORIGINAL(""), MP3("MP3"), AAC("AAC"), M4A("M4A"), FLAC("FLAC"), WAV("WAV"),
}

internal data class AudioEncoding(val codec: String, val container: String, val bits: Int) {
    val extension get() = when (container) {
        "mov" -> "m4a"
        "ogg" -> if (codec == "opus") "opus" else "ogg"
        "asf" -> "wma"
        "mp3", "aac", "flac", "wav", "aiff", "wv" -> container
        else -> null
    }
    val encoder get() = when (codec) {
        "mp3" -> "libmp3lame"
        "vorbis" -> "libvorbis"
        "opus" -> "libopus"
        "aac", "flac", "alac", "wavpack", "wmav1", "wmav2",
        "pcm_u8", "pcm_s16le", "pcm_s24le", "pcm_s32le", "pcm_f32le", "pcm_f64le",
        "pcm_s16be", "pcm_s24be", "pcm_s32be" -> codec
        else -> null
    }
    val lossy get() = codec in setOf("mp3", "aac", "vorbis", "opus", "wmav1", "wmav2")
}

internal data class AudioExportOptions(
    val format: AudioFormat = AudioFormat.ORIGINAL,
    val bitrateKbps: Int = 192,
    val compression: Int = 5,
    val bits: Int = 24,
) {
    fun encoding(original: AudioEncoding?): AudioEncoding? = when (format) {
        AudioFormat.ORIGINAL -> original
        AudioFormat.MP3 -> AudioEncoding("mp3", "mp3", 0)
        AudioFormat.AAC -> AudioEncoding("aac", "aac", 0)
        AudioFormat.M4A -> AudioEncoding("aac", "mov", 0)
        AudioFormat.FLAC -> AudioEncoding("flac", "flac", 0)
        AudioFormat.WAV -> AudioEncoding("pcm_s${bits}le", "wav", bits)
    }

    fun copiesOriginal(document: AudioDocument) =
        format == AudioFormat.ORIGINAL && document.current.file == document.file

    fun problem(document: AudioDocument): Int? {
        if (copiesOriginal(document)) return null
        val encoding = encoding(document.encoding)
        return when {
            encoding?.extension == null || encoding.encoder == null -> R.string.audio_original_unavailable
            encoding.codec == "mp3" && document.current.channels > 2 -> R.string.audio_mp3_channels
            else -> null
        }
    }
}

internal data class AudioExportPreview(
    val file: File, val frame: AudioFrame, val options: AudioExportOptions, val playable: File = file,
) {
    val files get() = setOf(file, playable)
}

internal suspend fun exportAudio(
    directory: File,
    document: AudioDocument,
    options: AudioExportOptions,
    progress: (Float?) -> Unit = {},
): File {
    options.problem(document)?.let { throw UnsupportedMediaException(it) }
    val frame = document.editor.history.value
    if (options.copiesOriginal(document))
        return AudioFiles.save(directory, document, frame.metadata.tags, frame.metadata.pictures)
    val encoding = options.encoding(document.encoding)
    val target = MediaFiles.temporary(directory, encoding?.extension ?: document.file.extension)
    try {
        checkNotNull(encoding)
        val quality = when {
            encoding.lossy -> {
                require(options.bitrateKbps in 32..(if (encoding.codec == "mp3") 320 else 512))
                listOf("-b:a", "${options.bitrateKbps}k")
            }
            encoding.codec == "flac" -> {
                require(options.compression in 0..12)
                listOf("-compression_level", options.compression.toString())
            }
            else -> emptyList()
        }
        if (options.format == AudioFormat.WAV) require(options.bits in listOf(16, 24, 32))
        MediaProcessing.execute(
            listOf("-protocol_whitelist", "file,pipe", "-threads", "2", "-i", frame.media.file.path,
                "-map", "0:a:0", "-vn", "-map_metadata", "-1", "-c:a", checkNotNull(encoding.encoder)) +
                quality + listOf("-threads", "2", target.path),
        ) { time ->
            progress(frame.media.durationMs.takeIf { it > 0 }?.let { (time.toFloat() / it).coerceIn(0f, 1f) })
        }
        val pictures = if (encoding.container == "mov") frame.metadata.pictures.map { picture ->
            val mime = AudioFiles.pictureMime(picture.data)
            if (mime in setOf("image/jpeg", "image/png", "image/gif", "image/bmp"))
                picture.copy(description = "", pictureType = "", mimeType = mime)
            else {
                val bitmap = checkNotNull(BitmapFactory.decodeByteArray(picture.data, 0, picture.data.size))
                val data = try {
                    ByteArrayOutputStream().use {
                        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                        it.toByteArray()
                    }
                } finally { bitmap.recycle() }
                picture.copy(data = data, description = "", pictureType = "", mimeType = "image/png")
            }
        }
        else frame.metadata.pictures
        AudioFiles.writeTags(target, frame.metadata.tags, pictures)
        currentCoroutineContext().ensureActive()
        return target
    } catch (error: Throwable) {
        target.delete()
        throw error
    }
}
