package com.jasongzy.medialab

import org.junit.Assert.*
import org.junit.Test

class ImageMathTest {
    @Test
    fun maskPreservesSourceAlphaAndBlendsWithTheChosenBackground() {
        val red = 0xffff0000.toInt()
        assertEquals(0x7fff0000, ImageMath.maskedPixel(red, 127, 0))
        assertEquals(0x40ff0000, ImageMath.maskedPixel(0x80ff0000.toInt(), 128, 0))
        for (background in listOf(-1, 0xff000000.toInt(), 0xff2468ac.toInt())) {
            assertEquals(background, ImageMath.maskedPixel(red, 0, background))
            assertEquals(background, ImageMath.maskedPixel(0x00ff0000, 255, background))
            assertEquals(red, ImageMath.maskedPixel(red, 255, background))
        }
        assertEquals(0xffff8080.toInt(), ImageMath.maskedPixel(red, 127, -1))
        assertEquals(0xff7f0000.toInt(), ImageMath.maskedPixel(red, 127, 0xff000000.toInt()))
        assertEquals(0xff923456.toInt(), ImageMath.maskedPixel(red, 128, 0xff2468ac.toInt()))
        assertEquals(0xffffbfbf.toInt(), ImageMath.maskedPixel(0x80ff0000.toInt(), 128, -1))
    }

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
