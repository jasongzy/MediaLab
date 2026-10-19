package com.jasongzy.medialab

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal object ImageMath {
    const val SIZE = 1024
    private const val PRECISION = 22
    private const val UNIT = 1 shl PRECISION

    private data class Kernel(val start: Int, val weights: IntArray)

    private fun kernels(source: Int, target: Int): Array<Kernel> {
        require(source > 0 && target > 0)
        val scale = source.toDouble() / target
        val filterScale = max(1.0, scale)
        return Array(target) { index ->
            val center = (index + 0.5) * scale
            val start = max(0, (center - filterScale + 0.5).toInt())
            val end = min(source, (center + filterScale + 0.5).toInt())
            val weights =
                DoubleArray(end - start) { offset ->
                    val x = abs((offset + start - center + 0.5) / filterScale)
                    max(0.0, 1 - x)
                }
            val sum = weights.sum()
            Kernel(
                start,
                IntArray(weights.size) { i ->
                    val scaled = weights[i] / sum * UNIT
                    (scaled + 0.5).toInt()
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

    fun normalize(rgb: IntArray): FloatArray {
        val output = FloatArray(rgb.size * 3)
        for (i in rgb.indices) {
            output[i * 3] = ((rgb[i] ushr 16) and 255) / 255f - 0.5f
            output[i * 3 + 1] = ((rgb[i] ushr 8) and 255) / 255f - 0.5f
            output[i * 3 + 2] = (rgb[i] and 255) / 255f - 0.5f
        }
        return output
    }

    fun alpha(
        mask: FloatArray,
        width: Int,
        height: Int,
        checkCancelled: () -> Unit = {},
        writeRow: (Int, IntArray) -> Unit,
    ) {
        require(mask.size == SIZE * SIZE && mask.all { it.isFinite() }) { "Invalid model output" }
        val xScale = SIZE.toFloat() / width
        val yScale = SIZE.toFloat() / height
        fun sample(x: Int, y: Int): Float {
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
        for (y in 0 until height) {
            checkCancelled()
            for (x in 0 until width) {
                val value = sample(x, y)
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
                    if (range > 0) ((sample(x, y) - low) / range * 255).toInt().coerceIn(0, 255)
                    else 0
            }
            writeRow(y, row)
        }
    }
}
