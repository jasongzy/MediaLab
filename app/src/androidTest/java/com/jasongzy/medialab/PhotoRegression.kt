package com.jasongzy.medialab

import android.app.Application
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.Gainmap
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.ViewModelStore
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.coroutines.CoroutineContext
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

    suspend fun maskEditing() {
        val space = ColorSpace.get(ColorSpace.Named.DISPLAY_P3)
        val source = Bitmap.createBitmap(16, 260, Bitmap.Config.RGBA_F16, true, space).apply {
            eraseColor(Color.pack(0.9f, 0.1f, 0.2f, 0.6f, space))
            setPixel(0, 127, Color.TRANSPARENT)
        }
        val mask = CutoutMask.Inferred(FloatArray(ImageMath.SIZE * ImageMath.SIZE) { 0.35f }, BackgroundModel.RMBG20)
        val preview = Photos.maskPreview(mask, source.width, source.height)
        val draft = CutoutDraft(mask, source, preview)
        val rendered = Bitmap.createBitmap(source.width, source.height, source.config!!, true, space)
        val output = checkNotNull(source.copy(source.config!!, true))
        val original = pixels(source)
        try {
            val keep = Stroke(listOf(-8f to 128f, 24f to 128f), Color.WHITE, 8f, CoverShape.BRUSH)
            val remove = Stroke(listOf(8.5f to 128.5f), Color.TRANSPARENT, 3f, CoverShape.BRUSH)
            val softKeep = keep.copy(width = 24f, softEdges = true)
            val softRemove = remove.copy(width = 12f, softEdges = true)
            draft.history.change(listOf(softKeep, keep))
            draft.history.change(draft.history.value + listOf(softRemove, remove))
            draft.history.undo()
            check(draft.history.value == listOf(softKeep, keep))
            draft.history.redo()
            Photos.applyMask(output, mask, Color.TRANSPARENT, draft.history.value)
            check(output.getColor(0, 127).alpha() == 0f)
            check(output.getColor(8, 128).alpha() == 0f)
            for (y in listOf(127, 128)) for (x in listOf(1, 15)) {
                val expected = source.getColor(x, y).components
                val actual = output.getColor(x, y).components
                check(actual.indices.all { abs(actual[it] - expected[it]) < 0.002f })
            }
            check(abs(output.getColor(1, 64).alpha() - 0.6f * 89f / 255f) < 0.002f)
            val kept = listOf(115, 117, 119, 121, 123).map { output.getColor(1, it).alpha() }
            check(kept.zipWithNext().all { (a, b) -> a < b })
            val removed = listOf(11, 12, 13, 15).map { output.getColor(it, 128).alpha() }
            check(removed.zipWithNext().all { (a, b) -> a < b })
            draft.showMask = false
            Photos.drawCutout(Canvas(rendered), draft, RectF(0f, 0f, 16f, 260f), Color.TRANSPARENT)
            val expected = FloatArray(source.width * 4)
            val actual = FloatArray(source.width * 4)
            for (y in 0 until source.height) {
                BitmapPixels.readRow(output, y, expected)
                BitmapPixels.readRow(rendered, y, actual)
                check(actual.indices.all { abs(actual[it] - expected[it]) < 0.01f }) { "Mask preview mismatch at $y" }
            }
            draft.showMask = true
            rendered.eraseColor(Color.TRANSPARENT)
            Photos.drawCutout(Canvas(rendered), draft, RectF(0f, 0f, 16f, 260f), Color.WHITE)
            check(rendered.getPixel(1, 127) == source.getPixel(1, 127))
            check(rendered.getPixel(8, 128) != source.getPixel(8, 128))
            val stored = Photos.storeMask(draft, source.width, source.height)
            val loaded = checkNotNull(source.copy(source.config!!, true))
            try {
                Photos.applyMask(loaded, stored, Color.TRANSPARENT)
                for (y in 0 until source.height) {
                    BitmapPixels.readRow(output, y, expected)
                    BitmapPixels.readRow(loaded, y, actual)
                    check(actual.indices.all { abs(actual[it] - expected[it]) < 0.002f })
                }
                val snapshot = stored.pixels.clone()
                Photos.applyMask(loaded, stored, Color.TRANSPARENT, listOf(keep))
                check(stored.pixels.contentEquals(snapshot))
            } finally { loaded.recycle() }
            val beforeHarden = draft.history.value
            draft.history.change(beforeHarden + MaskEdit.Harden)
            val hardened = Photos.storeMask(draft, source.width, source.height)
            check(hardened.pixels.indices.all {
                (hardened.pixels[it].toInt() and 255) == if ((stored.pixels[it].toInt() and 255) >= 128) 255 else 0
            })
            checkNotNull(source.copy(source.config!!, true)).let { hardenedOutput ->
                try {
                    Photos.applyMask(hardenedOutput, mask, Color.TRANSPARENT, draft.history.value)
                    draft.showMask = false
                    rendered.eraseColor(Color.TRANSPARENT)
                    Photos.drawCutout(Canvas(rendered), draft, RectF(0f, 0f, 16f, 260f), Color.TRANSPARENT)
                    for (y in 0 until source.height) for (x in 0 until source.width) {
                        val alpha = if (hardened.pixels[y * source.width + x] == 0.toByte()) 0f
                            else source.getColor(x, y).alpha()
                        check(abs(hardenedOutput.getColor(x, y).alpha() - alpha) < 0.002f)
                        val previewAlpha = rendered.getColor(x, y).alpha()
                        check(abs(previewAlpha - alpha) < 0.002f)
                    }
                    check(rendered.getColor(8, 128).alpha() == 0f)
                    check(abs(rendered.getColor(1, 127).alpha() - source.getColor(1, 127).alpha()) < 0.002f)
                } finally { hardenedOutput.recycle() }
            }
            draft.history.undo()
            check(Photos.storeMask(draft, source.width, source.height).pixels.contentEquals(stored.pixels))
            draft.history.redo()
            check(draft.history.value == beforeHarden + MaskEdit.Harden)
            draft.history.change(draft.history.value + softKeep)
            check(Photos.storeMask(draft, source.width, source.height).pixels.any { it != 0.toByte() && it != (-1).toByte() })
            draft.history.change(draft.history.value + MaskEdit.Harden)
            check(Photos.storeMask(draft, source.width, source.height).pixels.all { it == 0.toByte() || it == (-1).toByte() })
            check(pixels(source).contentEquals(original))
        } finally {
            source.recycle()
            preview.recycle()
            rendered.recycle()
            output.recycle()
        }
    }

    suspend fun colorEditing() {
        val capture = listOf(ExifInterface.TAG_SOFTWARE to "Color fixture")
        val mask = CutoutMask.Inferred(
            FloatArray(ImageMath.SIZE * ImageMath.SIZE) { (it % ImageMath.SIZE).toFloat() },
            BackgroundModel.RMBG14,
        )
        for ((named, config) in listOf(
            ColorSpace.Named.DISPLAY_P3 to Bitmap.Config.ARGB_8888,
            ColorSpace.Named.ADOBE_RGB to Bitmap.Config.RGBA_F16,
        )) {
            val space = ColorSpace.get(named)
            val source = Bitmap.createBitmap(8, 4, config, true, space).apply {
                eraseColor(Color.pack(0.9f, 0.05f, 0.02f, 0.6f, space))
            }
            val expected = source.getColor(0, 0).components
            val original = pixels(source)
            fun checkColor(bitmap: Bitmap, x: Int = 0, y: Int = 0, color: FloatArray = expected, tolerance: Float = 0.004f) {
                val actual = bitmap.getColor(x, y).convert(space).components
                check(actual.indices.all { abs(actual[it] - color[it]) < tolerance }) {
                    "$named $config: ${actual.toList()} != ${color.toList()}"
                }
            }
            try {
                for (method in 0..2) {
                    val scaled = Photos.resize(source, if (method == 2) 64 else 5, if (method == 2) 1 else 3, method)
                    try {
                        check(scaled.colorSpace == space && scaled.config == config)
                        checkColor(scaled)
                    } finally { scaled.recycle() }
                }
                val expanded = Photos.expand(source, Rect(-2, -1, 10, 5), Color.TRANSPARENT)
                try {
                    check(expanded.colorSpace == space && expanded.config == config)
                    checkColor(expanded, 2, 1)
                    Photos.cover(expanded, listOf(Stroke(listOf(2f to 1f, 3f to 2f), Color.TRANSPARENT, 1f, CoverShape.RECTANGLE)))
                    check(expanded.getColor(2, 1).alpha() == 0f)
                    checkColor(expanded, 4, 2)
                    val faint = if (config == Bitmap.Config.RGBA_F16) 1f / 1024 else 1f / 255
                    expanded.eraseColor(Color.pack(1f, 0f, 0f, faint, space))
                    check(!Photos.hasFullyTransparentPixels(expanded))
                    expanded.setPixel(expanded.width - 1, expanded.height - 1, Color.TRANSPARENT)
                    check(Photos.hasFullyTransparentPixels(expanded))
                } finally { expanded.recycle() }
                val rotated = Photos.rotate(source, RotationSelection(90, flipHorizontal = true), Color.WHITE)
                try {
                    check(rotated.colorSpace == space && rotated.config == config)
                    checkColor(rotated)
                } finally { rotated.recycle() }
                for (background in listOf(Color.TRANSPARENT, 0xff2468ac.toInt())) {
                    val cutout = checkNotNull(source.copy(config, true))
                    try {
                        Photos.applyMask(cutout, mask, background)
                        val fill = Color.valueOf(background).convert(space).components
                        checkColor(cutout, color = fill)
                        val alpha = expected[3] + fill[3] * (1f - expected[3])
                        val combined = FloatArray(4) { c ->
                            if (c == 3) alpha else
                                (expected[c] * expected[3] + fill[c] * fill[3] * (1f - expected[3])) / alpha
                        }
                        checkColor(cutout, 7, color = combined)
                        if (background == Color.TRANSPARENT)
                            checkColor(cutout, 3, color = expected.clone().apply { this[3] *= 109f / 255f }, tolerance = 0.015f)
                    } finally { cutout.recycle() }
                }
                val opaque = checkNotNull(source.copy(config, true)).apply { eraseColor(Color.RED); setHasAlpha(false) }
                try {
                    Photos.applyMask(opaque, mask, Color.WHITE)
                    check(!opaque.hasAlpha() && opaque.getPixel(0, 0) == Color.WHITE)
                    check(!Photos.hasFullyTransparentPixels(opaque))
                    opaque.setHasAlpha(true)
                    check(!Photos.hasFullyTransparentPixels(opaque))
                } finally { opaque.recycle() }
                val info = Photos.rasterInfo(source, capture)
                check(info.color.losses().isEmpty())
                for (format in ImageFormat.entries.filter { it != ImageFormat.ORIGINAL }) {
                    val file = Photos.export(app.cacheDir, source, info, ExportOptions(format, quality = 100))
                    val decodedInfo = Photos.info(file)
                    check(decodedInfo.color.wide == true && decodedInfo.color.hdr == false)
                    val decoded = Photos.decode(file, decodedInfo)
                    try {
                        val color = if (format == ImageFormat.JPEG) FloatArray(4) { c ->
                            if (c == 3) 1f else expected[c] * expected[3] + 1f - expected[3]
                        } else expected
                        checkColor(decoded, color = color, tolerance = 0.025f)
                        check(ExifInterface(file).getAttribute(ExifInterface.TAG_SOFTWARE) == "Color fixture")
                        if (format == ImageFormat.PNG) {
                            check(decoded.config == config)
                            val crop = Photos.decode(file, decodedInfo, Rect(1, 1, 5, 3))
                            try { checkColor(crop) } finally { crop.recycle() }
                            val clean = Photos.export(app.cacheDir, file, decodedInfo, ExportOptions(metadata = true))
                            check(Photos.info(clean).color.wide == true)
                            check(ExifInterface(clean).getAttribute(ExifInterface.TAG_SOFTWARE) == null)
                        }
                    } finally { decoded.recycle() }
                }
                val standard = Photos.convert(source, ColorSpace.get(ColorSpace.Named.SRGB))
                try {
                    val file = Photos.export(app.cacheDir, source, info, ExportOptions(ImageFormat.PNG, keepColor = false))
                    val decoded = Photos.decode(file, Photos.info(file))
                    try {
                        check(decoded.colorSpace?.isSrgb == true)
                        check(decoded.getPixel(0, 0) == standard.getPixel(0, 0))
                    } finally { decoded.recycle() }
                } finally { standard.recycle() }
                check(pixels(source).contentEquals(original))
            } finally { source.recycle() }
        }
        if (Build.VERSION.SDK_INT >= 34) {
            val source = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888, false, ColorSpace.get(ColorSpace.Named.DISPLAY_P3))
            val gain = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
            try {
                source.eraseColor(Color.RED)
                gain.eraseColor(Color.WHITE)
                source.gainmap = Gainmap(gain).apply { setRatioMax(2f, 2f, 2f); setDisplayRatioForFullHdr(2f) }
                val file = File(app.cacheDir, "hdr.jpg")
                file.outputStream().use { check(source.compress(Bitmap.CompressFormat.JPEG, 100, it)) }
                val preview = Photos.preview(file)
                val info = try { Photos.info(file, preview) } finally { preview.recycle() }
                check(info.color.losses() == listOf(R.string.hdr))
                val changed = Photos.export(app.cacheDir, file, info.copy(
                    metadataEdits = mapOf(ExifInterface.TAG_ARTIST to "MediaLab HDR")), ExportOptions())
                check(Photos.info(changed).color.hdr == true)
                check(ExifInterface(changed).getAttribute(ExifInterface.TAG_ARTIST) == "MediaLab HDR")
                val hdr = Photos.decode(changed, Photos.info(changed), keepHdr = true)
                val ratioMax = hdr.gainmap!!.ratioMax
                val first = PhotoFrame(null, Photos.rasterInfo(hdr), hdr)
                val history = PhotoHistory(app.cacheDir, first, memoryBudget = 1)
                try {
                    val crop = Photos.cropBitmap(hdr, Rect(1, 2, 3, 5), true)
                    check(crop.hasGainmap() && crop.gainmap!!.gainmapContents.width == 1)
                    val rotated = Photos.rotate(crop, RotationSelection(angle = 90, flipHorizontal = true), Color.WHITE)
                    crop.recycle()
                    val nearest = Photos.resize(rotated, 12, 8, 0)
                    check(nearest.hasGainmap())
                    nearest.recycle()
                    val scaled = Photos.resize(rotated, 12, 8, 2)
                    rotated.recycle()
                    val next = PhotoFrame(null, Photos.rasterInfo(scaled, listOf(ExifInterface.TAG_ARTIST to "HDR edit")), scaled)
                    history.append(next)
                    check(first.pixels == null && first.files.size == 2)
                    val restored = history.bitmap(first)
                    check(restored.hasGainmap() && restored.gainmap!!.ratioMax.contentEquals(ratioMax))
                    val output = Photos.export(app.cacheDir, history.bitmap(next), next.info, ExportOptions())
                    val saved = Photos.info(output)
                    check(saved.color.hdr == true && saved.width == 12 && saved.height == 8)
                    check(saved.metadata.contains(ExifInterface.TAG_ARTIST to "HDR edit"))
                    val clean = Photos.export(app.cacheDir, output, saved, ExportOptions(metadata = true))
                    check(Photos.info(clean).color.hdr == true && ExifInterface(clean).getAttribute(ExifInterface.TAG_ARTIST) == null)
                } finally { history.close() }
                val edited = Photos.decode(file, info)
                try {
                    check(!edited.hasGainmap() && edited.colorSpace?.isWideGamut == true)
                    val exported = Photos.export(app.cacheDir, edited, Photos.rasterInfo(edited), ExportOptions())
                    check(Photos.info(exported).color.hdr == false)
                } finally { edited.recycle() }
            } finally { source.recycle(); gain.recycle() }
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
            val quarterTurn = Photos.rotate(source, RotationSelection(90), Color.WHITE)
            try {
                for (y in 0 until source.height) for (x in 0 until source.width)
                    check(quarterTurn.getPixel(source.height - y - 1, x) == source.getPixel(x, y))
            } finally { quarterTurn.recycle() }
            for (locked in listOf(false, true)) {
                val selection = RotationSelection(45, lockCanvas = locked)
                for (color in listOf(Color.WHITE, Color.TRANSPARENT)) {
                    val rotated = Photos.rotate(source, selection, color)
                    try {
                        val size = selection.size(source.width, source.height)
                        check(rotated.width == size.width && rotated.height == size.height)
                        check(rotated.getPixel(0, 0) == color)
                    } finally { rotated.recycle() }
                }
            }

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
            instrumentation.runOnMainSync {
                val editor = checkNotNull(model.state.value.photo.editor)
                editor.rotationHistory.change(RotationSelection(90, flipHorizontal = true))
                editor.coverHistory.change(listOf(Stroke(listOf(1f to 1f), Color.BLACK, 2f, CoverShape.BRUSH)))
                model.rotate(editor.rotationHistory.value, Color.TRANSPARENT)
            }
            answer(model, Confirmation.APPLY, false)
            check(model.state.value.photo.frame === cropped && PhotoTool.ROTATE in model.state.value.photo.pending)
            instrumentation.runOnMainSync {
                model.rotate(checkNotNull(model.state.value.photo.editor).rotationHistory.value, Color.TRANSPARENT)
            }
            answer(model, Confirmation.APPLY, true)
            val rotated = checkNotNull(model.state.value.photo.frame)
            check(rotated.info.metadata.containsAll(capture) && model.state.value.photo.pending.isEmpty())
            for (y in 0 until source.height) for (x in 0 until source.width)
                check(rotated.pixels!!.getPixel(y, x) == source.getPixel(x, y))
            instrumentation.runOnMainSync { model.undo(-1) }
            idle(model)
            check(model.state.value.photo.frame === cropped)
            check(model.state.value.photo.pending == listOf(PhotoTool.ROTATE))
            check(model.state.value.photo.editor!!.rotationHistory.canUndo)
            instrumentation.runOnMainSync { model.undo(1) }
            answer(model, Confirmation.REDO, true)
            check(model.state.value.photo.frame === rotated)
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
                            else ColorSpace.Named.DISPLAY_P3
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
            val retained = bitmap.allocationByteCount.toLong()
            val history = PhotoHistory(directory, first, retained * 2, retainedBytes = { retained })
            val secondBitmap =
                checkNotNull(bitmap.copy(config, true)).apply { eraseColor(Color.WHITE) }
            val second = PhotoFrame(null, Photos.rasterInfo(secondBitmap), secondBitmap)
            try {
                val abandoned = history.copy(Rect(0, 0, bitmap.width, bitmap.height))
                check(abandoned === bitmap && first.pixels == null)
                abandoned.eraseColor(Color.BLACK)
                abandoned.recycle()
                check(pixels(history.bitmap()).contentEquals(expected))
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
                history.append(first)
                check(history.residentBytes == first.pixels!!.allocationByteCount.toLong())
                history.move(0)
                val retained = history.bitmap()
                val cropped = history.copy(Rect(0, 0, 2, 2))
                check(first.pixels === retained && cropped !== retained)
                cropped.recycle()
                val branchBitmap = history.copy()
                check(branchBitmap === retained && first.pixels == null)
                branchBitmap.eraseColor(Color.BLACK)
                check(pixels(history.bitmap()).contentEquals(expected))
                val branch = PhotoFrame(null, Photos.rasterInfo(branchBitmap), branchBitmap)
                val discarded = second.files
                history.append(branch)
                check(history.frames == listOf(first, branch) && discarded.none { it.exists() })

                first.snapshot!!.writeBytes(byteArrayOf(0))
                check(runCatching { history.bitmap(first) }.exceptionOrNull() is IOException)
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
            check(runCatching { history.copy() }.isFailure)
            check(frame.pixels === bitmap && !bitmap.isRecycled && frame.snapshot == null)
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

    private suspend fun answer(model: LabViewModel, action: Confirmation, discard: Boolean) {
        withTimeout(30_000) { model.state.first { it.confirmation == action } }
        instrumentation.runOnMainSync { model.answerConfirmation(discard) }
        idle(model)
        check(model.state.value.confirmation == null)
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

    suspend fun raw() {
        val store = ViewModelStore()
        lateinit var model: LabViewModel
        val input = File(app.filesDir, "mosaic.dng")
        instrumentation.context.assets.open(input.name).use { source ->
            input.outputStream().use { source.copyTo(it) }
        }
        try {
            instrumentation.runOnMainSync {
                model = LabViewModel(app)
                store.put("raw-test", model)
                model.openRaw(Uri.fromFile(input))
            }
            idle(model)
            val photo = model.state.value.photo
            val frame = checkNotNull(photo.frame)
            val pixels = checkNotNull(frame.pixels)
            check(frame.source == null && frame.files.isEmpty())
            val info = checkNotNull(photo.info)
            check(photo.name == input.name && photo.sourceUri == Uri.fromFile(input))
            check(photo.sourceBytes == input.length() && photo.sourceMime != "image/png")
            check(info.width == 64 && info.height == 96 && info.orientation == 1)
            check(
                info.mime == "image/png" &&
                    info.colorSpace in
                        listOf(
                            ColorSpace.get(ColorSpace.Named.SRGB),
                            ColorSpace.get(ColorSpace.Named.EXTENDED_SRGB),
                        )
            ) {
                "Unexpected RAW color: ${info.color}"
            }
            check(info.metadata.contains(ExifInterface.TAG_MAKE to "MediaLab"))
            val file = Photos.export(app.filesDir, pixels, info, ExportOptions(format = ImageFormat.PNG))
            check(info.config == Bitmap.Config.RGBA_F16 && file.readBytes()[24].toInt() == 16) {
                "RAW depth: ${info.config}, PNG ${file.readBytes()[24]}"
            }
            check(ExifInterface(file).getAttribute(ExifInterface.TAG_MAKE) == "MediaLab")
            val decoded = Photos.decode(file, Photos.info(file))
            try {
                check(!decoded.hasAlpha())
                check(decoded.getColor(32, 20) != decoded.getColor(32, 70))
            } finally {
                decoded.recycle()
            }
            instrumentation.runOnMainSync { model.clearCache() }
            idle(model)
            check(!pixels.isRecycled && model.state.value.photo.frame === frame)
            instrumentation.runOnMainSync { model.crop(Rect(2, 3, 32, 43), false) }
            idle(model)
            check(model.state.value.photo.info?.width == 30)
            check(model.state.value.photo.sourceBytes == input.length())
            val work = File(app.cacheDir, "work").listFiles()!!.single { it.isDirectory }
            val files = work.listFiles()!!.toSet()
            instrumentation.runOnMainSync { model.openRaw(Uri.fromFile(input)) }
            answer(model, Confirmation.IMAGE, false)
            check(work.listFiles()!!.toSet() == files && model.state.value.photo.position == 1)
            instrumentation.runOnMainSync { model.undo(-1) }
            idle(model)
            check(model.state.value.photo.frame === photo.frame)
            val invalid = File(app.cacheDir, "invalid.raw").apply { writeText("not a RAW image") }
            instrumentation.runOnMainSync { model.openRaw(Uri.fromFile(invalid)) }
            answer(model, Confirmation.IMAGE, true)
            check(model.state.value.notice?.message == R.string.raw_unsupported)
            check(model.state.value.photo.frame === photo.frame)
            check(work.listFiles()!!.all { it in model.state.value.workingFiles })
            instrumentation.runOnMainSync { model.undo(1) }
            idle(model)
            check(model.state.value.photo.info?.width == 30)
            var checks = 0
            var cancelAt = Int.MAX_VALUE
            val cancelled = Job()
            val cancellation = object : CoroutineContext by cancelled {
                override fun <E : CoroutineContext.Element> get(key: CoroutineContext.Key<E>): E? {
                    if (key == Job && ++checks == cancelAt) cancelled.cancel()
                    return cancelled[key]
                }
            }
            val decoder = RawDecoder(cancellation)
            checkNotNull(decoder.decode(input.path, 64L * 1024 * 1024)).recycle()
            // The final checks occur while the output bitmap's pixels are locked.
            cancelAt = checks - 1
            checks = 0
            try {
                decoder.decode(input.path, 64L * 1024 * 1024)
                error("Cancelled RAW decoding succeeded")
            } catch (_: kotlinx.coroutines.CancellationException) {}
        } finally {
            instrumentation.runOnMainSync { store.clear() }
        }
    }

    suspend fun imports() {
        val store = ViewModelStore()
        lateinit var model: LabViewModel
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
            instrumentation.runOnMainSync {
                model = LabViewModel(app)
                store.put("import-test", model)
                check(!model.state.value.busy) { "Startup cleanup blocks the workspace" }
                model.open(Uri.fromFile(image), ImportType.IMAGE)
            }
            idle(model)
            val draft = checkNotNull(model.state.value.photo.editor)

            instrumentation.runOnMainSync {
                draft.cropHistory.change(FrameSelection(Rect(2, 2, 20, 20)))
                model.open(Uri.fromFile(image), ImportType.IMAGE)
            }
            answer(model, Confirmation.IMAGE, false)
            check(model.state.value.photo.editor === draft && model.state.value.photo.history.size == 1)
            instrumentation.runOnMainSync { model.crop(Rect(2, 2, 20, 20), false) }
            idle(model)
            val photo = model.state.value.photo
            check(photo.sourceUri == Uri.fromFile(image))
            check(photo.history.first().info.bytes == image.length())
            val work = photo.history.first().source!!.parentFile!!
            val files = work.listFiles()!!.toSet()
            for (type in listOf(ImportType.IMAGE, null)) {
                instrumentation.runOnMainSync { model.open(Uri.fromFile(image), type) }
                answer(model, Confirmation.IMAGE, false)
                check(model.state.value.photo === photo && work.listFiles()!!.toSet() == files)
            }
            instrumentation.runOnMainSync {
                model.open(Uri.fromFile(audio), ImportType.AUDIO)
            }
            idle(model)
            check(model.state.value.photo === photo && model.state.value.audio != null)
            val tag = TagDraft("TITLE", emptyList()).apply { text = "Unsaved" }
            instrumentation.runOnMainSync {
                model.state.value.audio!!.editor.tag = tag
                model.open(Uri.fromFile(audio), ImportType.AUDIO)
            }
            answer(model, Confirmation.AUDIO, false)
            check(model.state.value.audio!!.editor.tag === tag && !model.state.value.audioDirty)
            instrumentation.runOnMainSync {
                checkNotNull(model.state.value.audio).editor.tags.change(mapOf("TITLE" to listOf("Unsaved")))
                model.applyAudioMetadata(AudioTool.TAGS)
                check(model.state.value.audio!!.editor.tag === tag)
                model.state.value.audio!!.editor.tag = null
            }
            idle(model)
            val editedAudio = checkNotNull(model.state.value.audio)
            check(editedAudio.sourceUri == Uri.fromFile(audio))
            check(editedAudio.sourceBytes == audio.length())
            check(model.state.value.audioDirty)
            instrumentation.runOnMainSync { model.restoreAudio() }
            idle(model)
            check(!model.state.value.audioDirty && model.state.value.notice?.message == R.string.original_audio_restored)
            instrumentation.runOnMainSync { model.clearNotice(); model.restoreAudio() }
            idle(model)
            check(model.state.value.notice == null)
            instrumentation.runOnMainSync { model.undoAudio(-1) }
            idle(model)
            check(model.state.value.audioDirty)
            check(model.state.value.notice == Notice(R.string.undone, argumentRes = R.string.restore_original_audio))
            val before = work.listFiles()!!.toSet()
            for (type in listOf(ImportType.AUDIO, null)) {
                instrumentation.runOnMainSync { model.open(Uri.fromFile(audio), type) }
                answer(model, Confirmation.AUDIO, false)
                check(model.state.value.audio === editedAudio && model.state.value.audioDirty)
                check(work.listFiles()!!.toSet() == before)
            }
            instrumentation.runOnMainSync {
                model.open(Uri.fromFile(image), ImportType.AUDIO)
            }
            idle(model)
            check(model.state.value.notice?.message == R.string.select_audio_only)
            check(model.state.value.audio === editedAudio && work.listFiles()!!.toSet() == before)
            for ((name, content, expected) in listOf(
                    Triple("empty.bin", byteArrayOf(), R.string.empty_media_file),
                    Triple("document.txt", "not media".toByteArray(), R.string.select_media_only),
                    Triple("fake.mp4", "not a video".toByteArray(), R.string.select_media_only),
                    Triple("broken.png", image.readBytes().take(48).toByteArray(), R.string.select_media_only),
                )) {
                val invalid = File(app.filesDir, name).apply { writeBytes(content) }
                instrumentation.runOnMainSync { model.clearNotice(); model.open(Uri.fromFile(invalid), null) }
                idle(model)
                check(model.state.value.notice?.message == expected) { "Unexpected import result: $name" }
                check(model.state.value.photo === photo && model.state.value.audio === editedAudio)
                check(work.listFiles()!!.toSet() == before)
            }
            instrumentation.runOnMainSync { model.open(Uri.fromFile(audio), null) }
            withTimeout(30_000) { model.state.first { it.confirmation == Confirmation.AUDIO } }
            instrumentation.runOnMainSync { model.cancel() }
            idle(model)
            check(model.state.value.confirmation == null && model.state.value.audio === editedAudio)
            check(work.listFiles()!!.toSet() == before)
            instrumentation.runOnMainSync { model.open(Uri.fromFile(audio), null) }
            answer(model, Confirmation.AUDIO, true)
            check(!model.state.value.audioDirty && model.state.value.audio != null)
            check(!editedAudio.file.exists() && model.state.value.photo === photo)
            instrumentation.runOnMainSync { model.open(Uri.fromFile(image), null) }
            answer(model, Confirmation.IMAGE, true)
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
        val input = File(app.filesDir, "fixture.bin")
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
            val originalBytes = input.readBytes()
            val info = Photos.info(input)
            check(PhotoMetadata.normalize(app.cacheDir, mapOf("GPSLatitude" to "31/1,12/1,0/1"))["GPSLatitude"] != null)
            check(runCatching { PhotoMetadata.normalize(app.cacheDir, mapOf("GPSLatitude" to "91/1,0/1,0/1")) }.isFailure)
            check(info.color == PhotoColor("sRGB", "YCbCr", false, false))
            for ((x, y) in listOf(0 to 0, 16 to 8, 63 to 47))
                check(Photos.pixel(input, info, x, y) == reference.getPixel(x, y)) {
                    "JPEG pixel at $x, $y"
                }
            for (clear in listOf(false, true)) {
                val converted = Photos.export(
                    app.cacheDir, input, Photos.info(input), ExportOptions(format = ImageFormat.PNG, metadata = clear),
                )
                try { checkCapture(converted, !clear) } finally { converted.delete() }
            }
            idle(model)
            instrumentation.runOnMainSync {
                model.open(Uri.fromFile(input), ImportType.IMAGE)
            }
            idle(model)
            val draft = checkNotNull(model.state.value.photo.editor)
            instrumentation.runOnMainSync {
                draft.cropHistory.change(FrameSelection(Rect(1, 2, 32, 24)))
                model.editPhotoMetadata(mapOf(ExifInterface.TAG_SOFTWARE to "Edited camera")) {}
            }
            idle(model)
            instrumentation.runOnMainSync { model.editPhotoMetadata(mapOf(ExifInterface.TAG_DATETIME_ORIGINAL to null)) {} }
            idle(model)
            for (direction in listOf(-1, 1)) {
                instrumentation.runOnMainSync { model.undo(direction) }
                idle(model)
                check(model.state.value.photo.editor === draft && draft.cropHistory.canUndo)
                check(model.state.value.photo.pending == listOf(PhotoTool.CROP))
            }
            val metadataFrame = checkNotNull(model.state.value.photo.frame)
            for (format in listOf(ImageFormat.ORIGINAL, ImageFormat.PNG, ImageFormat.WEBP_LOSSLESS)) {
                val exported = Photos.export(app.cacheDir, checkNotNull(metadataFrame.source), metadataFrame.info, ExportOptions(format))
                check(exported.extension == if (format == ImageFormat.ORIGINAL) "jpg" else format.extension)
                val exif = ExifInterface(exported)
                check(exif.getAttribute(ExifInterface.TAG_SOFTWARE) == "Edited camera")
                check(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) == null)
                exported.delete()
            }
            check(input.readBytes().contentEquals(originalBytes))
            val croppedMetadata = File(app.cacheDir, "metadata-crop.jpg")
            JpegNative.crop(checkNotNull(metadataFrame.source).path, croppedMetadata.path, 0, 0, 16, 16)
            PhotoMetadata.write(croppedMetadata, metadataFrame.info.metadata, 16, 16, metadataFrame.info.metadataEdits)
            check(ExifInterface(croppedMetadata).getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) == null)
            check(ExifInterface(croppedMetadata).getAttribute(ExifInterface.TAG_SOFTWARE) == "Edited camera")
            repeat(2) { instrumentation.runOnMainSync { model.undo(-1) }; idle(model) }
            instrumentation.runOnMainSync { draft.cropHistory.undo() }
            val mask = CutoutMask.Inferred(FloatArray(ImageMath.SIZE * ImageMath.SIZE) { 0.5f }, BackgroundModel.RMBG20)
            val maskPreview = Photos.maskPreview(mask, reference.width, reference.height)
            val cutout = CutoutDraft(mask, model.state.value.photo.preview!!, maskPreview)
            instrumentation.runOnMainSync {
                check(draft.backgroundModel == BackgroundModel.RMBG14)
                draft.cutout = cutout
                draft.backgroundModel = BackgroundModel.RMBG20
                draft.cropHistory.change(FrameSelection(Rect(0, 0, 16, 16)))
                draft.resize.width = 32
                draft.resize.height = 24
                draft.resize.method = 0
                draft.export = ExportOptions(format = ImageFormat.JPEG, quality = 75)
                draft.cropHistory.undo()
            }
            checkPicking(model, reference)
            check(model.state.value.photo.editor === draft)
            check(draft.cropHistory.canRedo && draft.resize.width == 32 && draft.resize.method == 0)
            instrumentation.runOnMainSync {
                draft.cropHistory.redo()
                check(draft.cropHistory.value.bounds == Rect(0, 0, 16, 16))
                model.pick(3, 2)
                model.pick(9, 7)
            }
            for (apply in listOf<() -> Unit>(
                { model.crop(Rect(0, 0, 16, 16), true) },
                { model.applyBackground() },
            )) {
                val resident = model.state.value.photo.frame!!.pixels
                instrumentation.runOnMainSync(apply)
                answer(model, Confirmation.APPLY, false)
                check(model.state.value.photo.editor === draft && model.state.value.photo.position == 0)
                check(model.state.value.photo.frame!!.pixels === resident)
                check(draft.cropHistory.value.bounds == Rect(0, 0, 16, 16) && draft.resize.width == 32)
            }
            for ((confirmation, action) in listOf<Pair<Confirmation, () -> Unit>>(
                Confirmation.REINFER to { model.removeBackground(BackgroundModel.RMBG20) },
                Confirmation.DISCARD_MASK to model::discardBackground,
            )) {
                instrumentation.runOnMainSync(action)
                answer(model, confirmation, false)
                check(draft.cutout === cutout && cutout.history.value.isEmpty())
            }
            instrumentation.runOnMainSync { model.storeMask() }
            idle(model)
            val stored = checkNotNull(model.state.value.storedMask)
            check(stored.width == reference.width && stored.height == reference.height)
            check(model.state.value.notice?.message == R.string.mask_stored)
            instrumentation.runOnMainSync { model.loadMask() }
            answer(model, Confirmation.LOAD_MASK, false)
            check(draft.cutout === cutout)
            instrumentation.runOnMainSync { model.discardBackground() }
            answer(model, Confirmation.DISCARD_MASK, true)
            check(draft.cutout == null && model.state.value.photo.backgroundElapsedMs == null)
            instrumentation.runOnMainSync {
                draft.cutout = cutout
                cutout.history.change(listOf(Stroke(listOf(1f to 1f), Color.WHITE, 2f, CoverShape.BRUSH)))
                model.crop(Rect(0, 0, 16, 16), true)
            }
            answer(model, Confirmation.APPLY, true)
            check(model.state.value.photo.picked == null)
            val applied = checkNotNull(model.state.value.photo.editor)
            check(applied !== draft && !applied.cropHistory.canUndo && !applied.cropHistory.canRedo)
            check(applied.resize.width == 16 && applied.resize.height == 16 && applied.export == draft.export)
            check(applied.backgroundModel == BackgroundModel.RMBG20)
            checkCapture(checkNotNull(model.state.value.photo.frame?.source))
            instrumentation.runOnMainSync { model.loadMask() }
            answer(model, Confirmation.RESIZE_MASK, false)
            check(applied.cutout == null)
            instrumentation.runOnMainSync { model.loadMask() }
            answer(model, Confirmation.RESIZE_MASK, true)
            check(applied.cutout?.mask === stored && applied.cutout?.showMask == true)
            instrumentation.runOnMainSync { model.discardBackground() }
            answer(model, Confirmation.DISCARD_MASK, true)
            for (direction in listOf(-1, 1)) {
                val before = model.state.value.photo
                val pending = before.pending + PhotoTool.COVER
                val editor = checkNotNull(before.editor)
                val strokes = listOf(Stroke(listOf(0f to 0f, 4f to 4f), Color.WHITE, 1f, CoverShape.RECTANGLE))
                val action = if (direction < 0) Confirmation.UNDO else Confirmation.REDO
                instrumentation.runOnMainSync {
                    editor.coverHistory.change(strokes)
                    model.undo(-direction)
                }
                idle(model)
                check(model.state.value.photo === before && editor.coverHistory.value == strokes)
                instrumentation.runOnMainSync { model.undo(direction) }
                answer(model, action, false)
                check(model.state.value.photo === before && editor.coverHistory.value == strokes)
                check(model.state.value.photo.pending == pending)
                instrumentation.runOnMainSync { model.undo(direction) }
                answer(model, action, true)
                check(model.state.value.photo.position == before.position + direction)
                check(model.state.value.photo.sourceUri == Uri.fromFile(input))
                val restored = checkNotNull(model.state.value.photo.editor)
                check(restored !== editor)
                if (direction < 0) {
                    check(model.state.value.photo.pending == listOf(PhotoTool.CROP))
                    check(restored.tool == PhotoTool.CROP && restored.cropHistory.canUndo)
                    check(restored.cropHistory.value == draft.cropHistory.value)
                } else check(model.state.value.photo.pending.isEmpty())
            }
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
            val coverDraft = checkNotNull(model.state.value.photo.editor)
            instrumentation.runOnMainSync {
                coverDraft.cropHistory.change(FrameSelection(Rect(0, 0, info.width, info.height)))
                coverDraft.coverHistory.change(listOf(stroke))
                coverDraft.coverHistory.change(listOf(stroke, stroke.copy(color = Color.BLACK)))
                coverDraft.coverHistory.undo()
                model.cover(coverDraft.coverHistory.value)
            }
            idle(model)
            val edited = checkNotNull(model.state.value.photo.frame)
            check(edited.source == null && edited.pixels!!.sameAs(reference))
            instrumentation.runOnMainSync { model.undo(-1) }
            idle(model)
            val restoredCover = checkNotNull(model.state.value.photo.editor)
            check(
                restoredCover.tool == PhotoTool.COVER &&
                    model.state.value.photo.pending == listOf(PhotoTool.COVER)
            )
            check(restoredCover.coverHistory.value == listOf(stroke))
            check(restoredCover.coverHistory.canUndo && restoredCover.coverHistory.canRedo)
            instrumentation.runOnMainSync {
                restoredCover.coverHistory.redo()
                model.undo(1)
            }
            answer(model, Confirmation.REDO, false)
            check(
                model.state.value.photo.editor === restoredCover &&
                    restoredCover.coverHistory.value.size == 2
            )
            instrumentation.runOnMainSync { model.undo(1) }
            answer(model, Confirmation.REDO, true)
            check(
                model.state.value.photo.frame === edited &&
                    model.state.value.photo.pending.isEmpty()
            )
            instrumentation.runOnMainSync { model.undo(-1) }
            idle(model)
            check(model.state.value.photo.editor!!.coverHistory.value == listOf(stroke))
            instrumentation.runOnMainSync { model.undo(1) }
            answer(model, Confirmation.REDO, true)
            instrumentation.runOnMainSync {
                model.removeBackground(BackgroundModel.RMBG20)
            }
            answer(model, Confirmation.TRANSPARENT_INPUT, false)
            check(model.state.value.photo.frame === edited && edited.pixels!!.sameAs(reference))
            val work = model.state.value.photo.history.first().source!!.parentFile!!
            val files = work.listFiles()!!.toSet()
            var preview: Bitmap? = null
            instrumentation.runOnMainSync { model.previewResize(32, 24, 2) { preview = it } }
            idle(model)
            val expectedPreview = checkNotNull(preview)
            instrumentation.runOnMainSync { model.previewResize(32, 24, 2) { preview = it } }
            idle(model)
            check(preview === expectedPreview)
            instrumentation.runOnMainSync {
                model.state.value.photo.editor!!.resize.apply {
                    width = 32
                    height = 24
                }
                model.resize(32, 24, 2)
            }
            idle(model)
            val resized = checkNotNull(model.state.value.photo.frame)
            check(model.state.value.photo.preview === expectedPreview)
            check(work.listFiles()!!.toSet() == files)
            val expected = Photos.resize(reference, 32, 24, 2)
            try {
                check(resized.pixels!!.sameAs(expected))
            } finally {
                expected.recycle()
            }
            instrumentation.runOnMainSync { model.undo(-1) }
            idle(model)
            check(model.state.value.photo.frame === edited && edited.pixels!!.sameAs(reference))
            instrumentation.runOnMainSync { model.undo(-1) }
            answer(model, Confirmation.UNDO, true)
            check(model.state.value.photo.pending == listOf(PhotoTool.COVER))
            instrumentation.runOnMainSync { model.undo(1) }
            answer(model, Confirmation.REDO, true)
            check(
                model.state.value.photo.frame === edited &&
                    model.state.value.photo.pending == listOf(PhotoTool.RESIZE)
            )
            check(model.state.value.photo.editor!!.resize.width == 32)
            instrumentation.runOnMainSync { model.undo(1) }
            answer(model, Confirmation.REDO, true)
            check(model.state.value.photo.frame === resized)
            instrumentation.runOnMainSync { model.undo(-1) }
            idle(model)
            instrumentation.runOnMainSync {
                model.state.value.photo.editor!!.resize.apply {
                    width = reference.width
                    height = reference.height
                }
                model.crop(Rect(0, 0, 16, 16), false)
            }
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
            val exportEditor = checkNotNull(model.state.value.photo.editor)
            instrumentation.runOnMainSync {
                exportEditor.cropHistory.change(FrameSelection(Rect(1, 1, 8, 8)))
            }
            var previewShown = false
            for (action in listOf<() -> Unit>(
                { model.previewExport(ExportOptions()) { previewShown = true } },
                { model.shareImage(ExportOptions()) { error("Unexpected share") } },
                { model.saveImage(ExportOptions()) },
            )) {
                instrumentation.runOnMainSync(action)
                answer(model, Confirmation.EXPORT, false)
                check(!previewShown && model.state.value.exportFile == export)
                check(model.state.value.photo.pending == listOf(PhotoTool.CROP))
            }
            instrumentation.runOnMainSync { model.previewExport(ExportOptions()) { previewShown = true } }
            answer(model, Confirmation.EXPORT, true)
            check(previewShown && model.state.value.exportFile == export)
            check(model.state.value.photo.pending == listOf(PhotoTool.CROP))
            instrumentation.runOnMainSync { exportEditor.cropHistory.undo() }
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
            instrumentation.runOnMainSync {
                val editor = checkNotNull(model.state.value.photo.editor)
                editor.cleanup = editor.cleanup.copy(static = false, flatten = false)
                model.clean(false, false, false, true)
            }
            idle(model)
            check(model.state.value.photo.frame === beforeClean && model.state.value.photo.pending.isEmpty())
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
            check(model.state.value.notice == Notice(R.string.undone, argumentRes = R.string.clean))
            check(model.state.value.photo.pending.isEmpty())
            instrumentation.runOnMainSync { model.undo(1) }
            idle(model)
            check(model.state.value.photo.frame === cleaned && cleaned.info.metadata.isEmpty())
            check(model.state.value.notice == Notice(R.string.redone, argumentRes = R.string.clean))
            instrumentation.runOnMainSync { model.resize(16, 16, 0) }
            idle(model)
            instrumentation.runOnMainSync { model.previewExport(ExportOptions(format = ImageFormat.JPEG)) {} }
            idle(model)
            checkCapture(checkNotNull(model.state.value.exportFile), false)
            val beforeRestore = model.state.value.photo
            val original = beforeRestore.history.first()
            instrumentation.runOnMainSync {
                beforeRestore.editor!!.resize.width++
                model.restoreOriginal()
            }
            answer(model, Confirmation.RESTORE, false)
            check(model.state.value.photo === beforeRestore && beforeRestore.pending == listOf(PhotoTool.RESIZE))
            instrumentation.runOnMainSync { model.restoreOriginal() }
            answer(model, Confirmation.RESTORE, true)
            check(model.state.value.photo.frame === original && model.state.value.photo.pending.isEmpty())
            check(model.state.value.photo.info!!.color == info.color)
            check(model.state.value.notice?.message == R.string.original_image_restored)
            check(original.source!!.readBytes().contentEquals(originalBytes))
            val restoredSize = model.state.value.photo.history.size
            instrumentation.runOnMainSync { model.restoreOriginal() }
            idle(model)
            check(model.state.value.photo.history.size == restoredSize)
            instrumentation.runOnMainSync { model.undo(-1) }
            idle(model)
            check(model.state.value.photo.frame === beforeRestore.frame)
            check(model.state.value.notice == Notice(R.string.undone, argumentRes = R.string.restore_original))
            instrumentation.runOnMainSync { model.undo(1) }
            idle(model)
            check(model.state.value.photo.frame === original)
            check(model.state.value.notice == Notice(R.string.redone, argumentRes = R.string.restore_original))
            val editor = checkNotNull(model.state.value.photo.editor)
            instrumentation.runOnMainSync {
                editor.cutout = cutout
                model.applyBackground()
            }
            idle(model)
            val cut = checkNotNull(model.state.value.photo.frame)
            check(model.state.value.photo.history.size == restoredSize + 1)
            check(model.state.value.photo.pending.isEmpty())
            check(cut.info.metadata.containsAll(capture))
            instrumentation.runOnMainSync { model.undo(-1) }
            idle(model)
            check(model.state.value.photo.frame === original)
            val restoredCutout = checkNotNull(model.state.value.photo.editor?.cutout)
            check(restoredCutout !== cutout && restoredCutout.mask === mask)
            check(restoredCutout.maskPreview.sameAs(cutout.maskPreview))
            check(
                restoredCutout.history.value == cutout.history.value &&
                    restoredCutout.history.canUndo
            )
            check(model.state.value.photo.pending == listOf(PhotoTool.BACKGROUND))
            instrumentation.runOnMainSync { model.undo(-1) }
            answer(model, Confirmation.UNDO, true)
            instrumentation.runOnMainSync { model.undo(1) }
            idle(model)
            val redoneDraft = checkNotNull(model.state.value.photo.editor?.cutout)
            check(model.state.value.photo.pending == listOf(PhotoTool.BACKGROUND))
            check(redoneDraft.history.value == cutout.history.value && redoneDraft.history.canUndo)
            instrumentation.runOnMainSync { model.undo(1) }
            answer(model, Confirmation.REDO, true)
            check(model.state.value.photo.frame === cut)
            instrumentation.runOnMainSync { model.open(Uri.fromFile(input), ImportType.IMAGE) }
            answer(model, Confirmation.IMAGE, true)
            check(model.state.value.storedMask === stored)
            instrumentation.runOnMainSync { model.loadMask() }
            idle(model)
            check(model.state.value.photo.editor!!.cutout?.mask === stored)
        } finally {
            reference.recycle()
            instrumentation.runOnMainSync { store.clear() }
        }
    }
}
