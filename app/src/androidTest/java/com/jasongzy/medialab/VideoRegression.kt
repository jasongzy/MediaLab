package com.jasongzy.medialab

import android.app.Application
import android.app.Instrumentation
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.ViewModelStore
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

internal object VideoRegression {
    suspend fun run(context: Application, instrumentation: Instrumentation) {
        try {
            verify(context, instrumentation)
        } catch (error: Throwable) {
            instrumentation.sendStatus(
                2,
                android.os.Bundle().apply {
                    putString(
                        "stream",
                        com.arthenica.ffmpegkit.FFmpegKitConfig.getLastSession()
                            ?.allLogsAsString
                            .orEmpty(),
                    )
                },
            )
            throw error
        }
    }

    private suspend fun verify(context: Application, instrumentation: Instrumentation) =
        coroutineScope {
            fun stage(message: String) =
                instrumentation.sendStatus(
                    2,
                    android.os.Bundle().apply { putString("stream", "$message\n") },
                )
            stage("generate")
            val input = File(context.filesDir, "video-source.mp4")
            MediaProcessing.execute(
                listOf(
                    "-f",
                    "lavfi",
                    "-i",
                    "testsrc2=size=96x64:rate=10:duration=1",
                    "-itsoffset",
                    "0.4",
                    "-f",
                    "lavfi",
                    "-i",
                    "sine=frequency=440:duration=0.6",
                    "-t",
                    "1",
                    "-c:v",
                    "mpeg4",
                    "-threads",
                    "2",
                    "-c:a",
                    "aac",
                    "-metadata",
                    "creation_time=2020-02-03T04:05:06Z",
                    "-metadata",
                    "comment=拍摄信息",
                    "-metadata:s:v:0",
                    "handler_name=画面轨道",
                    input.path,
                )
            )
            stage("inspect")
            val info = Videos.inspect(input)
            check(info.width == 96 && info.height == 64 && info.audioTracks.single().codec == "aac")
            val containerTags = info.metadata.single { it.streamIndex == null }.entries.toMap()
            val videoTags =
                info.metadata.single { it.streamIndex == info.videoIndex }.entries.toMap()
            check(containerTags["creation_time"] == "2020-02-03T04:05:06.000000Z")
            check(containerTags["comment"] == "拍摄信息")
            check(videoTags["handler_name"] == "画面轨道")
            val descriptive = info.descriptiveMetadata(false).flatMap { it.entries }.toMap()
            check(descriptive["comment"] == "拍摄信息" && descriptive["creation_time"] == containerTags["creation_time"])
            check("encoder" !in descriptive && "handler_name" !in descriptive)
            check(info.descriptiveMetadata(true).isEmpty())
            val edits =
                info
                    .edits()
                    .copy(
                        startMs = 200,
                        endMs = 800,
                        beforeMs = 300,
                        afterMs = 100,
                        timeColor = 0xffffffff.toInt(),
                        crop = IntRect(5, 7, 84, 62),
                        canvas = IntRect(-7, -9, 88, 62),
                        width = 127,
                        height = 95,
                        fps = 10,
                        bitrateKbps = 500,
                        background = Color.BLACK,
                    )
            for (format in VideoFormat.entries) {
                stage("export $format")
                val target = File(context.filesDir, "video-result.${format.extension}")
                val stages = mutableListOf<Int>()
                Videos.export(
                    input,
                    info,
                    edits.copy(format = format, holdFrames = format == VideoFormat.WEBM),
                    target,
                    stage = stages::add,
                )
                check(
                    stages ==
                        if (format == VideoFormat.GIF)
                            listOf(R.string.analyzing_gif_colors, R.string.encoding_gif)
                        else listOf(R.string.preparing_video)
                )
                val disguised = File(context.filesDir, "probe.bin")
                target.copyTo(disguised, overwrite = true)
                val output = Videos.inspect(disguised)
                disguised.delete()
                check(output.format == format.extension)
                val editor = VideoEditor(output.edits())
                check(editor.format == null && editor.output.format == format)
                check(
                    output.size ==
                        if (format == VideoFormat.GIF) edits.size else evenVideoSize(edits.size)
                )
                check(abs(output.durationMs - 1000) <= 150) { "${format}: ${output.durationMs}" }
                check(output.audioTracks.isEmpty() == (format == VideoFormat.GIF))
                if (format == VideoFormat.MP4) {
                    val frame = File(context.filesDir, "frame.png")
                    Videos.frame(target, output, 100, frame, output.edits())
                    val bitmap = checkNotNull(BitmapFactory.decodeFile(frame.path))
                    check(Color.red(bitmap.getPixel(64, 48)) >= 240)
                    bitmap.recycle()
                }
                if (format in listOf(VideoFormat.MP4, VideoFormat.GIF)) {
                    val collection =
                        if (format == VideoFormat.GIF) MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                        else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    val name = "MediaLab-test-${UUID.randomUUID()}.${format.extension}"
                    val where = "${MediaStore.MediaColumns.DISPLAY_NAME}=?"
                    try {
                        val path = MediaFiles.save(context, target, name, ImportType.VIDEO)
                        check(
                            path ==
                                "${if (format == VideoFormat.GIF) "Pictures" else "Movies"}/MediaLab/$name"
                        )
                        context.contentResolver
                            .query(
                                collection,
                                arrayOf(
                                    MediaStore.MediaColumns.IS_PENDING,
                                    MediaStore.MediaColumns.SIZE,
                                ),
                                where,
                                arrayOf(name),
                                null,
                            )!!
                            .use {
                                check(
                                    it.moveToFirst() &&
                                        it.getInt(0) == 0 &&
                                        it.getLong(1) == target.length()
                                )
                            }
                    } finally {
                        context.contentResolver.delete(collection, where, arrayOf(name))
                    }
                }
            }
            stage("GIF colors and looping")
            val paletteGif = File(context.filesDir, "colors.gif")
            Videos.export(
                input,
                info,
                info.edits().copy(format = VideoFormat.GIF, gifColors = 16, gifLoop = true),
                paletteGif,
            )
            check(paletteGif.readBytes().toString(Charsets.ISO_8859_1).contains("NETSCAPE2.0"))
            check(
                !File(context.filesDir, "video-result.gif")
                    .readBytes()
                    .toString(Charsets.ISO_8859_1)
                    .contains("NETSCAPE2.0")
            )
            val gifBitmap = checkNotNull(BitmapFactory.decodeFile(paletteGif.path))
            val pixels = IntArray(gifBitmap.width * gifBitmap.height)
            gifBitmap.getPixels(pixels, 0, gifBitmap.width, 0, 0, gifBitmap.width, gifBitmap.height)
            check(pixels.toSet().size <= 16)
            gifBitmap.recycle()
            stage("timed cover and metadata cleanup")
            val covered =
                info
                    .edits()
                    .next()
                    .copy(
                        cover =
                            VideoCover(
                                listOf(
                                    Stroke(
                                        listOf(0f to 0f, 96f to 64f),
                                        Color.WHITE,
                                        10f,
                                        CoverShape.RECTANGLE,
                                    )
                                ),
                                200,
                                600,
                            )
                    )
            val clean = File(context.filesDir, "clean.mp4")
            val options = info.edits().copy(clearMetadata = true)
            check(Videos.canCopy(info, options))
            Videos.export(input, info, options, clean)
            val cleaned = Videos.inspect(clean)
            check(cleaned.codec == info.codec && cleaned.audioTracks.size == info.audioTracks.size)
            check(
                cleaned.metadata
                    .flatMap { it.entries }
                    .none { it.first in listOf("comment", "creation_time") || it.second == "画面轨道" }
            )
            val hashes =
                listOf(input, clean).mapIndexed { index, file ->
                    val hash = File(context.filesDir, "streams$index.txt")
                    MediaProcessing.execute(
                        listOf(
                            "-i",
                            file.path,
                            "-map",
                            "0",
                            "-c",
                            "copy",
                            "-f",
                            "streamhash",
                            "-hash",
                            "sha256",
                            hash.path,
                        )
                    )
                    hash.readText()
                }
            check(hashes[0] == hashes[1])
            val rendered = File(context.filesDir, "covered.mp4")
            Videos.export(input, info, covered.copy(clearMetadata = true), rendered)
            val renderedInfo = Videos.inspect(rendered)
            check(
                renderedInfo.metadata
                    .flatMap { it.entries }
                    .none { it.first in listOf("comment", "creation_time") }
            )
            val sample = File(context.filesDir, "cover-frame.png")
            for (position in listOf(100L, 200L, 500L, 600L)) {
                for ((source, sourceInfo, changes) in
                    listOf(
                        Triple(input, info, covered),
                        Triple(rendered, renderedInfo, renderedInfo.edits()),
                    )) {
                    Videos.frame(source, sourceInfo, position, sample, changes)
                    val bitmap = checkNotNull(BitmapFactory.decodeFile(sample.path))
                    val pixel = bitmap.getPixel(48, 32)
                    val white =
                        Color.red(pixel) > 240 &&
                            Color.green(pixel) > 240 &&
                            Color.blue(pixel) > 240
                    check(white == (position in 200L until 600L)) {
                        "Cover timing: ${source.name}, $position, $pixel"
                    }
                    bitmap.recycle()
                }
            }
            val resizedCover =
                covered
                    .next()
                    .copy(
                        width = 48,
                        height = 32,
                        startMs = 300,
                        endMs = 700,
                        beforeMs = 100,
                        holdFrames = true,
                    )
            Videos.frame(input, info, 0, sample, resizedCover)
            val first = checkNotNull(BitmapFactory.decodeFile(sample.path))
            check(first.width == 48 && first.height == 32 && first.getPixel(24, 16) == Color.WHITE)
            first.recycle()
            stage("speed and timed cover")
            for (speed in listOf(50, 150, 200)) {
                val sped = covered.next().copy(speedPercent = speed, beforeMs = 100, afterMs = 200)
                Videos.export(input, info, sped, rendered)
                val spedInfo = Videos.inspect(rendered)
                check(abs(spedInfo.durationMs - sped.durationMs) <= 100)
                for (time in listOf(0L, 100 + 30000L / speed, sped.durationMs - 1)) {
                    for ((source, sourceInfo, changes) in
                        listOf(
                            Triple(input, info, sped),
                            Triple(rendered, spedInfo, spedInfo.edits()),
                        )) {
                        Videos.frame(source, sourceInfo, time, sample, changes)
                        val bitmap = checkNotNull(BitmapFactory.decodeFile(sample.path))
                        val pixel = bitmap.getPixel(48, 32)
                        val white =
                            Color.red(pixel) > 240 &&
                                Color.green(pixel) > 240 &&
                                Color.blue(pixel) > 240
                        check(white == (time in 100 until sped.durationMs - 200)) {
                            "Speed cover: $speed, ${source.name}, $time, $pixel"
                        }
                        bitmap.recycle()
                    }
                }
            }
            val muted = File(context.filesDir, "muted.mp4")
            stage("mute and extract")
            Videos.export(
                input,
                info,
                edits.copy(
                    mute = true,
                    width = 320,
                    height = 240,
                    startMs = 201,
                    endMs = 202,
                    beforeMs = 0,
                    afterMs = 0,
                ),
                muted,
            )
            check(Videos.inspect(muted).audioTracks.isEmpty())
            stage("HDR conversion")
            val hdr = File(context.filesDir, "hdr.mp4")
            MediaProcessing.execute(
                listOf(
                    "-i",
                    input.path,
                    "-c",
                    "copy",
                    "-color_trc",
                    "smpte2084",
                    "-color_primaries",
                    "bt2020",
                    "-colorspace",
                    "bt2020nc",
                    hdr.path,
                )
            )
            val hdrInfo = Videos.inspect(hdr)
            check(hdrInfo.hdr)
            stage("HDR stream copy")
            for (format in listOf(VideoFormat.MP4, VideoFormat.MKV)) {
                val target = File(context.filesDir, "hdr-copy.${format.extension}")
                val options = hdrInfo.edits().copy(format = format, mute = true, clearMetadata = true)
                check(Videos.canCopy(hdrInfo, options))
                Videos.export(hdr, hdrInfo, options, target)
                val copied = Videos.inspect(target)
                check(copied.hdr && copied.color == hdrInfo.color && copied.codec == hdrInfo.codec)
                check(copied.audioTracks.isEmpty())
                check(copied.descriptiveMetadata(false).flatMap { it.entries }.none { it.first == "comment" })
                val hashes = listOf(hdr, target).mapIndexed { index, source ->
                    val hash = File(context.filesDir, "stream-$index.hash")
                    MediaProcessing.execute(listOf("-i", source.path, "-map", "0:v:0", "-c", "copy", "-f", "hash", "-hash", "sha256", hash.path))
                    hash.readText()
                }
                check(hashes[0] == hashes[1])
            }
            Videos.export(hdr, hdrInfo, hdrInfo.edits().copy(width = 320, height = 240), muted)
            check(!Videos.inspect(muted).hdr)
            val frame = File(context.filesDir, "cropped-frame.png")
            Videos.frame(input, info, 400, frame, edits)
            val bitmap = checkNotNull(BitmapFactory.decodeFile(frame.path))
            check(bitmap.width == edits.size.width && bitmap.height == edits.size.height)
            check(Color.red(bitmap.getPixel(0, 0)) < 10)
            bitmap.recycle()
            Videos.frame(input, info, 0, frame, edits)
            val padding = checkNotNull(BitmapFactory.decodeFile(frame.path))
            check(padding.width == edits.size.width && padding.height == edits.size.height)
            check(Color.red(padding.getPixel(0, 0)) > 240)
            padding.recycle()
            stage("hold trimmed endpoints")
            val frozenEdits = edits.copy(holdFrames = true)
            for ((time, boundary) in
                listOf(
                    0L to frozenEdits.beforeMs,
                    frozenEdits.durationMs - 1 to frozenEdits.durationMs - frozenEdits.afterMs - 1,
                )) {
                Videos.frame(input, info, time, frame, frozenEdits)
                val frozen = checkNotNull(BitmapFactory.decodeFile(frame.path))
                Videos.frame(input, info, boundary, frame, frozenEdits)
                val endpoint = checkNotNull(BitmapFactory.decodeFile(frame.path))
                check(frozen.sameAs(endpoint))
                frozen.recycle()
                endpoint.recycle()
            }
            stage("sequential edits")
            val paddingColor = Color.rgb(53, 167, 201)
            val padded = info.edits().copy(beforeMs = 300, timeColor = paddingColor)
            val expanded =
                padded.next().copy(canvas = IntRect(-8, -6, 104, 70), background = Color.BLUE)
            val cropped = expanded.next().withCrop(IntRect(0, 0, 80, 60))
            val sequence =
                cropped.next().copy(startMs = 100, endMs = 800, format = VideoFormat.WEBM)
            sequence.validate(info.size, info.durationMs)
            Videos.frame(input, info, 0, frame, sequence)
            val firstFrame = checkNotNull(BitmapFactory.decodeFile(frame.path))
            check(firstFrame.width == 80 && firstFrame.height == 60)
            check(
                firstFrame.getPixel(0, 0) == Color.BLUE &&
                    firstFrame.getPixel(16, 16) == paddingColor
            )
            firstFrame.recycle()
            val sequenceFile = File(context.filesDir, "sequence.webm")
            Videos.export(input, info, sequence, sequenceFile)
            val sequenceInfo = Videos.inspect(sequenceFile)
            check(
                sequenceInfo.width == 80 &&
                    sequenceInfo.height == 60 &&
                    abs(sequenceInfo.durationMs - 700) < 110
            )
            Videos.frame(sequenceFile, sequenceInfo, 0, frame, sequenceInfo.edits())
            val encodedFrame = checkNotNull(BitmapFactory.decodeFile(frame.path))
            check(Color.blue(encodedFrame.getPixel(1, 1)) > 220)
            val encodedColor = encodedFrame.getPixel(16, 16)
            check(abs(Color.red(encodedColor) - Color.red(paddingColor)) < 10)
            check(abs(Color.green(encodedColor) - Color.green(paddingColor)) < 10)
            check(abs(Color.blue(encodedColor) - Color.blue(paddingColor)) < 10)
            encodedFrame.recycle()
            stage("preview size limits")
            val tail =
                info.edits().next().withCrop(IntRect(0, 0, 80, 60)).next().copy(startMs = 999)
            val tailPreview = Videos.preview(input, info, tail, context.cacheDir) {}
            check(Videos.inspect(tailPreview).size == IntSize(80, 60))
            check(tailPreview.delete())
            for ((width, height) in listOf(1 to 1, 4000 to 2000)) {
                val playable =
                    Videos.preview(
                        input,
                        info,
                        edits.copy(width = width, height = height),
                        context.cacheDir,
                    ) {}
                val size = Videos.inspect(playable).size
                check(
                    size ==
                        if (width == 1) androidx.compose.ui.unit.IntSize(2, 2)
                        else androidx.compose.ui.unit.IntSize(1280, 640)
                )
                check(playable.delete())
            }
            val audio = File(context.filesDir, "track.flac")
            Videos.audio(input, info, edits, info.audioTracks.single().index, audio)
            AudioFiles.validate(audio)
            val pcm = File(context.filesDir, "track.pcm")
            val samples = decodeAudio(audio, pcm)
            check((0 until 3200).all { abs(samples[it].toInt()) < 2 })
            check((4800 until 5600).any { abs(samples[it].toInt()) > 100 })
            Videos.audio(input, info, sequence, info.audioTracks.single().index, audio)
            val sequenceAudio = decodeAudio(audio, pcm)
            check(abs(sequenceAudio.size - 5600) <= 1)
            check((0 until 4000).all { abs(sequenceAudio[it].toInt()) < 2 })
            check((5000 until 5500).any { abs(sequenceAudio[it].toInt()) > 100 })
            stage("crop after timeline padding")
            val paddedCrop =
                info
                    .edits()
                    .copy(
                        startMs = 200,
                        endMs = 800,
                        beforeMs = 800,
                        afterMs = 800,
                    )
                    .next()
                    .withCrop(IntRect(0, 0, 80, 60))
            val paddedPreview = Videos.preview(input, info, paddedCrop, context.cacheDir) {}
            val paddedSamples = decodeAudio(paddedPreview, pcm)
            check(abs(paddedSamples.size - 17600) < 200)
            check((0 until 7600).all { abs(paddedSamples[it].toInt()) < 2 })
            check((8400 until 10400).any { abs(paddedSamples[it].toInt()) > 100 })
            check((12000 until paddedSamples.size).all { abs(paddedSamples[it].toInt()) < 2 })
            check(paddedPreview.delete())
            val gif = File(context.filesDir, "video-result.gif")
            stage("GIF playback")
            val imported = Videos.read(gif, "test.gif", null, context.cacheDir, Videos.inspect(gif))
            check(imported.playable.extension == "webm")
            check(abs(imported.info.fps - 10) < 0.01)
            val held = File(context.filesDir, "held.gif")
            stage("held frame")
            instrumentation.context.assets.open("held.gif").use { source ->
                held.outputStream().use(source::copyTo)
            }
            val heldInfo = Videos.inspect(held)
            Videos.frame(held, heldInfo, heldInfo.durationMs, frame, heldInfo.edits())
            val last = checkNotNull(BitmapFactory.decodeFile(frame.path))
            check(Color.blue(last.getPixel(0, 0)) > 240 && Color.red(last.getPixel(0, 0)) < 10)
            last.recycle()
            stage("full-resolution frame")
            val fullHd = File(context.filesDir, "full-hd.mp4")
            MediaProcessing.execute(
                listOf(
                    "-f",
                    "lavfi",
                    "-i",
                    "color=c=red:s=1920x1080:r=2:d=1",
                    "-c:v",
                    "mpeg4",
                    "-threads",
                    "1",
                    fullHd.path,
                )
            )
            val fullHdInfo = Videos.inspect(fullHd)
            withTimeout(15_000) {
                Videos.frame(fullHd, fullHdInfo, 250, frame, fullHdInfo.edits())
            }
            val fullHdFrame = checkNotNull(BitmapFactory.decodeFile(frame.path))
            check(fullHdFrame.width == 1920 && fullHdFrame.height == 1080)
            check(Color.red(fullHdFrame.getPixel(960, 540)) > 240)
            fullHdFrame.recycle()
            stage("audio track selection")
            val multi = File(context.filesDir, "multi.mkv")
            MediaProcessing.execute(
                listOf(
                    "-i",
                    input.path,
                    "-f",
                    "lavfi",
                    "-i",
                    "sine=frequency=400:sample_rate=8000:duration=1",
                    "-itsoffset",
                    "0.4",
                    "-f",
                    "lavfi",
                    "-i",
                    "sine=frequency=800:sample_rate=16000:duration=0.4",
                    "-f",
                    "lavfi",
                    "-i",
                    "sine=frequency=1200:sample_rate=8000:duration=1",
                    "-map",
                    "1:a",
                    "-map",
                    "0:v",
                    "-map",
                    "2:a",
                    "-map",
                    "3:a",
                    "-c:v",
                    "copy",
                    "-c:a",
                    "pcm_s16le",
                    "-metadata:s:a:0",
                    "title=First",
                    "-metadata:s:a:0",
                    "language=eng",
                    multi.path,
                )
            )
            val multiInfo = Videos.inspect(multi)
            check(multiInfo.audioTracks.map { it.index } == listOf(0L, 2L, 3L))
            val firstTags = multiInfo.metadata.first { it.streamIndex == multiInfo.audioTracks.first().index }.entries.toMap()
            check(firstTags["title"] == "First" && firstTags["language"] == "eng")
            val audioEdits =
                multiInfo.edits().copy(startMs = 200, endMs = 800, beforeMs = 100, afterMs = 200)
            stage("export all audio tracks")
            for (format in VideoFormat.entries.filter { it != VideoFormat.GIF }) {
                val target = File(context.filesDir, "multi-export.${format.extension}")
                Videos.export(multi, multiInfo, audioEdits.copy(format = format), target)
                val exported = Videos.inspect(target)
                check(exported.audioTracks.size == 3)
                check(exported.metadata.first { it.streamIndex == exported.audioTracks.first().index }
                    .entries.toMap()["language"] == "eng")
                for ((track, frequency) in listOf(400, 800, 1200).withIndex()) {
                    val samples = decodeAudio(target, pcm, track)
                    check(magnitude(samples, 2800, 1600, frequency) > 1000)
                }
                check(target.delete())
            }
            for (track in multiInfo.audioTracks) {
                Videos.audio(multi, multiInfo, audioEdits, track.index, audio)
                val extracted = decodeAudio(audio, pcm)
                check(abs(extracted.size - 7200) <= 1)
                check((0 until 800).all { abs(extracted[it].toInt()) < 2 })
                check((5680 until extracted.size).all { abs(extracted[it].toInt()) < 2 })
                for ((index, frequency) in listOf(0L to 400, 2L to 800, 3L to 1200)) {
                    val magnitude = magnitude(extracted, 2800, 1600, frequency)
                    val expected = if (index == track.index) 4095.0 else 0.0
                    check(abs(magnitude - expected) < 20) {
                        "${track.index}: $frequency Hz = $magnitude"
                    }
                }
                check(magnitude(extracted, 1200, 800, 800) < 10)
            }
            stage("speed preserves audio pitch and duration")
            for (speed in listOf(10, 50, 200, 800)) {
                val sped = audioEdits.copy(speedPercent = speed)
                Videos.audio(multi, multiInfo, sped, multiInfo.audioTracks.first().index, audio)
                val samples = decodeAudio(audio, pcm)
                check(abs(samples.size - sped.durationMs * 8) <= 1)
                check((0 until 800).all { abs(samples[it].toInt()) < 2 })
                if (speed in listOf(50, 200)) {
                    check(magnitude(samples, 1200, 800, 400) > 3000)
                    check(magnitude(samples, 1200, 800, 400 * speed / 100) < 100)
                }
            }
            stage("workspace workflow")
            workflow(
                context,
                instrumentation,
                input,
                multi,
                gif,
                edits.copy(beforeMs = 800, afterMs = 800, speedPercent = 50),
            )
            val cancelled = File(context.filesDir, "cancelled.gif")
            stage("cancel")
            val retained = context.filesDir.listFiles()!!.toSet()
            val task = launch {
                Videos.export(
                    input,
                    info,
                    covered.copy(format = VideoFormat.GIF, afterMs = 600000),
                    cancelled,
                )
            }
            delay(150)
            task.cancel()
            task.join()
            check(!cancelled.exists())
            check(context.filesDir.listFiles()!!.toSet() == retained)
        }

    private suspend fun decodeAudio(file: File, target: File, track: Int = 0): ShortArray {
        MediaProcessing.execute(
            listOf(
                "-i",
                file.path,
                "-map",
                "0:a:$track",
                "-f",
                "s16le",
                "-ar",
                "8000",
                "-ac",
                "1",
                target.path,
            )
        )
        val samples =
            java.nio.ByteBuffer.wrap(target.readBytes())
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .asShortBuffer()
        return ShortArray(samples.remaining()).also { samples.get(it) }
    }

    private fun magnitude(samples: ShortArray, start: Int, count: Int, frequency: Int): Double {
        var real = 0.0
        var imaginary = 0.0
        for (i in 0 until count) {
            val angle = 2 * Math.PI * frequency * i / 8000
            real += samples[start + i] * kotlin.math.cos(angle)
            imaginary += samples[start + i] * kotlin.math.sin(angle)
        }
        return 2 * kotlin.math.hypot(real, imaginary) / count
    }

    private suspend fun workflow(
        app: Application,
        instrumentation: Instrumentation,
        input: File,
        multi: File,
        gif: File,
        edits: VideoEdits,
    ) {
        val store = ViewModelStore()
        lateinit var model: LabViewModel
        fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)
        suspend fun idle() {
            val state = withTimeout(60_000) { model.state.first { !it.busy } }
            check(state.error == null) { state.error.orEmpty() }
        }
        suspend fun reject(confirmation: Confirmation) {
            withTimeout(60_000) { model.state.first { it.confirmation == confirmation } }
            onMain { model.answerConfirmation(false) }
            idle()
        }
        try {
            onMain {
                model = LabViewModel(app)
                store.put("video-test", model)
                model.open(Uri.fromFile(multi), null)
            }
            idle()
            val video = checkNotNull(model.state.value.video)
            check(video.selectedAudioTrack == 0L && !video.audioTracksExpanded)
            check(model.state.value.tab == ImportType.VIDEO.ordinal)
            val metadataTag = VideoTag(video.info.audioTracks.first().index, "title")
            onMain {
                video.editor.tool = VideoTool.TIME
                video.editor.draft.change(video.editor.current.copy(startMs = 100))
                model.editVideoMetadata(metadataTag, "Edited track") {}
            }
            idle()
            val timeDraft = video.editor.draft
            for (direction in listOf(-1, 1)) {
                onMain { model.undoVideo(direction) }
                idle()
                check(video.editor.draft === timeDraft && timeDraft.canUndo)
                check(video.editor.pending == listOf(VideoTool.TIME))
                check(video.appliedPreview == null)
            }
            val tagged = File(app.cacheDir, "metadata.mkv")
            Videos.export(video.file, video.info, video.editor.output, tagged)
            val taggedInfo = Videos.inspect(tagged)
            check(taggedInfo.metadata.first { it.streamIndex == taggedInfo.audioTracks.first().index }
                .entries.toMap()["title"] == "Edited track")
            onMain { model.undoVideo(-1) }
            idle()
            onMain { video.editor.resetDrafts() }
            onMain {
                video.editor.history.change(edits)
                video.editor.resetDrafts()
                video.editor.tool = VideoTool.TIME
                video.editor.draft.change(video.editor.current.copy(startMs = 100))
                video.editor.tool = VideoTool.RESIZE
                video.editor.resize = IntSize(120, video.editor.resize.height)
                model.applyVideo()
            }
            reject(Confirmation.APPLY)
            check(video.editor.pending.size == 2 && video.editor.history.value == edits)
            onMain { model.undoVideo(-1) }
            reject(Confirmation.UNDO)
            onMain { model.applyVideo() }
            withTimeout(60_000) { model.state.first { it.confirmation == Confirmation.APPLY } }
            onMain { model.answerConfirmation(true) }
            idle()
            check(video.editor.pending.isEmpty() && video.editor.history.value.width == 120)
            val applied = checkNotNull(video.appliedPreview)
            check(
                video.playbackFile == applied &&
                    video.editor.current.crop ==
                        IntRect(0, 0, 120, video.editor.history.value.size.height)
            )
            check(Videos.inspect(applied).width == 120)
            check(video.appliedInfo == Videos.inspect(applied))
            val committed = video.editor.history.value
            onMain {
                video.editor.positionMs = 0
                video.editor.tool = VideoTool.CLEAN
                video.editor.clearMetadata = true
                model.applyVideo()
            }
            idle()
            check(video.editor.history.value.clearMetadata && video.editor.positionMs == 0L)
            check(video.appliedPreview == applied && applied.isFile)
            onMain { model.undoVideo(-1) }
            idle()
            check(
                video.editor.history.value == committed &&
                    video.appliedPreview == applied &&
                    applied.isFile
            )
            check(video.editor.pending.isEmpty() && !video.editor.clearMetadata)
            check(model.state.value.notice == Notice(R.string.undone, argumentRes = R.string.clean))
            val files = video.file.parentFile!!.listFiles()!!.toSet()
            onMain {
                video.editor.resetDrafts()
                video.editor.tool = VideoTool.TIME
                video.editor.draft.change(video.editor.current.copy(afterMs = 600000))
                model.applyVideo()
            }
            delay(150)
            onMain { model.cancel() }
            idle()
            check(
                video.editor.history.value == committed &&
                    video.appliedPreview == applied &&
                    applied.isFile
            )
            check(video.editor.pending == listOf(VideoTool.TIME))
            check(video.file.parentFile!!.listFiles()!!.toSet() == files)
            onMain { video.editor.resetDrafts() }
            onMain { model.undoVideo(-1) }
            idle()
            check(video.editor.history.value == edits)
            check(model.state.value.notice == Notice(R.string.undone, argumentRes = R.string.resize))
            check(video.editor.tool == VideoTool.RESIZE && video.editor.pending == listOf(VideoTool.RESIZE))
            check(video.editor.resize.width == 120)
            check(
                applied.isFile &&
                    Videos.inspect(video.playbackFile).size == evenVideoSize(edits.size)
            )
            onMain {
                model.undoVideo(1)
                check(!model.state.value.showProgress)
            }
            withTimeout(60_000) { model.state.first { it.confirmation == Confirmation.REDO } }
            onMain { model.answerConfirmation(true) }
            idle()
            check(video.playbackFile == applied)
            check(video.editor.pending.isEmpty())
            check(model.state.value.notice == Notice(R.string.redone, argumentRes = R.string.resize))
            onMain { model.previewVideo() }
            idle()
            val obsolete = checkNotNull(video.previewFile)
            onMain { video.editor.showPreview = false }
            onMain { model.restoreVideo() }
            idle()
            check(video.appliedPreview == null && video.playbackFile == video.playable)
            check(video.appliedInfo == null)
            check(video.previewFile == null && video.previewEdits == null && !obsolete.exists())
            onMain { model.clearCache() }
            idle()
            check(!applied.exists() && video.workingFiles.all { it.isFile })
            onMain { model.undoVideo(-1) }
            idle()
            check(video.appliedPreview != applied)
            check(Videos.inspect(video.playbackFile).width == 120)
            onMain { model.undoVideo(-1) }
            idle()
            check(video.editor.history.value == edits)
            check(video.editor.pending == listOf(VideoTool.RESIZE))
            onMain { model.previewVideo() }
            reject(Confirmation.EXPORT)
            check(video.previewFile == null && video.editor.pending == listOf(VideoTool.RESIZE))
            onMain { model.previewVideo() }
            withTimeout(60_000) { model.state.first { it.confirmation == Confirmation.EXPORT } }
            onMain { model.answerConfirmation(true) }
            idle()
            val preview = checkNotNull(video.previewFile)
            check(Videos.inspect(preview).size == evenVideoSize(edits.size))
            onMain {
                video.editor.showPreview = false
                model.shareVideo { error("Unexpected share") }
            }
            reject(Confirmation.EXPORT)
            check(video.previewFile == preview && video.editor.pending == listOf(VideoTool.RESIZE))
            onMain { video.editor.resetDrafts(); model.previewVideo() }
            idle()
            check(video.previewFile == preview)
            onMain {
                video.editor.showPreview = false
                model.extractVideo(false, false)
            }
            idle()
            val photo = model.state.value.photo
            check(photo.sourceUri == null && photo.info?.width == edits.size.width)
            onMain {
                photo.editor!!
                    .cropHistory
                    .change(FrameSelection(android.graphics.Rect(1, 1, 30, 30)))
                model.extractVideo(false, false)
            }
            reject(Confirmation.IMAGE)
            check(model.state.value.photo === photo)
            onMain {
                video.selectedAudioTrack = 3L
                model.extractVideo(true, false)
            }
            idle()
            val audio = checkNotNull(model.state.value.audio)
            check(audio.sourceUri == null && model.state.value.tab == ImportType.AUDIO.ordinal)
            onMain {
                model.rememberPlaybackPosition(video.file, 400)
                model.rememberPlaybackPosition(audio.file, 300)
                check(
                    model.playbackPosition(video.file) == 400L &&
                        model.playbackPosition(audio.file) == 300L
                )
                model.open(Uri.fromFile(input), null)
            }
            reject(Confirmation.VIDEO)
            check(
                model.state.value.video === video &&
                    model.state.value.workingFiles.all { it.isFile }
            )
            val motion = File(app.filesDir, "motion.jpg")
            val jpeg =
                java.io
                    .ByteArrayOutputStream()
                    .also {
                        val cover =
                            android.graphics.Bitmap.createBitmap(
                                16,
                                16,
                                android.graphics.Bitmap.Config.ARGB_8888,
                            )
                        cover.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it)
                        cover.recycle()
                    }
                    .toByteArray()
            val xmp =
                ("http://ns.adobe.com/xap/1.0/\u0000" +
                        "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">" +
                        "<rdf:Description xmlns:GCamera=\"http://ns.google.com/photos/1.0/camera/\" " +
                        "GCamera:MotionPhoto=\"1\" GCamera:MicroVideoOffset=\"${input.length()}\"/>" +
                        "</rdf:RDF></x:xmpmeta>")
                    .toByteArray()
            java.io.DataOutputStream(motion.outputStream()).use {
                it.writeShort(0xffd8)
                it.writeShort(0xffe1)
                it.writeShort(xmp.size + 2)
                it.write(xmp)
                it.write(jpeg, 2, jpeg.size - 2)
                input.inputStream().use { source -> source.copyTo(it) }
            }
            onMain { model.open(Uri.fromFile(motion), ImportType.IMAGE) }
            withTimeout(60_000) { model.state.first { it.confirmation == Confirmation.IMAGE } }
            onMain { model.answerConfirmation(true) }
            idle()
            val motionPhoto = model.state.value.photo
            check(motionPhoto.info?.container?.video != null)
            onMain { model.extractMotionVideo(false) }
            reject(Confirmation.VIDEO)
            check(model.state.value.video === video)
            val replacedFiles = video.files
            onMain { model.extractMotionVideo(false) }
            withTimeout(60_000) { model.state.first { it.confirmation == Confirmation.VIDEO } }
            onMain { model.answerConfirmation(true) }
            idle()
            check(model.state.value.photo === motionPhoto)
            check(replacedFiles.none { it.exists() })
            check(
                checkNotNull(model.state.value.video)
                    .file
                    .readBytes()
                    .contentEquals(input.readBytes())
            )
            val previousVideo = checkNotNull(model.state.value.video)
            val retainedFiles = previousVideo.file.parentFile!!.listFiles()!!.toSet()
            onMain {
                previousVideo.editor.draft.change(previousVideo.editor.current.copy(beforeMs = 100))
                model.tab(ImportType.IMAGE.ordinal)
                model.open(Uri.fromFile(gif), ImportType.IMAGE)
            }
            reject(Confirmation.VIDEO)
            check(model.state.value.video === previousVideo && model.state.value.tab == ImportType.IMAGE.ordinal)
            check(previousVideo.file.parentFile!!.listFiles()!!.toSet() == retainedFiles)
            onMain { model.open(Uri.fromFile(gif), ImportType.IMAGE) }
            withTimeout(60_000) { model.state.first { it.confirmation == Confirmation.VIDEO } }
            onMain { model.answerConfirmation(true) }
            idle()
            val importedGif = checkNotNull(model.state.value.video)
            check(importedGif.info.format == "gif" && importedGif.sourceUri == Uri.fromFile(gif))
            check(importedGif.playbackFile.isFile && model.state.value.tab == ImportType.VIDEO.ordinal)
            check(model.state.value.photo === motionPhoto && previousVideo.files.none { it.exists() })
        } finally {
            onMain { store.clear() }
        }
    }
}
