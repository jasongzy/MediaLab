package com.jasongzy.medialab

import android.app.Application
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.Rect
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.ViewModelStore
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal class PhotoRegression(
    private val app: Application,
    private val instrumentation: Instrumentation,
) {
    fun lassoEdges() {
        val canvas = Size(400f, 400f)
        val image = Size(100f, 80f)
        val boundary =
            listOf(
                Offset(0.5f, 40.5f),
                Offset(399.5f, 40.5f),
                Offset(399.5f, 359.5f),
                Offset(0.5f, 359.5f),
            )
        val bitmap = Bitmap.createBitmap(100, 80, Bitmap.Config.ARGB_8888)
        try {
            for (scale in listOf(1f, ImageViewport.MIN_SCALE)) {
                val points = boundary.map {
                    ImageViewport(scale).imagePoint(it, canvas, image, edgeSnap = 1f).let { p ->
                        p.x to p.y
                    }
                }
                for ((color, expected) in listOf(Color.WHITE to Color.WHITE, 0x00ffffff to Color.TRANSPARENT)) {
                    bitmap.eraseColor(Color.RED)
                    Photos.cover(bitmap, listOf(Stroke(points, color, 1f, CoverShape.LASSO)))
                    val actual = IntArray(100 * 80)
                    bitmap.getPixels(actual, 0, 100, 0, 0, 100, 80)
                    check(actual.all { it == expected }) {
                        "Lasso left uncovered pixels at scale $scale"
                    }
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun pixels(bitmap: Bitmap): ByteArray =
        ByteBuffer.allocate(bitmap.byteCount).also(bitmap::copyPixelsToBuffer).array()

    private fun fixture(): Bitmap =
        Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply {
            val row = IntArray(width)
            var value = 17
            for (y in 0 until height) {
                for (x in row.indices) {
                    value = value * 1664525 + 1013904223
                    row[x] = value or Color.BLACK
                }
                setPixels(row, 0, width, 0, y, width, 1)
            }
        }

    suspend fun expansion() {
        val store = ViewModelStore()
        lateinit var model: LabViewModel
        instrumentation.runOnMainSync {
            model = LabViewModel(app)
            store.put("expansion-test", model)
        }
        val source = fixture().apply {
            setPixel(3, 4, Color.TRANSPARENT)
            setPixel(5, 6, 0x73abcdef)
        }
        val input = File(app.cacheDir, "expand.png")
        val capture = listOf(ExifInterface.TAG_SOFTWARE to "Fixture camera")
        val bounds = Rect(-5, -3, source.width + 7, source.height + 9)
        try {
            for (color in listOf(Color.WHITE, Color.BLACK, Color.TRANSPARENT, 0xff2468ac.toInt())) {
                val pixels = Photos.expand(source, bounds, color)
                try {
                    check(pixels.width == bounds.width() && pixels.height == bounds.height())
                    for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
                        val sx = x + bounds.left
                        val sy = y + bounds.top
                        val expected = if (sx in 0 until source.width && sy in 0 until source.height)
                            source.getPixel(sx, sy) else color
                        check(pixels.getPixel(x, y) == expected) { "Expanded pixel: $x, $y" }
                    }
                } finally {
                    pixels.recycle()
                }
            }
            check(runCatching { Photos.expand(source, Rect(1, 0, 64, 48), Color.WHITE) }.isFailure)

            input.outputStream().use { check(source.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            PhotoMetadata.write(input, capture, source.width, source.height)
            idle(model)
            instrumentation.runOnMainSync { model.open(Uri.fromFile(input), ImportType.IMAGE) }
            idle(model)
            val original = checkNotNull(model.state.value.photo.frame)
            instrumentation.runOnMainSync { model.expand(bounds, Color.TRANSPARENT) }
            idle(model)
            val expanded = checkNotNull(model.state.value.photo.frame)
            check(expanded.source == null && expanded.info.mime == "image/png")
            check(expanded.info.metadata.containsAll(capture))
            instrumentation.runOnMainSync { model.undo(-1) }
            idle(model)
            check(model.state.value.photo.frame === original)
            instrumentation.runOnMainSync { model.undo(1) }
            idle(model)
            check(model.state.value.photo.frame === expanded)
            instrumentation.runOnMainSync { model.previewExport(ExportOptions()) {} }
            idle(model)
            val file = checkNotNull(model.state.value.exportFile)
            val exported = Photos.decode(file, Photos.info(file))
            try {
                check(exported.sameAs(expanded.pixels))
                check(ExifInterface(file).getAttribute(ExifInterface.TAG_SOFTWARE) == "Fixture camera")
            } finally {
                exported.recycle()
            }
            instrumentation.runOnMainSync {
                model.crop(Rect(-bounds.left, -bounds.top, source.width - bounds.left, source.height - bounds.top), false)
            }
            idle(model)
            val cropped = checkNotNull(model.state.value.photo.frame)
            check(cropped.pixels!!.sameAs(source) && cropped.info.metadata.containsAll(capture))
        } finally {
            source.recycle()
            instrumentation.runOnMainSync { store.clear() }
        }
    }

    suspend fun history() {
        val directory = File(app.cacheDir, "history")
        for (config in listOf(Bitmap.Config.ARGB_8888, Bitmap.Config.RGBA_F16)) {
            val bitmap =
                Bitmap.createBitmap(
                        31,
                        17,
                        config,
                        true,
                        ColorSpace.get(
                            if (config == Bitmap.Config.RGBA_F16) ColorSpace.Named.EXTENDED_SRGB
                            else ColorSpace.Named.SRGB
                        ),
                    )
                    .apply {
                        density = 320
                        if (config == Bitmap.Config.RGBA_F16)
                            eraseColor(Color.pack(0.12345f, 0.46789f, 1.25f, 0.6f, colorSpace!!))
                        else eraseColor(0x73ab5729)
                        setPixel(3, 4, Color.TRANSPARENT)
                    }
            val expected = pixels(bitmap)
            val space = bitmap.colorSpace
            val first = PhotoFrame(null, Photos.rasterInfo(bitmap), bitmap)
            val history = PhotoHistory(directory, first, bitmap.allocationByteCount.toLong())
            val secondBitmap =
                checkNotNull(bitmap.copy(config, true)).apply { eraseColor(Color.WHITE) }
            val second = PhotoFrame(null, Photos.rasterInfo(secondBitmap), secondBitmap)
            try {
                history.append(second)
                check(first.pixels == null && first.snapshot?.isFile == true)
                val restored = history.bitmap(first)
                check(pixels(restored).contentEquals(expected))
                check(
                    restored.config == config &&
                        restored.colorSpace == space &&
                        restored.density == 320
                )
                check(restored.hasAlpha() && restored.isPremultiplied)
                history.move(0)
                check(history.bitmap(second).getPixel(0, 0) == Color.WHITE)
                history.move(1)
                history.bitmap(first)
                history.move(0)
                val branchBitmap = history.copy().apply { eraseColor(Color.BLACK) }
                check(pixels(history.bitmap()).contentEquals(expected))
                val branch = PhotoFrame(null, Photos.rasterInfo(branchBitmap), branchBitmap)
                val discarded = second.files
                history.append(branch)
                check(history.frames == listOf(first, branch) && discarded.none { it.exists() })

                first.snapshot!!.writeBytes(byteArrayOf(0))
                check(runCatching { history.bitmap(first) }.isFailure)
                check(history.current === branch && history.position == 1)
                check(history.bitmap().getPixel(0, 0) == Color.BLACK)
                val cancelled = Job().apply { cancel() }
                check(runCatching { withContext(cancelled) { history.append(first) } }.isFailure)
                check(history.current === branch && history.frames.size == 2)
            } finally {
                history.close()
            }
        }
        val unavailable = File(directory, "unavailable").apply { writeText("file") }
        val bitmap = fixture()
        val frame = PhotoFrame(null, Photos.rasterInfo(bitmap), bitmap)
        val history = PhotoHistory(unavailable, frame, 1)
        val nextBitmap = fixture()
        try {
            check(
                runCatching { history.append(PhotoFrame(null, frame.info, nextBitmap)) }.isFailure
            )
            check(history.current === frame && frame.pixels === bitmap && !bitmap.isRecycled)
        } finally {
            nextBitmap.recycle()
            history.close()
            unavailable.delete()
        }
    }

    private suspend fun idle(model: LabViewModel) {
        withTimeout(30_000) { while (model.state.value.busy) delay(10) }
        check(model.state.value.error == null) { model.state.value.error.orEmpty() }
    }

    private suspend fun checkPicking(model: LabViewModel, expected: Bitmap) {
        val colors = mutableSetOf<Int>()
        var x = 0
        withTimeout(5_000) {
            while (colors.size < 3) {
                instrumentation.runOnMainSync { model.pick(x, 0) }
                delay(8)
                model.state.value.photo.picked?.let(colors::add)
                x = (x + 1) % expected.width
            }
        }
        instrumentation.runOnMainSync { model.pick(expected.width, expected.height) }
        withTimeout(5_000) {
            model.state.first {
                it.photo.picked == expected.getPixel(expected.width - 1, expected.height - 1)
            }
        }
        check(model.state.value.error == null) { model.state.value.error.orEmpty() }
    }

    suspend fun imports() {
        val store = ViewModelStore()
        lateinit var model: LabViewModel
        instrumentation.runOnMainSync {
            model = LabViewModel(app)
            store.put("import-test", model)
        }
        suspend fun answer(type: ImportType, discard: Boolean) {
            withTimeout(30_000) { model.state.first { it.replacing == type } }
            instrumentation.runOnMainSync { model.confirmReplacement(discard) }
            idle(model)
            check(model.state.value.replacing == null)
        }
        val image = File(app.cacheDir, "input.png")
        val bitmap = fixture()
        try {
            image.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
        val audio = File(app.cacheDir, "input.mp3")
        instrumentation.context.assets.open("silence.mp3").use { input ->
            audio.outputStream().use { input.copyTo(it) }
        }
        try {
            idle(model)
            instrumentation.runOnMainSync { model.open(Uri.fromFile(image), ImportType.IMAGE) }
            idle(model)
            instrumentation.runOnMainSync { model.crop(Rect(2, 2, 20, 20), false) }
            idle(model)
            val photo = model.state.value.photo
            val work = File(app.cacheDir, "work")
            val files = work.listFiles()!!.toSet()
            for (type in listOf(ImportType.IMAGE, null)) {
                instrumentation.runOnMainSync { model.open(Uri.fromFile(image), type) }
                answer(ImportType.IMAGE, false)
                check(model.state.value.photo === photo && work.listFiles()!!.toSet() == files)
            }
            instrumentation.runOnMainSync {
                model.open(Uri.fromFile(audio), ImportType.AUDIO)
            }
            idle(model)
            check(model.state.value.photo === photo && model.state.value.audio != null)
            instrumentation.runOnMainSync {
                model.editAudio(mapOf("TITLE" to listOf("Unsaved")), emptyList())
            }
            val editedAudio = checkNotNull(model.state.value.audio)
            check(model.state.value.audioDirty)
            val before = work.listFiles()!!.toSet()
            for (type in listOf(ImportType.AUDIO, null)) {
                instrumentation.runOnMainSync { model.open(Uri.fromFile(audio), type) }
                answer(ImportType.AUDIO, false)
                check(model.state.value.audio === editedAudio && model.state.value.audioDirty)
                check(work.listFiles()!!.toSet() == before)
            }
            instrumentation.runOnMainSync {
                model.open(Uri.fromFile(image), ImportType.AUDIO)
            }
            idle(model)
            check(model.state.value.notice == R.string.select_audio_only)
            check(model.state.value.audio === editedAudio && work.listFiles()!!.toSet() == before)
            instrumentation.runOnMainSync { model.open(Uri.fromFile(audio), null) }
            withTimeout(30_000) { model.state.first { it.replacing == ImportType.AUDIO } }
            instrumentation.runOnMainSync { model.cancel() }
            idle(model)
            check(model.state.value.replacing == null && model.state.value.audio === editedAudio)
            check(work.listFiles()!!.toSet() == before)
            instrumentation.runOnMainSync { model.open(Uri.fromFile(audio), null) }
            answer(ImportType.AUDIO, true)
            check(!model.state.value.audioDirty && model.state.value.audio != null)
            check(!editedAudio.file.exists() && model.state.value.photo === photo)
            instrumentation.runOnMainSync { model.open(Uri.fromFile(image), null) }
            answer(ImportType.IMAGE, true)
            check(model.state.value.photo.history.size == 1)
            check(photo.history.flatMap { it.files }.none { it.exists() })
            check(model.state.value.workingFiles.all { it.isFile })
        } finally {
            instrumentation.runOnMainSync { store.clear() }
        }
    }

    suspend fun workflow() {
        val store = ViewModelStore()
        lateinit var model: LabViewModel
        instrumentation.runOnMainSync {
            model = LabViewModel(app)
            store.put("photo-test", model)
        }
        val input = File(app.cacheDir, "fixture.jpg")
        val generated = fixture()
        try {
            input.outputStream().use {
                check(generated.compress(Bitmap.CompressFormat.JPEG, 100, it))
            }
        } finally {
            generated.recycle()
        }
        val reference = Photos.decode(input, Photos.info(input))
        val capture = listOf(
            ExifInterface.TAG_DATETIME to "2026:10:18 12:34:56",
            ExifInterface.TAG_DATETIME_ORIGINAL to "2026:10:18 12:34:56",
            ExifInterface.TAG_SOFTWARE to "Fixture camera",
            ExifInterface.TAG_USER_COMMENT to "a".repeat(9000),
        )
        fun checkCapture(file: File, present: Boolean = true) {
            val exif = ExifInterface(file)
            for ((tag, value) in capture)
                check(exif.getAttribute(tag) == if (present) value else null) { "Metadata: $tag" }
            check((exif.latLong != null) == present)
            if (present) {
                val location = checkNotNull(exif.latLong)
                check(abs(location[0] - 31.2) < 0.000001 && abs(location[1] - 121.5) < 0.000001)
            }
            check(exif.getAttribute(ExifInterface.TAG_MAKER_NOTE) == null)
        }
        try {
            ExifInterface(input).apply {
                capture.forEach { (tag, value) -> setAttribute(tag, value) }
                setLatLong(31.2, 121.5)
                setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, "999")
                setAttribute(ExifInterface.TAG_MAKER_NOTE, "source camera data")
                saveAttributes()
            }
            val info = Photos.info(input)
            for ((x, y) in listOf(0 to 0, 16 to 8, 63 to 47))
                check(Photos.pixel(input, info, x, y) == reference.getPixel(x, y)) {
                    "JPEG pixel at $x, $y"
                }
            for (clear in listOf(false, true)) {
                val converted = Photos.export(
                    app, input, Photos.info(input), ExportOptions(format = ImageFormat.PNG, metadata = clear),
                )
                try { checkCapture(converted, !clear) } finally { converted.delete() }
            }
            idle(model)
            instrumentation.runOnMainSync {
                model.open(Uri.fromFile(input), ImportType.IMAGE)
            }
            idle(model)
            checkPicking(model, reference)
            instrumentation.runOnMainSync {
                model.pick(3, 2)
                model.pick(9, 7)
                model.crop(Rect(0, 0, 16, 16), true)
            }
            idle(model)
            check(model.state.value.photo.picked == null)
            checkCapture(checkNotNull(model.state.value.photo.frame?.source))
            instrumentation.runOnMainSync { model.undo(-1) }
            idle(model)
            instrumentation.runOnMainSync { model.crop(Rect(8, 6, 28, 22), false) }
            idle(model)
            check(model.state.value.photo.info!!.metadata.containsAll(capture))
            val cropped = Bitmap.createBitmap(reference, 8, 6, 20, 16)
            try {
                check(model.state.value.photo.frame!!.pixels!!.sameAs(cropped))
                checkPicking(model, cropped)
            } finally {
                cropped.recycle()
            }
            instrumentation.runOnMainSync { model.undo(-1) }
            idle(model)
            val stroke =
                Stroke(listOf(-1f to -1f, 20f to 20f), Color.TRANSPARENT, 1f, CoverShape.RECTANGLE)
            Photos.cover(reference, listOf(stroke))
            instrumentation.runOnMainSync { model.cover(listOf(stroke)) }
            idle(model)
            val edited = checkNotNull(model.state.value.photo.frame)
            check(edited.source == null && edited.pixels!!.sameAs(reference))
            val files = File(app.cacheDir, "work").listFiles()!!.toSet()
            var preview: Bitmap? = null
            instrumentation.runOnMainSync { model.previewResize(32, 24, 2) { preview = it } }
            idle(model)
            val expectedPreview = checkNotNull(preview)
            instrumentation.runOnMainSync { model.previewResize(32, 24, 2) { preview = it } }
            idle(model)
            check(preview === expectedPreview)
            instrumentation.runOnMainSync { model.resize(32, 24, 2) }
            idle(model)
            val resized = checkNotNull(model.state.value.photo.frame)
            check(model.state.value.photo.preview === expectedPreview)
            check(File(app.cacheDir, "work").listFiles()!!.toSet() == files)
            val expected = Photos.resize(reference, 32, 24, 2)
            try {
                check(resized.pixels!!.sameAs(expected))
            } finally {
                expected.recycle()
            }
            instrumentation.runOnMainSync { model.undo(-1) }
            idle(model)
            check(model.state.value.photo.frame === edited && edited.pixels!!.sameAs(reference))
            instrumentation.runOnMainSync { model.undo(1) }
            idle(model)
            check(model.state.value.photo.frame === resized)
            instrumentation.runOnMainSync { model.undo(-1) }
            idle(model)
            instrumentation.runOnMainSync { model.crop(Rect(0, 0, 16, 16), false) }
            idle(model)
            check(model.state.value.photo.history.size == 3 && resized.pixels == null)
            check(!expectedPreview.isRecycled)
            instrumentation.runOnMainSync { model.previewExport(ExportOptions()) {} }
            idle(model)
            val export = checkNotNull(model.state.value.exportFile)
            val decoded = Photos.decode(export, Photos.info(export))
            try {
                check(decoded.width == 16 && decoded.height == 16 && decoded.hasAlpha())
                check(decoded.getPixel(0, 0) == Color.TRANSPARENT)
                check(decoded.sameAs(model.state.value.photo.frame!!.pixels))
            } finally {
                decoded.recycle()
            }
            for (format in listOf(ImageFormat.PNG, ImageFormat.JPEG, ImageFormat.WEBP_LOSSLESS, ImageFormat.WEBP_LOSSY)) {
                instrumentation.runOnMainSync { model.previewExport(ExportOptions(format = format)) {} }
                idle(model)
                val output = checkNotNull(model.state.value.exportFile)
                checkCapture(output)
                val exif = ExifInterface(output)
                check(exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0) == 1)
                check(exif.getAttributeInt(ExifInterface.TAG_PIXEL_X_DIMENSION, 0) == 16)
                check(exif.getAttributeInt(ExifInterface.TAG_PIXEL_Y_DIMENSION, 0) == 16)
            }
            instrumentation.runOnMainSync { model.clearCache() }
            idle(model)
            check(model.state.value.workingFiles.all { it.isFile })
            instrumentation.runOnMainSync { model.undo(-1) }
            idle(model)
            check(model.state.value.photo.frame!!.pixels!!.sameAs(reference))
            val beforeClean = checkNotNull(model.state.value.photo.frame)
            instrumentation.runOnMainSync { model.clean(false, false, true, true) }
            idle(model)
            val cleaned = checkNotNull(model.state.value.photo.frame)
            check(cleaned.info.metadata.isEmpty() && cleaned.pixels!!.sameAs(reference))
            instrumentation.runOnMainSync { model.previewExport(ExportOptions()) {} }
            idle(model)
            checkCapture(checkNotNull(model.state.value.exportFile), false)
            instrumentation.runOnMainSync { model.undo(-1) }
            idle(model)
            check(model.state.value.photo.frame === beforeClean && beforeClean.info.metadata.containsAll(capture))
            instrumentation.runOnMainSync { model.undo(1) }
            idle(model)
            check(model.state.value.photo.frame === cleaned && cleaned.info.metadata.isEmpty())
            instrumentation.runOnMainSync { model.resize(16, 16, 0) }
            idle(model)
            instrumentation.runOnMainSync { model.previewExport(ExportOptions(format = ImageFormat.JPEG)) {} }
            idle(model)
            checkCapture(checkNotNull(model.state.value.exportFile), false)
        } finally {
            reference.recycle()
            instrumentation.runOnMainSync { store.clear() }
        }
    }
}
