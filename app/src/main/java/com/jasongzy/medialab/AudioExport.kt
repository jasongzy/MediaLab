package com.jasongzy.medialab

import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal enum class AudioFormat(val label: String) {
    ORIGINAL(""), MP3("MP3"), AAC("AAC"), M4A("M4A"), FLAC("FLAC"), WAV("WAV"),
}

private val mp3SampleRates = listOf(48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000)
private val aacSampleRates = listOf(96000, 88200, 64000) + mp3SampleRates + 7350

private val mpeg25Bitrates = listOf(8, 16, 24, 32, 40, 48, 56, 64)
private val mpeg2Bitrates = mpeg25Bitrates + listOf(80, 96, 112, 128, 144, 160)
private val mpeg1Bitrates = listOf(32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
internal val mp4AudioExtensions = setOf("m4a", "mov", "3ga", "3g2")

internal data class AudioEncoding(val codec: String, val container: String, val bits: Int) {
    val extension get() = when (container) {
        "mp4" -> "m4a"
        "3gp" -> "3ga"
        "mp3" -> if (codec == "mp1" || codec == "mp2") codec else container
        "ogg" -> if (codec == "opus") "opus" else "ogg"
        "asf" -> "wma"
        "aac", "m4a", "mov", "3g2", "flac", "wav", "aiff", "wv", "ape" -> container
        else -> null
    }
    val encoder get() = when (codec) {
        "mp3" -> "libmp3lame"
        "vorbis" -> "libvorbis"
        "opus" -> "libopus"
        "aac", "flac", "alac", "wavpack", "wmav1", "wmav2",
        "pcm_alaw", "pcm_mulaw", "pcm_u8", "pcm_s8",
        "pcm_s16le", "pcm_s24le", "pcm_s32le", "pcm_f32le", "pcm_f64le",
        "pcm_s16be", "pcm_s24be", "pcm_s32be", "pcm_f32be", "pcm_f64be" -> codec
        else -> null
    }
    fun sampleRate(media: AudioMedia): Int? {
        val rates = when (codec) {
            "aac" -> aacSampleRates
            "mp3" -> mp3SampleRates
            else -> return null
        }
        val source = media.properties.sampleRate.takeIf { it > 0 } ?: return if (codec == "mp3") 44100 else null
        return rates.minBy { abs(it - source) }
    }

    fun bitrateSteps(media: AudioMedia): List<Int>? = if (codec != "mp3") null else when (sampleRate(media)) {
        in 8000..12000 -> mpeg25Bitrates
        in 16000..24000 -> mpeg2Bitrates
        else -> mpeg1Bitrates
    }

    fun bitrateRange(media: AudioMedia): IntRange? = when (codec) {
        "opus" -> 32..(if (media.channels == 1) 256 else 512)
        "aac" -> {
            // AAC-LC allows 6144 bits per channel in a frame of 1024 samples.
            val rate = sampleRate(media)
            val maximum = if (rate == null || media.channels <= 0) 512
                else minOf(512L, 6L * rate * media.channels / 1000).toInt()
            32..maximum
        }
        "wmav1", "wmav2" -> 32..512
        else -> null
    }

    fun bitrate(media: AudioMedia, requested: Int): Int? =
        bitrateSteps(media)?.minBy { abs(it - requested) } ?: bitrateRange(media)?.let { requested.coerceIn(it) }
}

internal data class AudioExportOptions(
    val format: AudioFormat = AudioFormat.ORIGINAL,
    val bitrateKbps: Int = 192,
    val compression: Int = 5,
    val bits: Int = 24,
    val vorbisQuality: Int = 5,
) {
    fun encoding(original: AudioEncoding?): AudioEncoding? = when (format) {
        AudioFormat.ORIGINAL -> original
        AudioFormat.MP3 -> AudioEncoding("mp3", "mp3", 0)
        AudioFormat.AAC -> AudioEncoding("aac", "aac", 0)
        AudioFormat.M4A -> AudioEncoding("aac", "m4a", 0)
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

    fun forMedia(document: AudioDocument): AudioExportOptions {
        val bitrate = encoding(document.encoding)?.bitrate(document.current, bitrateKbps) ?: return this
        return if (bitrate == bitrateKbps) this else copy(bitrateKbps = bitrate)
    }
}

internal val AudioDocument.exportOptions: AudioExportOptions
    get() = editor.exportOptions.forMedia(this)

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
        return AudioFiles.saveOriginal(directory, document, frame.metadata.tags, frame.metadata.pictures)
    val encoding = checkNotNull(options.encoding(document.encoding))
    val target = MediaFiles.temporary(directory, checkNotNull(encoding.extension))
    try {
        val sampleRate = encoding.sampleRate(frame.media)?.let { listOf("-ar", it.toString()) }.orEmpty()
        val bitrate = encoding.bitrate(frame.media, options.bitrateKbps)
        val quality = when {
            bitrate != null -> listOf("-b:a", "${bitrate}k")
            encoding.codec == "vorbis" -> {
                require(options.vorbisQuality in 0..10)
                listOf("-q:a", options.vorbisQuality.toString())
            }
            encoding.codec == "flac" -> {
                require(options.compression in 0..12)
                listOf("-compression_level", options.compression.toString())
            }
            else -> emptyList()
        }
        if (options.format == AudioFormat.WAV) require(options.bits in listOf(16, 24, 32))
        val muxer = if (encoding.container == "3gp" || encoding.container == "mp4")
            listOf("-f", encoding.container) else emptyList()
        MediaProcessing.execute(
            listOf("-protocol_whitelist", "file,pipe", "-threads", "2", "-i", frame.media.file.path,
                "-map", "0:a:0", "-vn", "-map_metadata", "-1", "-c:a", checkNotNull(encoding.encoder)) +
                sampleRate + quality + muxer + listOf("-threads", "2", target.path),
        ) { time ->
            progress(frame.media.durationMs.takeIf { it > 0 }?.let { (time.toFloat() / it).coerceIn(0f, 1f) })
        }
        AudioFiles.writeTags(target, frame.metadata.tags, frame.metadata.pictures)
        currentCoroutineContext().ensureActive()
        return target
    } catch (error: Throwable) {
        target.delete()
        throw error
    }
}
