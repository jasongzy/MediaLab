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
import com.arthenica.ffmpegkit.FFmpegKit
import java.io.File
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

    fun edits() =
        VideoEdits(
            durationMs,
            IntRect(0, 0, width, height),
            IntRect(0, 0, width, height),
            bitrateKbps = (bitrate / 1000).coerceIn(500, 200000).toInt(),
            fps = fps.roundToInt().coerceIn(1, 120),
            format = exportFormat ?: VideoFormat.MP4,
        )
}

internal data class VideoPoster(val file: File, val positionMs: Long, val bitmap: Bitmap)

internal class VideoDocument(
    val file: File,
    val name: String,
    val sourceUri: Uri?,
    val info: VideoInfo,
    val playable: File,
) {
    val editor = VideoEditor(info.edits())
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

    val workingFiles
        get() = setOfNotNull(file, playable, appliedPreview, previewFile?.takeIf { editor.showPreview })

    val files
        get() = workingFiles + previews.files + listOfNotNull(previewFile)

    val dirty
        get() = editor.output != editor.original || editor.pending.isNotEmpty()
}

internal object Videos {
    fun normalizeMetadata(key: String, value: String?): String? {
        if (value == null) return null
        if (value.length > 32768 || '\u0000' in value)
            throw UnsupportedMediaException(R.string.invalid_metadata)
        return when (key.lowercase(Locale.ROOT)) {
            "creation_time" -> try {
                java.time.OffsetDateTime.parse(value).toInstant().toString()
            } catch (_: java.time.DateTimeException) {
                throw UnsupportedMediaException(R.string.invalid_metadata)
            }
            "language" -> value.lowercase(Locale.ROOT).also {
                if (!it.matches(Regex("[a-z]{3}"))) throw UnsupportedMediaException(R.string.invalid_metadata)
            }
            else -> value
        }
    }

    private fun verifyMetadata(source: VideoInfo, edits: VideoEdits, result: VideoInfo) {
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
            val normalized = actual?.let { normalizeMetadata(tag.key, it) }
            if (normalized != expected) throw UnsupportedMediaException(R.string.metadata_not_saved)
        }
    }
    private val protocols = listOf("-protocol_whitelist", "file,pipe")

    suspend fun inspect(file: File): VideoInfo {
        val info = MediaProcessing.inspect(file, "mov,matroska,webm,avi,gif,mpeg,mpegts,flv,asf,ogg")
            ?: throw UnsupportedMediaException(R.string.select_video_only)
        val stream =
            info.streams.firstOrNull {
                it.type == "video" &&
                    it.allProperties.optJSONObject("disposition")?.optInt("attached_pic") != 1
            } ?: throw UnsupportedMediaException(R.string.select_video_only)
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
        val duration =
            ((properties.optDouble("duration", 0.0).takeIf { it > 0 }
                    ?: info.duration?.toDoubleOrNull()
                    ?: 0.0) * 1000)
                .toLong()
        if (width <= 0 || height <= 0 || duration <= 0)
            throw UnsupportedMediaException(R.string.select_video_only)
        val transfer = properties.optString("color_transfer")
        val format = when (val container = info.format.orEmpty().substringBefore(',')) {
            "mov" -> {
                val brand = info.tags?.optString("major_brand").orEmpty().trim()
                when {
                    brand == "qt" -> "mov"
                    brand.startsWith("3g") -> brand.take(3)
                    else -> "mp4"
                }
            }
            "matroska" -> {
                // FFprobe shares a demuxer for Matroska and WebM; the EBML header identifies WebM.
                val header = ByteArray(256)
                file.inputStream().use { it.read(header) }
                if (header.toString(Charsets.ISO_8859_1).contains("webm")) "webm" else "mkv"
            }
            else -> container
        }
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
        )
    }

    private fun input(file: File, startMs: Long? = null) =
        (if (startMs == null) emptyList()
        else listOf("-ss", mediaSeconds(startMs), "-noaccurate_seek")) +
            protocols +
            listOf("-threads", "2", "-i", file.path)

    private fun audioFilter(steps: List<VideoEdits>) =
        steps
            .mapIndexed { index, step ->
                val start = if (index == 0) 0 else step.startMs
                // Reclock padded silence before the next edit consumes it.
                "atrim=start=${mediaSeconds(start)}:duration=${mediaSeconds(step.endMs - step.startMs)}," +
                    "asetpts=PTS-${mediaSeconds(start)}/TB,aresample=async=1:first_pts=0," +
                    tempoFilter(step.speedPercent) +
                    "adelay=${step.beforeMs}:all=1,apad=whole_dur=${mediaSeconds(step.durationMs)}," +
                    "asetpts=N/SR/TB,atrim=duration=${mediaSeconds(step.durationMs)}"
            }
            .joinToString(",")

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
        timed: Boolean,
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
        // Compare integer AVTB timestamps so floating-point rounding cannot exclude a boundary
        // frame.
        // The generated path is private app storage, not an imported filename.
        return "null[base$index];movie=filename=${file.path}:dec_threads=1[cover$index];" +
            "[base$index][cover$index]overlay=x=${bounds.left}:y=${bounds.top}:format=auto" +
            if (timed)
                ":enable='gte(round(t*1000000),${cover.startMs * 1000})*lt(round(t*1000000),${cover.endMs * 1000})'"
            else ""
    }

    private fun metadataOptions(info: VideoInfo, edits: VideoEdits, audioTrack: Long? = null): List<String> {
        val args = mutableListOf("-map_metadata", if (edits.clearMetadata) "-1" else "0", "-map_chapters", "-1")
        if (edits.clearMetadata) args += listOf("-map_metadata:s", "-1", "-metadata", "encoder=", "-metadata:s", "encoder=")
        val streams = if (audioTrack != null) listOf(audioTrack)
            else listOf(info.videoIndex) + if (edits.mute || edits.format == VideoFormat.GIF) emptyList() else info.audioTracks.map { it.index }
        if (!edits.clearMetadata) streams.forEachIndexed { index, source ->
            args += listOf("-map_metadata:s:$index", "0:s:$source")
        }
        edits.metadataEdits.forEach { (tag, value) ->
            val index = tag.streamIndex?.let(streams::indexOf)
            if (index == null || index >= 0)
                args += listOf(if (index == null) "-metadata" else "-metadata:s:$index", "${tag.key}=${value.orEmpty()}")
        }
        return args
    }

    fun canCopy(info: VideoInfo, edits: VideoEdits) =
        edits.format != VideoFormat.GIF &&
            (info.exportFormat == edits.format || when (edits.format) {
                VideoFormat.MP4 -> info.codec in setOf("h264", "hevc", "av1", "mpeg4")
                VideoFormat.MOV -> info.codec in setOf("h264", "hevc", "prores", "mjpeg", "mpeg4")
                VideoFormat.WEBM -> info.codec in setOf("vp8", "vp9", "av1")
                VideoFormat.MKV -> info.codec in setOf("h264", "hevc", "av1", "vp8", "vp9", "mpeg4", "mpeg2video", "ffv1", "prores", "mjpeg", "theora")
                VideoFormat.GIF -> false
            }) &&
            edits.fps == info.edits().fps &&
            edits.bitrateKbps == info.edits().bitrateKbps &&
            edits.steps.all { it.isIdentity(info.size, info.durationMs) }

    suspend fun export(
        file: File,
        info: VideoInfo,
        edits: VideoEdits,
        target: File,
        stage: (Int) -> Unit = {},
        progress: (Long) -> Unit = {},
    ) {
        edits.validate(info.size, info.durationMs)
        if (canCopy(info, edits)) {
            try {
                val args = input(file) + listOf("-map", "0:${info.videoIndex}", "-c:v", "copy")
                val audio = mutableListOf<String>()
                if (!edits.mute) info.audioTracks.forEachIndexed { index, track ->
                    val compatible = info.exportFormat == edits.format || when (edits.format) {
                        VideoFormat.MP4 -> track.codec in setOf("aac", "mp3", "ac3", "eac3", "alac")
                        VideoFormat.MOV -> track.codec in setOf("aac", "mp3", "ac3", "eac3", "alac", "pcm_s16le", "pcm_s24le", "pcm_s32le")
                        VideoFormat.WEBM -> track.codec in setOf("opus", "vorbis")
                        VideoFormat.MKV -> track.codec in setOf("aac", "mp3", "flac", "opus", "vorbis", "ac3", "eac3", "dts", "alac", "pcm_s16le", "pcm_s24le", "pcm_s32le")
                        VideoFormat.GIF -> false
                    }
                    audio += listOf("-map", "0:${track.index}", "-c:a:$index",
                        if (compatible) "copy" else if (edits.format == VideoFormat.WEBM) "libopus" else "aac")
                }
                MediaProcessing.execute(
                    args + audio + metadataOptions(info, edits) + target.path,
                    progress,
                )
                verifyMetadata(info, edits, inspect(target))
            } catch (error: Throwable) {
                target.delete()
                throw error
            }
            return
        }
        val temporary = mutableListOf<File>()
        try {
            val steps = renderSteps(info, edits)
            val frames = videoFrameRanges(steps, edits.fps)
            val filters =
                mutableListOf(
                    "tpad=stop_mode=clone:stop=1,fps=${edits.fps}:start_time=0:round=down,settb=AVTB"
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
                            timed = true,
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
            val args = input(file, steps.first().startMs).toMutableList()
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
                // 4:2:0 encoders require even dimensions; pad instead of stretching or trimming
                // pixels.
                filters +=
                    "pad=ceil(iw/2)*2:ceil(ih/2)*2:color=black,scale=out_color_matrix=bt709:out_range=tv,format=yuv420p"
                args += listOf("-map", "0:${info.videoIndex}", "-vf", filters.joinToString(","))
                args +=
                    if (edits.format == VideoFormat.WEBM)
                        listOf(
                            "-c:v",
                            "libvpx-vp9",
                            "-deadline",
                            "realtime",
                            "-cpu-used",
                            "6",
                            "-row-mt",
                            "1",
                        )
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
                if (!edits.mute && info.audioTracks.isNotEmpty()) {
                    info.audioTracks.forEach { args += listOf("-map", "0:${it.index}") }
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
            args +=
                listOf(
                    "-t",
                    mediaSeconds(edits.durationMs),
                ) + metadataOptions(info, edits) + encoded.path
            try {
                // Two passes keep palette generation from buffering the entire video.
                if (palette != null) {
                    stage(R.string.analyzing_gif_colors)
                    MediaProcessing.execute(
                        input(file, steps.first().startMs) +
                            listOf(
                                "-map",
                                "0:${info.videoIndex}",
                                "-vf",
                                filters.joinToString(",") +
                                    ",palettegen=stats_mode=diff:max_colors=${edits.gifColors}",
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
                if (encoded != target)
                    MediaProcessing.execute(input(encoded) + listOf("-map", "0", "-c", "copy", target.path))
                verifyMetadata(info, edits, inspect(target))
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
    ): File {
        val scale = minOf(1.0, 1280.0 / maxOf(edits.size.width, edits.size.height))
        val options =
            edits.copy(
                format = VideoFormat.MP4,
                width = (edits.size.width * scale).roundToInt().coerceAtLeast(1),
                height = (edits.size.height * scale).roundToInt().coerceAtLeast(1),
                fps = minOf(30, edits.fps),
                bitrateKbps = 3000,
                metadataEdits = emptyMap(),
            )
        var target = MediaFiles.temporary(work, "mp4")
        try {
            try {
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
            return target
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
                            timed = false,
                        )
                        ?.let { filters += it }
                filters += spatialFilters(steps[index], size)
            }
            // Whole-second seeking avoids rounding past frames in coarse input time bases.
            val seekUs = positionUs.coerceIn(0, info.durationMs * 1000 - 1)
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
                        listOf(
                            "-ss",
                            (seekUs / 1_000_000).toString(),
                            "-noaccurate_seek",
                        ) +
                            input(file) +
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
        edits.validate(info.size, info.durationMs)
        val steps = renderSteps(info, edits)
        try {
            MediaProcessing.execute(
                input(file, steps.first().startMs) +
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
                    ) +
                    metadataOptions(info, edits, track) +
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
        var playable = file
        try {
            if (info.format == "gif") {
                playable = MediaFiles.temporary(work, "webm")
                val scale = minOf(1.0, 960.0 / maxOf(info.width, info.height))
                export(
                    file,
                    info,
                    info
                        .edits()
                        .copy(
                            format = VideoFormat.WEBM,
                            width = (info.width * scale).roundToInt().coerceAtLeast(1),
                            height = (info.height * scale).roundToInt().coerceAtLeast(1),
                            bitrateKbps = 2000,
                            fps = minOf(30, info.edits().fps),
                        ),
                    playable,
                )
            }
            return VideoDocument(file, name, uri, info, playable)
        } catch (error: Throwable) {
            if (playable != file) playable.delete()
            throw error
        }
    }
}
