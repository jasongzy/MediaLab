package com.jasongzy.medialab

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.net.Uri
import androidx.compose.runtime.*
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

internal data class VideoAudioTrack(
    val index: Long,
    val codec: String,
)

internal data class VideoMetadata(
    val streamIndex: Long?,
    val entries: List<Pair<String, String>>,
)

internal data class VideoTag(val streamIndex: Long?, val key: String)

internal data class VideoInfo(
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val fps: Double,
    val bitrate: Long,
    val codec: String,
    val audioTracks: List<VideoAudioTrack>,
    val format: String,
    val hdr: Boolean,
    val color: String,
    val videoIndex: Long,
    val metadata: List<VideoMetadata>,
    val hasCoverArt: Boolean = false,
    val frameCount: Long? = null,
    val videoStartMs: Long = 0,
    val videoEndMs: Long = durationMs,
    val inputOffsetMs: Long = 0,
) {
    val size
        get() = IntSize(width, height)

    val exportFormat get() = VideoFormat.entries.firstOrNull { it.extension == format }

    fun descriptiveMetadata(clear: Boolean): List<VideoMetadata> =
        if (clear) emptyList()
        else metadata.mapNotNull { group ->
            if (group.streamIndex != null && group.streamIndex != videoIndex &&
                audioTracks.none { it.index == group.streamIndex }) return@mapNotNull null
            val entries = group.entries.filterNot { (key, _) ->
                val tag = key.lowercase(Locale.ROOT)
                tag.startsWith("_statistics_") || tag.substringBefore('-') in setOf(
                    "major_brand", "minor_version", "compatible_brands", "encoder", "handler_name",
                    "vendor_id", "duration", "bps", "number_of_frames", "number_of_bytes", "rotate",
                )
            }
            group.copy(entries = entries).takeIf { entries.isNotEmpty() }
        }

    fun metadata(edits: VideoEdits): List<VideoMetadata> =
        descriptiveMetadata(edits.clearMetadata).mapNotNull { group ->
            group.copy(entries = group.entries.mapNotNull { (key, value) ->
                val tag = VideoTag(group.streamIndex, key)
                (if (tag in edits.metadataEdits) edits.metadataEdits[tag] else value)?.let { key to it }
            }).takeIf { it.entries.isNotEmpty() }
        }

    fun containerTags(edits: VideoEdits) = metadata(edits).firstOrNull { it.streamIndex == null }?.entries.orEmpty()

    fun editMetadata(edits: VideoEdits, tag: VideoTag, value: String?): VideoEdits {
        val original = requireNotNull(descriptiveMetadata(edits.clearMetadata)
            .firstOrNull { it.streamIndex == tag.streamIndex }?.entries?.firstOrNull { it.first == tag.key }).second
        val next = if (value == original) original else Videos.normalizeMetadata(tag, value)
        val changes = if (next == original) edits.metadataEdits - tag else edits.metadataEdits + (tag to next)
        return edits.copy(metadataEdits = changes)
    }

    fun edits(gifFrameTimes: LongArray = longArrayOf()): VideoEdits {
        val format = exportFormat ?: VideoFormat.MP4
        val rate = if (gifFrameTimes.size > 1) {
            // Use the lowest integer frame rate that can represent every GIF frame boundary.
            tailrec fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)
            (1000 / gifFrameTimes.fold(gcd(1000, durationMs), ::gcd)).toInt()
        } else fps.roundToInt()
        return VideoEdits(
            durationMs,
            IntRect(0, 0, width, height),
            IntRect(0, 0, width, height),
            bitrateKbps = (bitrate / 1000).coerceIn(500, 200000).toInt(),
            fps = rate.coerceIn(1, format.maxFps),
            format = format,
        )
    }
}

internal data class VideoPoster(val file: File, val positionMs: Long, val bitmap: Bitmap)

internal class VideoDocument(
    val file: File,
    val name: String,
    val sourceUri: Uri?,
    val info: VideoInfo,
    val playable: File,
    val gifFrameTimes: LongArray = longArrayOf(),
) {
    val editor = VideoEditor(info.edits(gifFrameTimes))
    val sourceMime get() = MediaFiles.mime(info.format, ImportType.VIDEO).takeUnless { it == "application/octet-stream" } ?: "video/*"
    val previews = VideoPreviewCache()
    var selectedAudioTrack by mutableStateOf(info.audioTracks.firstOrNull()?.index)
    var audioTracksExpanded by mutableStateOf(false)
    var appliedPreview by mutableStateOf<File?>(null)
    var appliedInfo by mutableStateOf<VideoInfo?>(null)
    var previewFile by mutableStateOf<File?>(null)
    var previewEdits by mutableStateOf<VideoEdits?>(null)
    var poster by mutableStateOf<VideoPoster?>(null)
    val playbackFile
        get() = appliedPreview ?: playable

    fun framePosition(positionMs: Long): Pair<Long, Long> {
        val preview = appliedInfo
        if (preview == null && gifFrameTimes.isNotEmpty()) {
            val index = gifFrameTimes.binarySearch(positionMs)
            val frame = if (index >= 0) index else (-index - 2).coerceAtLeast(0)
            return frame + 1L to gifFrameTimes.size.toLong()
        }
        val track = preview ?: info
        val fps = if (preview == null) info.fps else editor.history.value.previewFps.toDouble()
        val duration = track.videoEndMs - track.videoStartMs
        val total = track.frameCount ?: ceil(duration * fps / 1000).toLong().coerceAtLeast(1)
        val elapsed = (positionMs - track.videoStartMs).coerceAtLeast(0)
        val frame = if (elapsed >= duration) total
            else (elapsed * fps / 1000).toLong().coerceAtMost(total - 1) + 1
        return frame to total
    }

    val workingFiles
        get() = setOfNotNull(file, playable, appliedPreview, previewFile?.takeIf { editor.showPreview })

    val files
        get() = workingFiles + previews.files + listOfNotNull(previewFile)

    val needsReplacementConfirmation
        get() = editor.history.hasHistory || editor.output != editor.original ||
            !editor.inputValid || editor.pending.isNotEmpty()
}

internal object Videos {
    // The MOV and MP4 muxers have different native tag sets; other keys need an mdta table.
    private val commonMovieTags = setOf("artist", "title", "album", "date", "comment", "genre", "copyright",
        "keywords")
    private val nativeMovieTags = mapOf(
        VideoFormat.MOV to commonMovieTags + setOf("author", "make", "model", "location", "xmp"),
        VideoFormat.MP4 to commonMovieTags + setOf("album_artist", "composer", "grouping", "lyrics",
            "description", "synopsis", "show", "episode_id", "network", "episode_sort", "season_number", "media_type",
            "hd_video", "gapless_playback", "compilation", "track", "disc"),
    )

    fun normalizeMetadata(tag: VideoTag, value: String?, error: Int = R.string.invalid_metadata): String? {
        if (value == null) return null
        if (value.length > 32768 || '\u0000' in value)
            throw UnsupportedMediaException(error)
        return when (tag.key.lowercase(Locale.ROOT)) {
            "creation_time" -> try {
                java.time.OffsetDateTime.parse(value).toInstant().toString()
            } catch (_: java.time.DateTimeException) {
                throw UnsupportedMediaException(error)
            }
            "language" -> if (tag.streamIndex == null) value else value.lowercase(Locale.ROOT).also {
                if (!it.matches(Regex("[a-z]{3}(-[a-z]{2})?"))) throw UnsupportedMediaException(error)
            }.takeUnless { it == "und" }
            else -> value
        }
    }

    private fun needsKeyedTag(key: String, value: String, nativeTags: Set<String>): Boolean {
        val tag = key.lowercase(Locale.ROOT)
        if (tag == "creation_time") return try {
            val date = java.time.OffsetDateTime.parse(value).toInstant()
            // The muxer uses whole seconds since 1904 and treats Unix time zero as unset.
            date.nano != 0 || date.epochSecond !in 1..(0xffff_ffffL - 2_082_844_800L)
        } catch (_: java.time.DateTimeException) {
            true
        }
        if (tag !in nativeTags) return true
        fun nativeInteger(text: String, range: IntRange) =
            text.toIntOrNull()?.takeIf { it in range }?.toString() == text
        return when (tag) {
            "track", "disc" -> value.split('/', limit = 3).let { parts ->
                parts.size > 2 || parts.any { !nativeInteger(it, 1..Short.MAX_VALUE.toInt()) }
            }
            "episode_sort", "season_number", "media_type", "hd_video", "gapless_playback", "compilation" ->
                !nativeInteger(value, 0..255)
            else -> false
        }
    }

    private suspend fun verifyOutput(source: VideoInfo, edits: VideoEdits, file: File): VideoInfo {
        val result = inspect(file)
        if (edits.format in nativeMovieTags) {
            val written = result.metadata.firstOrNull { it.streamIndex == null }?.entries.orEmpty()
                .associate { it.first.lowercase(Locale.ROOT) to it.second }
            for ((key, value) in source.containerTags(edits)) {
                val actual = written[key.lowercase(Locale.ROOT)] ?: throw UnsupportedMediaException(R.string.metadata_not_saved)
                if (actual != value) {
                    val tag = VideoTag(null, key)
                    if (normalizeMetadata(tag, actual, R.string.metadata_not_saved) != normalizeMetadata(tag, value, R.string.metadata_not_saved))
                        throw UnsupportedMediaException(R.string.metadata_not_saved)
                }
            }
        }
        for ((tag, expected) in edits.metadataEdits) {
            val stream = when (tag.streamIndex) {
                null -> null
                source.videoIndex -> result.videoIndex
                else -> {
                    if (edits.mute || edits.format == VideoFormat.GIF) continue
                    val index = source.audioTracks.indexOfFirst { it.index == tag.streamIndex }
                    result.audioTracks.getOrNull(index)?.index ?: throw UnsupportedMediaException(R.string.metadata_not_saved)
                }
            }
            val actual = result.metadata.firstOrNull { it.streamIndex == stream }?.entries
                ?.firstOrNull { it.first.equals(tag.key, ignoreCase = true) }?.second
            val normalized = normalizeMetadata(tag, actual, R.string.metadata_not_saved)
            if (normalized != expected) throw UnsupportedMediaException(R.string.metadata_not_saved)
        }
        return result
    }
    private val protocols = listOf("-protocol_whitelist", "file,pipe")
    private val matroskaDuration = Regex("""(\d+):([0-5]\d):([0-5]\d(?:\.\d+)?)""")
    private val vp9 = listOf("-c:v", "libvpx-vp9", "-deadline", "realtime", "-cpu-used", "6", "-row-mt", "1")
    private const val yuv420 = "pad=ceil(iw/2)*2:ceil(ih/2)*2:color=black,scale=out_color_matrix=bt709:out_range=tv,format=yuv420p"

    suspend fun inspect(file: File): VideoInfo {
        val info = MediaProcessing.inspect(file, "mov,matroska,webm,avi,gif,mpeg,mpegts,flv,asf,ogg")
            ?: throw UnsupportedMediaException(R.string.select_video_only)
        val stream = info.videoTrack ?: throw UnsupportedMediaException(R.string.select_video_only)
        val properties = stream.allProperties
        val sideData = properties.optJSONArray("side_data_list")
        var rotation = 0
        if (sideData != null)
            for (i in 0 until sideData.length()) {
                rotation = sideData.getJSONObject(i).optInt("rotation", rotation)
            }
        fun fraction(value: String?, separator: Char): Double {
            val parts = value.orEmpty().split(separator)
            val first = parts.firstOrNull()?.toDoubleOrNull() ?: return 0.0
            return if (parts.size == 2) first / (parts[1].toDoubleOrNull() ?: 0.0) else first
        }
        val sar = fraction(stream.sampleAspectRatio, ':').takeIf { it.isFinite() && it > 0 } ?: 1.0
        val rawWidth = ((stream.width ?: 0) * sar).roundToInt()
        val rawHeight = (stream.height ?: 0).toInt()
        val swapped = abs(rotation % 180) == 90
        val width = if (swapped) rawHeight else rawWidth
        val height = if (swapped) rawWidth else rawHeight
        val transfer = properties.optString("color_transfer")
        val format = when (val container = info.container()) {
            "m4a" -> "mp4"
            "matroska" -> matroskaFormat(file)
            else -> container
        }
        val inputStart = info.startTime?.toDoubleOrNull() ?: 0.0
        // Matroska playback uses segment timestamps; FFmpeg rebases input to its first packet.
        val matroska = format == "mkv" || format == "webm"
        val origin = if (matroska) 0.0 else inputStart
        val start = properties.optDouble("start_time", inputStart) - origin
        val end = properties.optDouble("duration").takeIf { it > 0 }?.let { start + it }
            ?: if (matroska)
                // Matroska writes the track end timestamp in DURATION.
                matroskaDuration.matchEntire(stream.tags?.optString("DURATION").orEmpty())?.destructured?.let { (h, m, s) ->
                    h.toDouble() * 3600 + m.toInt() * 60 + s.toDouble()
                } else null
        val duration = mediaMilliseconds(info.duration?.toDoubleOrNull()?.takeIf { it > 0 } ?: end ?: 0.0)
        if (width <= 0 || height <= 0 || duration <= 0)
            throw UnsupportedMediaException(R.string.select_video_only)
        val startMs = mediaMilliseconds(start).coerceIn(0, duration - 1)
        return VideoInfo(
            width,
            height,
            duration,
            listOf(stream.averageFrameRate, properties.optString("r_frame_rate"))
                .map { fraction(it, '/') }
                .firstOrNull { it.isFinite() && it > 0 } ?: 30.0,
            info.bitrate?.toLongOrNull() ?: 6_000_000,
            stream.codec ?: "?",
            info.streams
                .filter { it.type == "audio" }
                .map {
                    VideoAudioTrack(
                        checkNotNull(it.index),
                        it.codec ?: "?",
                    )
                },
            format,
            transfer in listOf("smpte2084", "arib-std-b67"),
            listOf(
                    properties.optString("color_space"),
                    properties.optString("color_primaries"),
                    transfer,
                )
                .filter { it.isNotEmpty() && it != "unknown" }
                .distinct()
                .joinToString(" · "),
            checkNotNull(stream.index),
            buildList {
                fun addTags(index: Long?, tags: JSONObject?) {
                    if (tags == null) return
                    val entries =
                        tags
                            .keys()
                            .asSequence()
                            .sorted()
                            .mapNotNull { key ->
                                if (tags.isNull(key)) null
                                else
                                    tags
                                        .optString(key)
                                        .takeIf { it.isNotBlank() }
                                        ?.let { key to it }
                            }
                            .toList()
                    if (entries.isNotEmpty()) add(VideoMetadata(index, entries))
                }
                addTags(null, info.tags)
                info.streams.forEach { addTags(it.index, it.allProperties.optJSONObject("tags")) }
            },
            info.streams.any { it.allProperties.optJSONObject("disposition")?.optInt("attached_pic") == 1 },
            frameCount = properties.optLong("nb_frames").takeIf { it > 0 },
            inputOffsetMs = mediaMilliseconds(inputStart - origin),
            videoStartMs = startMs,
            videoEndMs = end?.takeIf { it > start }?.let {
                mediaMilliseconds(it).coerceIn(startMs + 1, duration)
            } ?: duration,
        )
    }

    // FFprobe shares a demuxer for Matroska and WebM. Only the EBML DocType identifies the container.
    private fun matroskaFormat(file: File): String = RandomAccessFile(file, "r").use { input ->
        fun vint(keepMarker: Boolean): Long {
            val first = input.read()
            if (first <= 0) return -1
            val marker = Integer.highestOneBit(first)
            var value = (if (keepMarker) first else first xor marker).toLong()
            repeat(7 - marker.countTrailingZeroBits()) {
                val byte = input.read()
                if (byte < 0) return -1
                value = (value shl 8) or byte.toLong()
            }
            return value
        }
        if (vint(true) != 0x1A45DFA3L) return@use "mkv"
        val headerSize = vint(false)
        if (headerSize !in 1..(input.length() - input.filePointer)) return@use "mkv"
        val end = input.filePointer + headerSize
        while (input.filePointer < end) {
            val id = vint(true)
            val size = vint(false)
            if (id < 0 || size !in 0..(end - input.filePointer)) break
            if (id == 0x4282L) {
                if (size >= 4) {
                    val type = ByteArray(4).also(input::readFully).toString(Charsets.US_ASCII)
                    if (type == "webm" && (size == 4L || input.read() == 0)) return@use "webm"
                }
                break
            }
            input.seek(input.filePointer + size)
        }
        "mkv"
    }

    private fun input(file: File, info: VideoInfo? = null, startMs: Long = 0): List<String> {
        val offset = info?.inputOffsetMs ?: 0L
        val timing = when {
            startMs > 0 && startMs > offset -> listOf("-ss", mediaSeconds(startMs - offset), "-noaccurate_seek")
            startMs != offset -> listOf("-itsoffset", mediaSeconds(offset - startMs))
            else -> emptyList()
        }
        return timing + protocols + listOf("-threads", "2", "-i", file.path)
    }

    private fun audioFilter(steps: List<VideoEdits>) =
        steps.joinToString(",") { step ->
            val start = mediaSeconds(step.startMs)
            "atrim=start=$start:end=${mediaSeconds(step.endMs)},asetpts=PTS-$start/TB," +
                "aresample=async=1:first_pts=0," + step.time.audioOutputFilter()
        }

    private fun renderSteps(info: VideoInfo, edits: VideoEdits): List<VideoEdits> =
        edits.steps
            .dropWhile { it.isIdentity(info.size, info.durationMs) }
            .ifEmpty { listOf(edits.copy(previous = emptyList())) }

    @Suppress(
        "DEPRECATION"
    ) // FFmpeg supplies planar buffers, rather than Android's flexible YUV layout.
    private fun h264Encoder(edits: VideoEdits): String {
        val size = evenVideoSize(edits.size)
        val format =
            MediaFormat.createVideoFormat("video/avc", size.width, size.height).apply {
                setInteger(MediaFormat.KEY_FRAME_RATE, edits.fps)
                setInteger(MediaFormat.KEY_BIT_RATE, edits.bitrateKbps * 1000)
            }
        return MediaCodecList(MediaCodecList.REGULAR_CODECS)
            .codecInfos
            .filter { it.isEncoder && "video/avc" in it.supportedTypes }
            .sortedBy { it.isSoftwareOnly }
            .firstOrNull {
                val caps = it.getCapabilitiesForType("video/avc")
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar in caps.colorFormats &&
                    caps.isFormatSupported(format)
            }
            ?.name ?: throw UnsupportedMediaException(R.string.video_encoder_unsupported)
    }

    private fun sourceFilters(info: VideoInfo): List<String> = buildList {
        if (info.hdr)
            add(
                "zscale=t=linear:npl=100,format=gbrpf32le,tonemap=hable,zscale=p=bt709:t=bt709:m=bt709:r=limited"
            )
        add("scale=${info.width}:${info.height}:flags=lanczos,setsar=1,format=rgba")
    }

    private fun colorCode(color: Int) = String.format(Locale.ROOT, "0x%06X", color and 0xffffff)

    private fun spatialFilters(edits: VideoEdits, inputSize: IntSize): List<String> = buildList {
        val crop = edits.crop
        if (crop != IntRect(0, 0, inputSize.width, inputSize.height))
            add("crop=${crop.width}:${crop.height}:${crop.left}:${crop.top}:exact=1")
        val canvas = edits.canvas
        if (canvas != IntRect(0, 0, crop.width, crop.height))
            add(
                "pad=${canvas.width}:${canvas.height}:${-canvas.left}:${-canvas.top}:color=${colorCode(edits.background)}"
            )
        val rotation = edits.rotation.normalized()
        when (rotation.quarterTurns) {
            1 -> add("transpose=clock")
            2 -> add("hflip,vflip")
            3 -> add("transpose=cclock")
        }
        if (rotation.flipHorizontal) add("hflip")
        if (edits.size != rotation.size(IntSize(canvas.width, canvas.height)))
            add("scale=${edits.size.width}:${edits.size.height}:flags=lanczos,setsar=1")
    }

    private suspend fun coverFilter(
        cover: VideoCover,
        size: IntSize,
        directory: File,
        temporary: MutableList<File>,
        index: Int,
        frames: LongRange? = null,
    ): String? {
        if (cover.strokes.isEmpty()) return null
        val left =
            cover.strokes.minOf { s ->
                s.points.minOf { it.first } - if (s.fill) 1f else s.width / 2 + 1
            }
        val top =
            cover.strokes.minOf { s ->
                s.points.minOf { it.second } - if (s.fill) 1f else s.width / 2 + 1
            }
        val right =
            cover.strokes.maxOf { s ->
                s.points.maxOf { it.first } + if (s.fill) 1f else s.width / 2 + 1
            }
        val bottom =
            cover.strokes.maxOf { s ->
                s.points.maxOf { it.second } + if (s.fill) 1f else s.width / 2 + 1
            }
        val bounds =
            Rect(
                floor(left).toInt().coerceIn(0, size.width),
                floor(top).toInt().coerceIn(0, size.height),
                ceil(right).toInt().coerceIn(0, size.width),
                ceil(bottom).toInt().coerceIn(0, size.height),
            )
        if (bounds.isEmpty) return null
        currentCoroutineContext().ensureActive()
        val file = MediaFiles.temporary(directory, "png")
        temporary += file
        if (bounds.width().toLong() * bounds.height() * 8 >= Runtime.getRuntime().maxMemory() * 0.6)
            throw OutOfMemoryError("Cover exceeds the image memory budget")
        val bitmap = Bitmap.createBitmap(bounds.width(), bounds.height(), Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.translate(-bounds.left.toFloat(), -bounds.top.toFloat())
            Photos.cover(canvas, cover.strokes)
            currentCoroutineContext().ensureActive()
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
        return "null[base$index];movie=filename=${file.path}:dec_threads=1[cover$index];" +
            "[base$index][cover$index]overlay=x=${bounds.left}:y=${bounds.top}:format=auto" +
            if (frames != null) ":enable='between(n,${frames.first},${frames.last})'" else ""
    }

    private fun metadataOptions(
        info: VideoInfo,
        edits: VideoEdits,
        inputIndex: Int = 0,
        inheritStreamTags: Boolean = false,
    ): List<String> {
        val keepChapters = !edits.clearMetadata && edits.format != VideoFormat.GIF &&
            edits.steps.all { it.time == MediaTimeEdit(info.durationMs) }
        val nativeTags = nativeMovieTags[edits.format]
        // FFmpeg writes attached MP4 artwork only with its native tags.
        val keyedTags = if (nativeTags != null && !(inheritStreamTags && info.hasCoverArt))
            info.containerTags(edits).takeIf { tags -> tags.any { (key, value) -> needsKeyedTag(key, value, nativeTags) } }
        else null
        val args = mutableListOf("-map_metadata", if (edits.clearMetadata || keyedTags != null) "-1" else inputIndex.toString(),
            "-map_chapters", if (keepChapters) inputIndex.toString() else "-1")
        if (keyedTags != null) {
            args += listOf("-movflags", "+use_metadata_tags")
            keyedTags.forEach { (key, value) -> args += listOf("-metadata", "$key=$value") }
        }
        if (edits.clearMetadata) args += listOf("-map_metadata:s", "-1", "-metadata", "encoder=", "-metadata:s", "encoder=")
        val streams = buildMap {
            put(info.videoIndex, "V:0")
            if (!edits.mute && edits.format != VideoFormat.GIF)
                info.audioTracks.forEachIndexed { index, track -> put(track.index, "a:$index") }
        }
        if (!edits.clearMetadata && !inheritStreamTags) streams.forEach { (source, target) ->
            args += listOf("-map_metadata:s:$target", "$inputIndex:s:$source")
        }
        for ((tag, value) in edits.metadataEdits) {
            if (keyedTags != null && tag.streamIndex == null) continue
            val option = if (tag.streamIndex == null) "-metadata" else "-metadata:s:${streams[tag.streamIndex] ?: continue}"
            args += listOf(option, "${tag.key}=${value.orEmpty()}")
        }
        return args
    }

    fun canCopy(info: VideoInfo, edits: VideoEdits) =
        edits.format != VideoFormat.GIF &&
            (info.exportFormat == edits.format || when (edits.format) {
                VideoFormat.MP4 -> info.codec in setOf("h264", "hevc", "av1", "vp9", "mpeg4")
                VideoFormat.MOV -> info.codec in setOf("h264", "hevc", "prores", "mjpeg", "mpeg4")
                VideoFormat.WEBM -> info.codec in setOf("vp8", "vp9", "av1")
                VideoFormat.MKV -> info.codec in setOf("h264", "hevc", "av1", "vp8", "vp9", "mpeg4", "mpeg2video", "ffv1", "prores", "mjpeg", "theora")
                VideoFormat.GIF -> false
            }) &&
            edits.fps == info.edits().fps &&
            edits.bitrateKbps == info.edits().bitrateKbps &&
            edits.steps.all { it.isIdentity(info.size, info.durationMs) }

    private fun remuxStreams(info: VideoInfo, edits: VideoEdits): List<String> = buildList {
        if (info.exportFormat == edits.format) {
            if (edits.clearMetadata) addAll(listOf("-map", "0:V", "-map", "0:a?", "-map", "0:s?"))
            else addAll(listOf("-map", "0"))
            if (edits.mute) addAll(listOf("-map", "-0:a?"))
            addAll(listOf("-c", "copy"))
            return@buildList
        }
        addAll(listOf("-map", "0:${info.videoIndex}", "-c:v", "copy"))
        if (!edits.mute) info.audioTracks.forEachIndexed { index, track ->
            val compatible = when (edits.format) {
                VideoFormat.MP4 -> track.codec in setOf("aac", "mp3", "ac3", "eac3", "alac", "opus")
                VideoFormat.MOV -> track.codec in setOf("aac", "mp3", "ac3", "eac3", "alac", "pcm_s16le", "pcm_s24le", "pcm_s32le")
                VideoFormat.WEBM -> track.codec in setOf("opus", "vorbis")
                VideoFormat.MKV -> track.codec in setOf("aac", "mp3", "flac", "opus", "vorbis", "ac3", "eac3", "dts", "alac", "pcm_s16le", "pcm_s24le", "pcm_s32le")
                VideoFormat.GIF -> false
            }
            addAll(listOf("-map", "0:${track.index}", "-c:a:$index",
                if (compatible) "copy" else if (edits.format == VideoFormat.WEBM) "libopus" else "aac"))
        }
    }

    suspend fun export(
        file: File,
        info: VideoInfo,
        edits: VideoEdits,
        target: File,
        stage: (Int) -> Unit = {},
        progress: (Long) -> Unit = {},
    ): VideoInfo {
        if (canCopy(info, edits)) {
            try {
                if (edits.format == info.exportFormat && !edits.mute && !edits.clearMetadata && edits.metadataEdits.isEmpty()) {
                    val bytes = file.length()
                    file.inputStream().use { input ->
                        target.outputStream().use { output ->
                            MediaFiles.copy(input, output, bytes) { progress((it.toDouble() / bytes * info.durationMs).toLong()) }
                        }
                    }
                    return info
                }
                MediaProcessing.execute(
                    input(file, info) + remuxStreams(info, edits) +
                        metadataOptions(info, edits, inheritStreamTags = info.exportFormat == edits.format) + target.path,
                    progress,
                )
                return verifyOutput(info, edits, target)
            } catch (error: Throwable) {
                target.delete()
                throw error
            }
        }
        edits.validate(info.size, info.durationMs)
        val temporary = mutableListOf<File>()
        try {
            val steps = renderSteps(info, edits)
            val frames = videoFrameRanges(steps, edits.fps)
            // Hold the last picture through longer audio; the first trim bounds the output.
            val filters =
                mutableListOf(
                    "tpad=stop_mode=clone:stop=-1,fps=${edits.fps}:start_time=0:round=up:eof_action=pass,settb=AVTB"
                )
            steps.forEachIndexed { index, step ->
                val range = frames[index]
                filters +=
                    "trim=start_frame=${range.start}:end_frame=${range.start + range.count},setpts=PTS-STARTPTS"
                if (index == 0) filters += sourceFilters(info)
                step.cover?.let { cover ->
                    coverFilter(
                            cover,
                            if (index == 0) info.size else steps[index - 1].size,
                            checkNotNull(target.parentFile),
                            temporary,
                            index,
                            cover.frames(edits.fps),
                        )
                        ?.let { filters += it }
                }
                filters +=
                    spatialFilters(step, if (index == 0) info.size else steps[index - 1].size)
                if (step.speedPercent != 100)
                    filters +=
                        "tpad=stop_mode=clone:stop=1,settb=1/(${edits.fps}*${step.speedPercent}),setpts=N*100," +
                            "fps=${edits.fps}:start_time=0:round=up,settb=AVTB," +
                            "trim=end_frame=${frames[index].outputCount}"
                if (step.beforeMs > 0 || step.afterMs > 0)
                    filters +=
                        "tpad=start_duration=${mediaSeconds(step.beforeMs)}:stop_duration=${mediaSeconds(step.afterMs)}:" +
                            if (step.holdFrames) "start_mode=clone:stop_mode=clone"
                            else "start_mode=add:stop_mode=add:color=${colorCode(step.timeColor)}"
            }
            val args = input(file, info, steps.first().startMs).toMutableList()
            // MediaCodec supplies headers after encoding; Matroska needs them at muxer startup.
            val encoded =
                if (edits.format == VideoFormat.MKV)
                    MediaFiles.temporary(checkNotNull(target.parentFile), "mp4")
                else target
            val palette =
                if (edits.format == VideoFormat.GIF)
                    MediaFiles.temporary(checkNotNull(target.parentFile), "png")
                else null
            if (edits.format == VideoFormat.GIF) {
                args += input(checkNotNull(palette))
                args +=
                    listOf(
                        "-filter_complex",
                        "[0:${info.videoIndex}]" +
                            filters.joinToString(",") +
                            "[v];[v][1:v]paletteuse=dither=sierra2_4a",
                        "-an",
                        "-loop",
                        if (edits.gifLoop) "0" else "-1",
                    )
            } else {
                val withAudio = !edits.mute && info.audioTracks.isNotEmpty()
                // Seeking by video keyframe can skip the audio packet that spans the cut.
                val audioInput = if (withAudio && steps.first().startMs > 0) {
                    args += input(file, info)
                    1
                } else 0
                // 4:2:0 encoders require even dimensions; pad instead of stretching or trimming
                // pixels.
                filters += yuv420
                args += listOf("-map", "0:${info.videoIndex}", "-vf", filters.joinToString(","))
                args +=
                    if (edits.format == VideoFormat.WEBM) vp9
                    else
                        listOf(
                            "-c:v",
                            "h264_mediacodec",
                            "-codec_name",
                            h264Encoder(edits),
                            "-ndk_codec",
                            "1",
                            "-ndk_async",
                            "1",
                        )
                args +=
                    listOf(
                        "-b:v",
                        "${edits.bitrateKbps}k",
                        "-threads",
                        "2",
                        "-colorspace",
                        "bt709",
                        "-color_range",
                        "tv",
                    )
                if (withAudio) {
                    info.audioTracks.forEach { args += listOf("-map", "$audioInput:${it.index}") }
                    args +=
                        listOf(
                            "-af",
                            audioFilter(steps),
                            "-c:a",
                            if (edits.format == VideoFormat.WEBM) "libopus" else "aac",
                            "-b:a",
                            "192k",
                        )
                } else args += "-an"
                if (edits.format in listOf(VideoFormat.MP4, VideoFormat.MOV))
                    args += listOf("-movflags", "+faststart")
            }
            args += listOf("-t", mediaSeconds(edits.durationMs))
            args += if (encoded == target) metadataOptions(info, edits) else listOf("-map_chapters", "-1")
            args += encoded.path
            try {
                // Two passes keep palette generation from buffering the entire video.
                if (palette != null) {
                    stage(R.string.analyzing_gif_colors)
                    MediaProcessing.execute(
                        input(file, info, steps.first().startMs) +
                            listOf(
                                "-map",
                                "0:${info.videoIndex}",
                                "-vf",
                                filters.joinToString(",") +
                                    ",palettegen=max_colors=${edits.gifColors}",
                                "-frames:v",
                                "1",
                                "-threads",
                                "1",
                                palette.path,
                            )
                    )
                }
                stage(if (palette == null) R.string.preparing_video else R.string.encoding_gif)
                progress(0)
                MediaProcessing.execute(args, progress)
                if (encoded != target) {
                    // Matroska supports tags that the intermediate MP4 cannot carry.
                    MediaProcessing.execute(input(encoded) + input(file) + listOf("-map", "0", "-c", "copy") +
                        metadataOptions(info, edits, inputIndex = 1) + target.path)
                }
                return verifyOutput(info, edits, target)
            } catch (error: Throwable) {
                target.delete()
                throw error
            } finally {
                palette?.delete()
                if (encoded != target) encoded.delete()
            }
        } finally {
            temporary.forEach { it.delete() }
        }
    }

    suspend fun preview(
        file: File,
        info: VideoInfo,
        edits: VideoEdits,
        work: File,
        progress: (Long) -> Unit,
    ): VideoPreviewCache.Preview {
        val scale = minOf(1.0, 1280.0 / maxOf(edits.size.width, edits.size.height))
        val options =
            edits.copy(
                format = VideoFormat.MP4,
                width = (edits.size.width * scale).roundToInt().coerceAtLeast(1),
                height = (edits.size.height * scale).roundToInt().coerceAtLeast(1),
                fps = edits.previewFps,
                bitrateKbps = 3000,
                clearMetadata = true,
                metadataEdits = emptyMap(),
            )
        var target = MediaFiles.temporary(work, "mp4")
        try {
            val rendered = try {
                export(file, info, options, target, progress = progress)
            } catch (error: UnsupportedMediaException) {
                if (error.messageRes != R.string.video_encoder_unsupported) throw error
                target.delete()
                target = MediaFiles.temporary(work, "webm")
                export(
                    file,
                    info,
                    options.copy(format = VideoFormat.WEBM),
                    target,
                    progress = progress,
                )
            }
            return VideoPreviewCache.Preview(target, rendered)
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    suspend fun frame(
        file: File,
        info: VideoInfo,
        timeMs: Long,
        target: File,
        edits: VideoEdits,
    ) {
        if (!edits.canExtractFrame) throw UnsupportedMediaException(R.string.video_frame_size_limit)
        val temporary = mutableListOf<File>()
        try {
            val steps = renderSteps(info, edits)
            val activeCovers = mutableSetOf<Int>()
            val (positionUs, padding) =
                if (steps.singleOrNull()?.isIdentity(info.size, info.durationMs) == true)
                    timeMs * 1000 to -1
                else videoFrameSource(steps, timeMs, edits.fps, activeCovers)
            val filters = if (padding < 0) sourceFilters(info).toMutableList() else mutableListOf()
            for (index in (padding + 1)..steps.lastIndex) {
                val size = if (index == 0) info.size else steps[index - 1].size
                if (index in activeCovers)
                    coverFilter(
                            checkNotNull(steps[index].cover),
                            size,
                            checkNotNull(target.parentFile),
                            temporary,
                            index,
                        )
                        ?.let { filters += it }
                filters += spatialFilters(steps[index], size)
            }
            // Whole-second seeking avoids rounding past frames in coarse input time bases.
            val seekUs = positionUs.coerceIn(info.videoStartMs * 1000, info.videoEndMs * 1000 - 1)
            // Shift the selected instant to zero, then keep its displayed frame before spatial
            // edits.
            filters.add(
                0,
                "tpad=stop_mode=clone:stop=1,settb=AVTB,setpts=PTS-${seekUs % 1_000_000}," +
                    "fps=1:start_time=0:round=up,trim=end_frame=1",
            )
            try {
                val source =
                    if (padding >= 0)
                        listOf(
                            "-f",
                            "lavfi",
                            "-i",
                            steps[padding].let {
                                "color=c=${colorCode(it.timeColor)}:s=${it.size.width}x${it.size.height},format=rgba"
                            },
                            "-vf",
                            filters.joinToString(","),
                        )
                    else
                        input(file, info, seekUs / 1_000_000 * 1000) +
                            listOf(
                                "-map",
                                "0:${info.videoIndex}",
                                "-vf",
                                filters.joinToString(","),
                            )
                MediaProcessing.execute(
                    source +
                        listOf(
                            "-frames:v",
                            "1",
                            "-threads",
                            "1",
                            target.path,
                        )
                )
                check(target.length() > 0) { "No frame at the selected position" }
            } catch (error: Throwable) {
                target.delete()
                throw error
            }
        } finally {
            temporary.forEach { it.delete() }
        }
    }

    suspend fun audio(
        file: File,
        info: VideoInfo,
        edits: VideoEdits,
        track: Long,
        target: File,
    ) {
        require(info.audioTracks.any { it.index == track })
        val steps = renderSteps(info, edits)
        steps.fold(info.durationMs) { duration, step ->
            step.time.validate(duration)
            step.durationMs
        }
        // FLAC stores file-level tags. The selected track takes precedence over the container.
        val groups = info.metadata(edits)
        val tags = groups.firstOrNull { it.streamIndex == null }?.entries.orEmpty() +
            groups.firstOrNull { it.streamIndex == track }?.entries.orEmpty()
        try {
            MediaProcessing.execute(
                input(file, info) +
                    listOf(
                        "-map",
                        "0:$track",
                        "-af",
                        audioFilter(steps),
                        "-vn",
                        "-t",
                        mediaSeconds(edits.durationMs),
                        "-c:a",
                        "flac",
                        "-map_metadata", "-1", "-map_metadata:s", "-1", "-map_chapters", "-1",
                    ) +
                    tags.flatMap { (key, value) -> listOf("-metadata", "$key=$value") } +
                    target.path
            )
            check(target.length() > 0) { "Empty audio output" }
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    suspend fun read(
        file: File,
        name: String,
        uri: Uri?,
        work: File,
        info: VideoInfo,
    ): VideoDocument {
        if (info.format != "gif") return VideoDocument(file, name, uri, info, file)
        val playable = MediaFiles.temporary(work, "webm")
        val timestamps = MediaFiles.temporary(work, "txt")
        try {
            val scale = minOf(1.0, 960.0 / maxOf(info.width, info.height))
            val width = (info.width * scale).roundToInt().coerceAtLeast(1)
            val height = (info.height * scale).roundToInt().coerceAtLeast(1)
            val track = listOf("-map", "0:${info.videoIndex}")
            // VP9 can discard GIF packet durations. Keep the final frame until the GIF ends.
            val durations = "setts=duration='if(gt(NEXT_PTS,PTS),NEXT_PTS-PTS,round(${mediaSeconds(info.durationMs)}/TB)-PTS)'"
            MediaProcessing.execute(input(file) + track + listOf(
                "-vf", "scale=$width:$height:flags=lanczos,setsar=1,$yuv420",
                "-fps_mode", "vfr", "-enc_time_base", "1:1000",
            ) + vp9 + listOf("-b:v", "2000k", "-threads", "2", "-colorspace", "bt709", "-color_range", "tv",
                "-bsf:v", durations, playable.path) +
                track + listOf("-c:v", "copy", "-f", "mkvtimestamp_v2", timestamps.path))
            val coroutine = currentCoroutineContext()
            val times = timestamps.useLines { lines ->
                lines.drop(1).map { coroutine.ensureActive(); it.toLong() }.toList().toLongArray()
            }
            return VideoDocument(file, name, uri, info, playable, times)
        } catch (error: Throwable) {
            playable.delete()
            throw error
        } finally {
            timestamps.delete()
        }
    }
}
