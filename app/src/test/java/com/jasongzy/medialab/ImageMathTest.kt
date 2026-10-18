package com.jasongzy.medialab

import org.junit.Assert.*
import org.junit.Test

class ImageMathTest {
    @Test
    fun normalizationUsesInterleavedRgb() {
        assertArrayEquals(
            floatArrayOf(0.5f, -0.5f, -0.5f, -0.5f, 0.5f, 127 / 255f - 0.5f),
            ImageMath.normalize(intArrayOf(0xffff0000.toInt(), 0x0000ff7f)),
            0f,
        )
    }

    @Test
    fun alphaNormalizesAfterResizeAndHandlesConstantMasks() {
        val size = ImageMath.SIZE
        val mask = FloatArray(size * size) { (it % size).toFloat() / size }
        val rows = mutableListOf<IntArray>()
        ImageMath.alpha(mask, 3, 2) { _, row -> rows += row.clone() }
        rows.forEach { assertArrayEquals(intArrayOf(0, 127, 255), it) }
        ImageMath.alpha(FloatArray(size * size) { 0.5f }, 3, 2) { _, row ->
            assertArrayEquals(IntArray(3), row)
        }
        mask[0] = Float.NaN
        assertThrows(IllegalArgumentException::class.java) {
            ImageMath.alpha(mask, 3, 2) { _, _ -> fail() }
        }
    }
}
