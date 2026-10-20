package com.jasongzy.medialab

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal object ImageMath {
    const val SIZE = 1024
    private const val PRECISION = 22
    private const val UNIT = 1 shl PRECISION

    private data class Kernel(val start: Int, val weights: IntArray)

    private fun kernels(source: Int, target: Int, bicubic: Boolean = false): Array<Kernel> {
        require(source > 0 && target > 0)
        val scale = source.toDouble() / target
        val filterScale = max(1.0, scale)
        val support = filterScale * if (bicubic) 2 else 1
        return Array(target) { index ->
            val center = (index + 0.5) * scale
            val start = max(0, (center - support + 0.5).toInt())
            val end = min(source, (center + support + 0.5).toInt())
            val weights =
                DoubleArray(end - start) { offset ->
                    val x = abs((offset + start - center + 0.5) / filterScale)
                    when {
                        !bicubic -> max(0.0, 1 - x)
                        x < 1 -> ((1.5 * x - 2.5) * x) * x + 1
                        x < 2 -> (((x - 5) * x + 8) * x - 4) * -0.5
                        else -> 0.0
                    }
                }
            val sum = weights.sum()
            Kernel(
                start,
                IntArray(weights.size) { i ->
                    val scaled = weights[i] / sum * UNIT
                    (scaled + if (scaled < 0) -0.5 else 0.5).toInt()
                },
            )
        }
    }

    // Pillow rounds to eight bits after each separable pass, including RGB preprocessing.
    fun resize(
        width: Int,
        height: Int,
        outWidth: Int,
        outHeight: Int,
        readRow: (Int, IntArray) -> Unit,
        writeRow: (Int, IntArray) -> Unit,
    ) {
        val horizontal = kernels(width, outWidth)
        val vertical = kernels(height, outHeight)
        val intermediate = IntArray(Math.multiplyExact(outWidth, height))
        val sourceRow = IntArray(width)
        val shifts = intArrayOf(16, 8, 0)
        for (y in 0 until height) {
            readRow(y, sourceRow)
            for (x in 0 until outWidth) {
                val kernel = horizontal[x]
                var pixel = 0
                for (shift in shifts) {
                    var value = UNIT / 2
                    for (i in kernel.weights.indices) {
                        value +=
                            ((sourceRow[kernel.start + i] ushr shift) and 255) * kernel.weights[i]
                    }
                    pixel = pixel or ((value shr PRECISION).coerceIn(0, 255) shl shift)
                }
                intermediate[y * outWidth + x] = pixel
            }
        }
        val row = IntArray(outWidth)
        for (y in 0 until outHeight) {
            val kernel = vertical[y]
            for (x in 0 until outWidth) {
                var pixel = 0
                for (shift in shifts) {
                    var value = UNIT / 2
                    for (i in kernel.weights.indices) {
                        value +=
                            ((intermediate[(kernel.start + i) * outWidth + x] ushr shift) and 255) *
                                kernel.weights[i]
                    }
                    pixel = pixel or ((value shr PRECISION).coerceIn(0, 255) shl shift)
                }
                row[x] = pixel
            }
            writeRow(y, row)
        }
    }

    fun normalize(rgb: IntArray, model: BackgroundModel): FloatArray {
        val output = FloatArray(rgb.size * 3)
        for (i in rgb.indices) {
            val r = ((rgb[i] ushr 16) and 255) / 255f
            val g = ((rgb[i] ushr 8) and 255) / 255f
            val b = (rgb[i] and 255) / 255f
            when (model) {
                BackgroundModel.RMBG14 -> {
                    output[i * 3] = r - 0.5f
                    output[i * 3 + 1] = g - 0.5f
                    output[i * 3 + 2] = b - 0.5f
                }
                BackgroundModel.RMBG20 -> {
                    output[i] = (r - 0.485f) / 0.229f
                    output[rgb.size + i] = (g - 0.456f) / 0.224f
                    output[rgb.size * 2 + i] = (b - 0.406f) / 0.225f
                }
            }
        }
        return output
    }

    fun alpha(
        mask: FloatArray,
        width: Int,
        height: Int,
        checkCancelled: () -> Unit = {},
        model: BackgroundModel = BackgroundModel.RMBG14,
        normalizationWidth: Int = width,
        normalizationHeight: Int = height,
        writeRow: (Int, IntArray) -> Unit,
    ) {
        require(mask.size == SIZE * SIZE && mask.all { it.isFinite() }) { "Invalid model output" }
        require(width > 0 && height > 0 && normalizationWidth > 0 && normalizationHeight > 0)
        if (model == BackgroundModel.RMBG20) {
            // RMBG 2.0 uses ToPILImage before resizing, so quantization precedes interpolation.
            resizeAlpha(
                SIZE, SIZE, width, height, checkCancelled, bicubic = true,
                readRow = { y, row ->
                    for (x in row.indices) row[x] = (mask[y * SIZE + x] * 255).toInt().coerceIn(0, 255)
                },
                writeRow = writeRow,
            )
            return
        }
        val xScale = SIZE.toFloat() / width
        val yScale = SIZE.toFloat() / height
        fun sample(x: Int, y: Int, xScale: Float, yScale: Float): Float {
            val sx = max(0f, (x + 0.5f) * xScale - 0.5f)
            val sy = max(0f, (y + 0.5f) * yScale - 0.5f)
            val x0 = sx.toInt().coerceAtMost(SIZE - 1)
            val y0 = sy.toInt().coerceAtMost(SIZE - 1)
            val x1 = min(x0 + 1, SIZE - 1)
            val y1 = min(y0 + 1, SIZE - 1)
            val fx = sx - x0
            val fy = sy - y0
            return (1 - fy) * ((1 - fx) * mask[y0 * SIZE + x0] + fx * mask[y0 * SIZE + x1]) +
                fy * ((1 - fx) * mask[y1 * SIZE + x0] + fx * mask[y1 * SIZE + x1])
        }
        var low = Float.POSITIVE_INFINITY
        var high = Float.NEGATIVE_INFINITY
        val normalizationXScale = SIZE.toFloat() / normalizationWidth
        val normalizationYScale = SIZE.toFloat() / normalizationHeight
        for (y in 0 until normalizationHeight) {
            checkCancelled()
            for (x in 0 until normalizationWidth) {
                val value = sample(x, y, normalizationXScale, normalizationYScale)
                low = min(low, value)
                high = max(high, value)
            }
        }
        val range = high - low
        val row = IntArray(width)
        for (y in 0 until height) {
            checkCancelled()
            for (x in row.indices) {
                row[x] =
                    if (range > 0) ((sample(x, y, xScale, yScale) - low) / range * 255).toInt().coerceIn(0, 255)
                    else 0
            }
            writeRow(y, row)
        }
    }

    fun resizeAlpha(
        width: Int,
        height: Int,
        outWidth: Int,
        outHeight: Int,
        checkCancelled: () -> Unit = {},
        bicubic: Boolean = false,
        readRow: (Int, IntArray) -> Unit,
        writeRow: (Int, IntArray) -> Unit,
    ) {
        val horizontal = kernels(width, outWidth, bicubic)
        val vertical = kernels(height, outHeight, bicubic)
        val sourceRow = IntArray(width)
        // Four rows cover the bicubic neighborhood when enlarging.
        val rows = Array(minOf(4, height)) { ByteArray(outWidth) }
        val rowNumbers = IntArray(rows.size) { -1 }
        fun resizedRow(y: Int): ByteArray {
            val slot = y % rows.size
            val row = rows[slot]
            if (rowNumbers[slot] == y) return row
            checkCancelled()
            readRow(y, sourceRow)
            for (x in row.indices) {
                val kernel = horizontal[x]
                var value = UNIT / 2
                for (i in kernel.weights.indices) {
                    value += sourceRow[kernel.start + i] * kernel.weights[i]
                }
                row[x] = (value shr PRECISION).coerceIn(0, 255).toByte()
            }
            rowNumbers[slot] = y
            return row
        }
        val row = IntArray(outWidth)
        for (y in 0 until outHeight) {
            checkCancelled()
            val kernel = vertical[y]
            row.fill(UNIT / 2)
            for (i in kernel.weights.indices) {
                val source = resizedRow(kernel.start + i)
                for (x in row.indices) {
                    row[x] += (source[x].toInt() and 255) * kernel.weights[i]
                }
            }
            for (x in row.indices) row[x] = (row[x] shr PRECISION).coerceIn(0, 255)
            writeRow(y, row)
        }
    }
}
