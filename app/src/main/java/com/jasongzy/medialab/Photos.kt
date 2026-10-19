package com.jasongzy.medialab

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class PhotoColor(
    val space: String? = null,
    val model: String? = null,
    val wide: Boolean? = null,
    val hdr: Boolean? = null,
    val convertsToSrgb: Boolean = false,
) {
    fun losses(forceSrgb: Boolean = false): List<Int> =
        listOfNotNull(
            (if (wide == true) R.string.wide_gamut else R.string.source_color_space)
                .takeIf { (convertsToSrgb || forceSrgb) && space != null && space != "sRGB" },
            R.string.hdr.takeIf { hdr == true },
        )
}

internal data class PhotoInfo(
    val width: Int,
    val height: Int,
    val rawWidth: Int,
    val rawHeight: Int,
    val orientation: Int,
    val mime: String,
    val bytes: Long?,
    val color: PhotoColor,
    val container: PhotoContainer?,
    val metadata: List<Pair<String, String>>,
    val colorSpace: ColorSpace? = null,
    val config: Bitmap.Config = Bitmap.Config.ARGB_8888,
)

internal enum class CoverShape(val label: Int) {
    RECTANGLE(R.string.rectangle),
    ELLIPSE(R.string.ellipse),
    LASSO(R.string.lasso),
    BRUSH(R.string.brush),
}

internal data class Stroke(
    val points: List<Pair<Float, Float>>,
    val color: Int,
    val width: Float,
    val shape: CoverShape,
) {
    val fill: Boolean
        get() = shape != CoverShape.BRUSH

    val isDrawable: Boolean
        get() =
            when (shape) {
                CoverShape.BRUSH -> points.isNotEmpty()
                CoverShape.LASSO -> points.size >= 3
                CoverShape.RECTANGLE,
                CoverShape.ELLIPSE ->
                    points.size >= 2 &&
                        points.first().first != points.last().first &&
                        points.first().second != points.last().second
            }

    val clearsPixels: Boolean
        get() = color ushr 24 == 0

    fun path(closed: Boolean = fill): Path =
        Path().apply {
            if (points.isEmpty()) return@apply
            when (shape) {
                CoverShape.RECTANGLE,
                CoverShape.ELLIPSE -> {
                    val left = minOf(points.first().first, points.last().first)
                    val top = minOf(points.first().second, points.last().second)
                    val right = maxOf(points.first().first, points.last().first)
                    val bottom = maxOf(points.first().second, points.last().second)
                    if (shape == CoverShape.RECTANGLE)
                        addRect(left, top, right, bottom, Path.Direction.CW)
                    else addOval(left, top, right, bottom, Path.Direction.CW)
                }
                CoverShape.LASSO,
                CoverShape.BRUSH -> {
                    moveTo(points.first().first, points.first().second)
                    points.drop(1).forEach { lineTo(it.first, it.second) }
                    if (closed) close()
                }
            }
        }
}

internal enum class ImageFormat(val extension: String, val label: Int) {
    ORIGINAL("", R.string.original_format),
    PNG("png", R.string.png),
    JPEG("jpg", R.string.jpeg),
    WEBP_LOSSLESS("webp", R.string.webp_lossless),
    WEBP_LOSSY("webp", R.string.webp_lossy),
}

internal data class ExportOptions(
    val format: ImageFormat = ImageFormat.ORIGINAL,
    val quality: Int = 90,
    val metadata: Boolean = false,
    val static: Boolean = false,
    val flatten: Boolean = false,
    val keepColor: Boolean = true,
    val background: Int = -1,
) {
    fun preservesSource(info: PhotoInfo): Boolean =
        format == ImageFormat.ORIGINAL && keepColor &&
            (!metadata || (info.mime in listOf("image/jpeg", "image/png", "image/webp") && info.orientation == 1))
}

internal object JpegNative {
    init {
        System.loadLibrary("medialab")
    }

    external fun crop(input: String, output: String, x: Int, y: Int, width: Int, height: Int)
}

internal object Photos {
    fun info(file: File, preview: Bitmap? = null): PhotoInfo {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)
        require(options.outWidth > 0 && options.outHeight > 0) { "Unsupported image format" }
        val exif = ExifInterface(file)
        val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1).coerceIn(1, 8)
        val swapped = orientation in 5..8
        val metadata =
            ExifInterface::class
                .java
                .fields
                .filter {
                    it.name.startsWith("TAG_") && it.type == String::class.java
                }
                .mapNotNull { field ->
                    val tag = field.get(null) as String
                    exif.getAttribute(tag)?.let { tag to it }
                }
                .distinctBy { it.first }
                .sortedBy { it.first }
        val container = Containers.inspect(file)
        val mime = options.outMimeType ?: "image/*"
        return PhotoInfo(
            if (swapped) options.outHeight else options.outWidth,
            if (swapped) options.outWidth else options.outHeight,
            options.outWidth,
            options.outHeight,
            orientation,
            mime,
            file.length(),
            colorInfo(
                options.outColorSpace ?: preview?.colorSpace,
                container?.jpeg?.colorModel ?: RasterMetadata.colorModel(file, mime),
                container?.gainmap != null || (Build.VERSION.SDK_INT >= 34 && preview?.hasGainmap() == true),
            ),
            container,
            metadata,
            options.outColorSpace ?: preview?.colorSpace,
            if (options.outConfig == Bitmap.Config.RGBA_F16 || preview?.config == Bitmap.Config.RGBA_F16 ||
                (Build.VERSION.SDK_INT >= 33 && options.outConfig == Bitmap.Config.RGBA_1010102))
                Bitmap.Config.RGBA_F16 else Bitmap.Config.ARGB_8888,
        )
    }

    fun preview(file: File): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            require(!info.isAnimated) { "Choose a still image" }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val scale = minOf(1.0, 1800.0 / maxOf(info.size.width, info.size.height))
            decoder.setTargetSize(
                maxOf(1, (info.size.width * scale).toInt()),
                maxOf(1, (info.size.height * scale).toInt()),
            )
        }

    fun preview(bitmap: Bitmap): Bitmap {
        val scale = minOf(1.0, 1800.0 / maxOf(bitmap.width, bitmap.height))
        val preview = Bitmap.createScaledBitmap(
            bitmap,
            maxOf(1, (bitmap.width * scale).toInt()),
            maxOf(1, (bitmap.height * scale).toInt()),
            true,
        )
        return if (preview === bitmap) checkNotNull(bitmap.copy(bitmap.config!!, false)) else preview
    }

    fun rasterInfo(bitmap: Bitmap, metadata: List<Pair<String, String>> = emptyList()) = PhotoInfo(
        bitmap.width,
        bitmap.height,
        bitmap.width,
        bitmap.height,
        1,
        "image/png",
        null,
        colorInfo(bitmap.colorSpace, bitmap.colorSpace?.model?.name, Build.VERSION.SDK_INT >= 34 && bitmap.hasGainmap()),
        null,
        PhotoMetadata.retained(metadata),
        bitmap.colorSpace,
        bitmap.config!!,
    )

    private fun colorInfo(space: ColorSpace?, model: String?, gainmap: Boolean): PhotoColor {
        val hdrSpace = if (Build.VERSION.SDK_INT >= 34) when (space) {
            ColorSpace.get(ColorSpace.Named.BT2020_HLG) -> "BT.2020 HLG"
            ColorSpace.get(ColorSpace.Named.BT2020_PQ) -> "BT.2020 PQ"
            else -> null
        } else null
        return PhotoColor(
            hdrSpace ?: if (space?.isSrgb == true) "sRGB" else space?.name,
            model,
            space?.isWideGamut,
            if (gainmap || hdrSpace != null) true else space?.let { false },
            space != null && (hdrSpace != null || space !is ColorSpace.Rgb || space.transferParameters == null),
        )
    }

    private fun budget(width: Int, height: Int, copies: Int = 2) {
        require(
            width > 0 &&
                height > 0 &&
                width.toLong() * height * 4 * copies < Runtime.getRuntime().maxMemory() * 0.6
        ) {
            "Image operation exceeds the memory budget: $width × $height"
        }
    }

    fun decode(file: File, info: PhotoInfo, crop: Rect? = null): Bitmap {
        val space = if (info.color.convertsToSrgb) ColorSpace.get(ColorSpace.Named.SRGB)
            else info.colorSpace ?: ColorSpace.get(ColorSpace.Named.SRGB)
        fun finish(bitmap: Bitmap): Bitmap {
            if (Build.VERSION.SDK_INT >= 34) bitmap.gainmap = null
            if (info.color.convertsToSrgb && bitmap.config != Bitmap.Config.ARGB_8888)
                return try { convert(bitmap, ColorSpace.get(ColorSpace.Named.SRGB)) } finally { bitmap.recycle() }
            if (bitmap.config == Bitmap.Config.ARGB_8888 || bitmap.config == Bitmap.Config.RGBA_F16) return bitmap
            return try { checkNotNull(bitmap.copy(info.config, true)) } finally { bitmap.recycle() }
        }
        fun decodeImage(): Bitmap =
            finish(ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, image, _ ->
                require(!image.isAnimated)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = true
                decoder.setTargetColorSpace(space)
                if (crop != null) decoder.crop = crop
            })
        if (crop != null) {
            require(
                crop.left >= 0 &&
                    crop.top >= 0 &&
                    crop.right <= info.width &&
                    crop.bottom <= info.height &&
                    !crop.isEmpty
            )
            val corners =
                listOf(
                        crop.left to crop.top,
                        crop.right to crop.top,
                        crop.left to crop.bottom,
                        crop.right to crop.bottom,
                    )
                    .map { (x, y) -> rawPoint(x, y, info) }
            val raw =
                Rect(
                    corners.minOf { it.first },
                    corners.minOf { it.second },
                    corners.maxOf { it.first },
                    corners.maxOf { it.second },
                )
            // JPEG chroma interpolation needs neighboring pixels at the crop boundary.
            val region = if (info.mime == "image/jpeg") Rect(raw).apply {
                inset(-1, -1)
                intersect(0, 0, info.rawWidth, info.rawHeight)
            } else raw
            budget(region.width(), region.height(), if (info.config == Bitmap.Config.RGBA_F16) 4 else 2)
            val decoder =
                try {
                    BitmapRegionDecoder.newInstance(file.path)
                } catch (_: java.io.IOException) {
                    return decodeImage()
                }
            val bitmap =
                try {
                    checkNotNull(
                        decoder.decodeRegion(
                            region,
                            BitmapFactory.Options().apply {
                                inMutable = true
                                inPreferredConfig = info.config
                                inPreferredColorSpace = space
                            },
                        )
                    )
                } finally {
                    decoder.recycle()
                }
            val selected = if (region == raw) bitmap else try {
                Bitmap.createBitmap(bitmap, raw.left - region.left, raw.top - region.top, raw.width(), raw.height())
            } finally {
                bitmap.recycle()
            }
            return finish(orient(selected, info.orientation))
        }
        budget(info.width, info.height, if (info.config == Bitmap.Config.RGBA_F16) 4 else 2)
        return decodeImage()
    }

    private fun rawPoint(x: Int, y: Int, info: PhotoInfo): Pair<Int, Int> =
        when (info.orientation) {
            2 -> info.rawWidth - x to y
            3 -> info.rawWidth - x to info.rawHeight - y
            4 -> x to info.rawHeight - y
            5 -> y to x
            6 -> y to info.rawHeight - x
            7 -> info.rawWidth - y to info.rawHeight - x
            8 -> info.rawWidth - y to x
            else -> x to y
        }

    private fun orient(bitmap: Bitmap, orientation: Int): Bitmap {
        if (orientation == 1) return bitmap
        val matrix =
            Matrix().apply {
                when (orientation) {
                    2 -> setScale(-1f, 1f)
                    3 -> setRotate(180f)
                    4 -> setScale(1f, -1f)
                    5 -> {
                        setRotate(90f)
                        postScale(-1f, 1f)
                    }
                    6 -> setRotate(90f)
                    7 -> {
                        setRotate(90f)
                        postScale(1f, -1f)
                    }
                    8 -> setRotate(270f)
                }
            }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, false).also {
            if (it !== bitmap) bitmap.recycle()
        }
    }

    fun pixel(file: File, info: PhotoInfo, x: Int, y: Int): Int {
        val left = x.coerceIn(0, info.width - 1)
        val top = y.coerceIn(0, info.height - 1)
        val bitmap = decode(file, info, Rect(left, top, left + 1, top + 1))
        return try {
            bitmap.getPixel(0, 0)
        } finally {
            bitmap.recycle()
        }
    }

    suspend fun palette(file: File, info: PhotoInfo): List<Int> {
        val samples = ArrayList<Int>()
        val decoder =
            if (info.width.toLong() * info.height * 4 < Runtime.getRuntime().maxMemory() / 5) null
            else
                try {
                    BitmapRegionDecoder.newInstance(file.path)
                } catch (_: java.io.IOException) {
                    null
                }
        if (decoder == null) {
            val bitmap = decode(file, info)
            try {
                return palette(bitmap)
            } finally {
                bitmap.recycle()
            }
        }
        try {
            val stepY = maxOf(1, info.rawHeight / 128)
            val stepX = maxOf(1, info.rawWidth / 128)
            for (y in stepY / 2 until info.rawHeight step stepY) {
                currentCoroutineContext().ensureActive()
                val row =
                    checkNotNull(
                        decoder.decodeRegion(
                            Rect(0, y, info.rawWidth, y + 1),
                            BitmapFactory.Options().apply {
                                inPreferredConfig = Bitmap.Config.ARGB_8888
                                inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
                            },
                        )
                    )
                try {
                    for (x in stepX / 2 until info.rawWidth step stepX) samples +=
                        row.getPixel(x, 0)
                } finally {
                    row.recycle()
                }
            }
        } finally {
            decoder.recycle()
        }
        return representativeColors(samples)
    }

    suspend fun palette(bitmap: Bitmap): List<Int> {
        val samples = ArrayList<Int>()
        val stepX = maxOf(1, bitmap.width / 128)
        val stepY = maxOf(1, bitmap.height / 128)
        for (y in stepY / 2 until bitmap.height step stepY) {
            currentCoroutineContext().ensureActive()
            for (x in stepX / 2 until bitmap.width step stepX) samples += bitmap.getPixel(x, y)
        }
        return representativeColors(samples)
    }

    suspend fun removeBackground(
        context: Context,
        bitmap: Bitmap,
        background: Int,
        progress: (Int) -> Unit,
    ) {
        require(background ushr 24 == 0 || background ushr 24 == 255)
        val coroutine = currentCoroutineContext()
        val pixels = IntArray(ImageMath.SIZE * ImageMath.SIZE)
        ImageMath.resize(
            bitmap.width,
            bitmap.height,
            ImageMath.SIZE,
            ImageMath.SIZE,
            { y, row ->
                coroutine.ensureActive()
                bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            },
            { y, row -> row.copyInto(pixels, y * ImageMath.SIZE) },
        )
        val mask =
            BackgroundEngine(context).infer(ImageMath.normalize(pixels), progress)
        progress(R.string.finishing_image)
        applyMask(bitmap, mask, background)
    }

    suspend fun applyMask(bitmap: Bitmap, mask: FloatArray, background: Int) {
        require(background ushr 24 == 0 || background ushr 24 == 255)
        val coroutine = currentCoroutineContext()
        val opaque = !bitmap.hasAlpha()
        bitmap.setHasAlpha(bitmap.hasAlpha() || background ushr 24 == 0)
        val fill = Color.valueOf(background).convert(bitmap.colorSpace!!).components
        val row = FloatArray(bitmap.width * 4)
        ImageMath.alpha(
            mask,
            bitmap.width,
            bitmap.height,
            { coroutine.ensureActive() },
        ) { y, alpha ->
            BitmapPixels.readRow(bitmap, y, row)
            for (x in alpha.indices) {
                val coverage = alpha[x] / 255f
                val a = (if (opaque) 1f else row[x * 4 + 3]) * coverage
                for (c in 0..2)
                    row[x * 4 + c] = row[x * 4 + c] * coverage + fill[c] * fill[3] * (1f - a)
                row[x * 4 + 3] = a + fill[3] * (1f - a)
            }
            BitmapPixels.writeRow(bitmap, y, row)
        }
    }

    fun cover(bitmap: Bitmap, strokes: List<Stroke>) {
        if (strokes.any { it.clearsPixels }) bitmap.setHasAlpha(true)
        cover(Canvas(bitmap), strokes)
    }

    fun cover(canvas: Canvas, strokes: List<Stroke>) {
        for (stroke in strokes) {
            if (!stroke.isDrawable) continue
            val paint =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = stroke.color
                    blendMode = if (stroke.clearsPixels) BlendMode.CLEAR else BlendMode.SRC_OVER
                    strokeWidth = stroke.width
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                    style = if (stroke.fill) Paint.Style.FILL else Paint.Style.STROKE
                }
            if (stroke.points.size == 1)
                canvas.drawCircle(
                    stroke.points[0].first,
                    stroke.points[0].second,
                    stroke.width / 2,
                    paint.apply { style = Paint.Style.FILL },
                )
            else canvas.drawPath(stroke.path(), paint)
        }
    }

    fun expand(bitmap: Bitmap, bounds: Rect, color: Int): Bitmap {
        require(
            bounds.left <= 0 && bounds.top <= 0 &&
                bounds.right >= bitmap.width && bounds.bottom >= bitmap.height
        )
        require(
            bounds.right.toLong() - bounds.left in 1..Int.MAX_VALUE.toLong() &&
                bounds.bottom.toLong() - bounds.top in 1..Int.MAX_VALUE.toLong()
        )
        budget(bounds.width(), bounds.height(), if (bitmap.config == Bitmap.Config.RGBA_F16) 2 else 1)
        val output = Bitmap.createBitmap(
            bounds.width(), bounds.height(), bitmap.config!!, true, bitmap.colorSpace!!,
        )
        try {
            output.density = bitmap.density
            output.eraseColor(color)
            Canvas(output).drawBitmap(
                bitmap,
                null,
                Rect(-bounds.left, -bounds.top, bitmap.width - bounds.left, bitmap.height - bounds.top),
                Paint().apply { blendMode = BlendMode.SRC },
            )
            return output
        } catch (error: Throwable) {
            output.recycle()
            throw error
        }
    }

    suspend fun resize(bitmap: Bitmap, width: Int, height: Int, method: Int): Bitmap {
        require(method in 0..2)
        budget(width, height, if (bitmap.config == Bitmap.Config.RGBA_F16) 6 else 3)
        if (method == 0) return Bitmap.createScaledBitmap(bitmap, width, height, false)
        val space = bitmap.colorSpace!!
        val output = Bitmap.createBitmap(width, height, bitmap.config!!, bitmap.hasAlpha(), space)
        output.density = bitmap.density
        val radius = if (method == 2) 2 else 1
        val sx = bitmap.width.toDouble() / width
        val sy = bitmap.height.toDouble() / height
        val fx = max(1.0, sx)
        val fy = max(1.0, sy)
        val cache = linkedMapOf<Int, FloatArray>()
        val source = FloatArray(bitmap.width * 4)
        val row = FloatArray(width * 4)
        val channels = DoubleArray(4)
        try {
            for (y in 0 until height) {
                currentCoroutineContext().ensureActive()
                val center = (y + 0.5) * sy - 0.5
                val start = maxOf(0, ceil(center - radius * fy).toInt())
                val end = minOf(bitmap.height - 1, floor(center + radius * fy).toInt())
                cache.keys.filter { it < start }.forEach { cache.remove(it) }
                for (iy in start..end) if (iy !in cache) {
                    currentCoroutineContext().ensureActive()
                    BitmapPixels.readRow(bitmap, iy, source)
                    val horizontal = FloatArray(width * 4)
                    for (x in 0 until width) {
                        val cx = (x + 0.5) * sx - 0.5
                        val lo = maxOf(0, ceil(cx - radius * fx).toInt())
                        val hi = minOf(bitmap.width - 1, floor(cx + radius * fx).toInt())
                        var weight = 0.0
                        for (ix in lo..hi) {
                            val w = kernel((ix - cx) / fx, method)
                            weight += w
                            for (c in 0..3) horizontal[x * 4 + c] += (source[ix * 4 + c] * w).toFloat()
                        }
                        for (c in 0..3) horizontal[x * 4 + c] =
                            (horizontal[x * 4 + c] / weight).toFloat()
                    }
                    cache[iy] = horizontal
                }
                for (x in 0 until width) {
                    channels.fill(0.0)
                    var weight = 0.0
                    for (iy in start..end) {
                        val w = kernel((iy - center) / fy, method)
                        weight += w
                        val horizontal = cache.getValue(iy)
                        for (c in 0..3) channels[c] += horizontal[x * 4 + c] * w
                    }
                    val alpha = (channels[3] / weight).coerceIn(0.0, 1.0).toFloat()
                    for (c in 0..2) row[x * 4 + c] = (channels[c] / weight).toFloat()
                        .coerceIn(space.getMinValue(c) * alpha, space.getMaxValue(c) * alpha)
                    row[x * 4 + 3] = alpha
                }
                BitmapPixels.writeRow(output, y, row)
            }
            return output
        } catch (error: Throwable) {
            output.recycle()
            throw error
        }
    }

    private fun kernel(value: Double, method: Int): Double {
        val x = abs(value)
        return when {
            method == 1 -> max(0.0, 1 - x)
            x < 1 -> (1.5 * x - 2.5) * x * x + 1
            x < 2 -> ((-0.5 * x + 2.5) * x - 4) * x + 2
            else -> 0.0
        }
    }

    suspend fun export(
        directory: File,
        source: File,
        info: PhotoInfo,
        options: ExportOptions,
    ): File {
        val coroutine = currentCoroutineContext()
        val original = options.preservesSource(info)
        val format = if (options.format == ImageFormat.ORIGINAL) ImageFormat.PNG else options.format
        val target =
            MediaFiles.temporary(
                directory,
                if (original)
                    when (info.mime) {
                        "image/jpeg" -> "jpg"
                        "image/png" -> "png"
                        "image/webp" -> "webp"
                        else -> source.extension
                    }
                else format.extension,
            )
        try {
            if (
                original && info.container != null &&
                    (options.metadata || options.static || options.flatten)
            ) {
                Containers.clean(
                    source,
                    target,
                    options.static,
                    options.flatten,
                    options.metadata,
                ) {
                    coroutine.ensureActive()
                }
            } else if (original && !options.metadata) {
                source.inputStream().use { input ->
                    target.outputStream().use { MediaFiles.copy(input, it) }
                }
            } else if (original && info.mime in listOf("image/png", "image/webp")) {
                RasterMetadata.clean(source, target, info.mime, { coroutine.ensureActive() })
            } else {
                val bitmap = decode(source, info)
                try {
                    encode(bitmap, target, options, info.metadata)
                } finally {
                    bitmap.recycle()
                }
            }
            coroutine.ensureActive()
            return target
        } catch (error: Exception) {
            target.delete()
            throw error
        }
    }

    suspend fun export(directory: File, bitmap: Bitmap, info: PhotoInfo, options: ExportOptions): File {
        val format = if (options.format == ImageFormat.ORIGINAL) ImageFormat.PNG else options.format
        val target = MediaFiles.temporary(directory, format.extension)
        try {
            encode(bitmap, target, options, info.metadata)
            currentCoroutineContext().ensureActive()
            return target
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    fun convert(bitmap: Bitmap, space: ColorSpace, background: Int? = null): Bitmap {
        budget(bitmap.width, bitmap.height, if (bitmap.config == Bitmap.Config.RGBA_F16) 4 else 2)
        val output = Bitmap.createBitmap(
            bitmap.width, bitmap.height,
            if (space.isSrgb) Bitmap.Config.ARGB_8888 else bitmap.config!!,
            background == null && bitmap.hasAlpha(), space,
        )
        try {
            output.density = bitmap.density
            Canvas(output).apply {
                if (background != null) drawColor(background)
                drawBitmap(bitmap, null, Rect(0, 0, bitmap.width, bitmap.height), null)
            }
            return output
        } catch (error: Throwable) {
            output.recycle()
            throw error
        }
    }

    private fun encode(
        bitmap: Bitmap,
        target: File,
        options: ExportOptions,
        metadata: List<Pair<String, String>>,
    ) {
        val background = options.background.takeIf { options.format == ImageFormat.JPEG && bitmap.hasAlpha() }
        val space = if (options.keepColor) bitmap.colorSpace!! else ColorSpace.get(ColorSpace.Named.SRGB)
        val flat = if (background != null || bitmap.colorSpace != space) convert(bitmap, space, background) else bitmap
        try {
            val compression = when (options.format) {
                ImageFormat.JPEG -> Bitmap.CompressFormat.JPEG
                ImageFormat.WEBP_LOSSY -> Bitmap.CompressFormat.WEBP_LOSSY
                ImageFormat.WEBP_LOSSLESS -> Bitmap.CompressFormat.WEBP_LOSSLESS
                else -> Bitmap.CompressFormat.PNG
            }
            target.outputStream().buffered().use {
                check(flat.compress(compression, options.quality, it))
            }
            if (!options.metadata) PhotoMetadata.write(target, metadata, bitmap.width, bitmap.height)
        } finally {
            if (flat !== bitmap) flat.recycle()
        }
    }
}

internal fun representativeColors(samples: List<Int>): List<Int> {
    val bins =
        samples
            .filter { it ushr 24 > 0 }
            .groupBy {
                (((it ushr 19) and 31) shl 10) or
                    (((it ushr 11) and 31) shl 5) or
                    ((it ushr 3) and 31)
            }
            .values
            .sortedByDescending { it.size }
    val selected = mutableListOf<Int>()
    for (bin in bins) {
        val color = bin.groupingBy { it and 0xffffff }.eachCount().maxBy { it.value }.key
        if (
            selected.all { other ->
                val distance =
                    listOf(16, 8, 0).sumOf { shift ->
                        val delta = ((color ushr shift) and 255) - ((other ushr shift) and 255)
                        delta * delta
                    }
                distance >= 32 * 32
            }
        )
            selected += color or (255 shl 24)
        if (selected.size == 4) break
    }
    return selected
}
