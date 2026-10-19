package com.jasongzy.medialab

import org.junit.Assert.*
import org.junit.Test
import java.util.zip.CRC32

class ImageMathTest {
    @Test
    fun normalizationMatchesEachModelsLayoutAndChannels() {
        val pixels = intArrayOf(0xffff0000.toInt(), 0x0000ff7f)
        assertArrayEquals(
            floatArrayOf(0.5f, -0.5f, -0.5f, -0.5f, 0.5f, 127 / 255f - 0.5f),
            ImageMath.normalize(pixels, BackgroundModel.RMBG14),
            0f,
        )
        assertArrayEquals(
            floatArrayOf(
                0.515f / 0.229f, -0.485f / 0.229f,
                -0.456f / 0.224f, 0.544f / 0.224f,
                -0.406f / 0.225f, (127 / 255f - 0.406f) / 0.225f,
            ),
            ImageMath.normalize(pixels, BackgroundModel.RMBG20),
            0.000001f,
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
        for (opacity in listOf(0f, 0.5f, 1f)) {
            ImageMath.alpha(FloatArray(size * size) { opacity }, 3, 2, model = BackgroundModel.RMBG20) { _, row ->
                assertArrayEquals(IntArray(3) { (opacity * 255).toInt() }, row)
            }
        }
        for (i in mask.indices) mask[i] = (i / size).toFloat()
        ImageMath.alpha(mask, 3, 1, normalizationWidth = 12, normalizationHeight = 4) { _, row ->
            assertArrayEquals(intArrayOf(127, 127, 127), row)
        }
        mask[0] = Float.NaN
        assertThrows(IllegalArgumentException::class.java) {
            ImageMath.alpha(mask, 3, 2) { _, _ -> fail() }
        }
    }

    @Test
    fun rmbg20AlphaMatchesPillowGrayscaleResize() {
        val size = ImageMath.SIZE
        val mask = FloatArray(size * size) { i ->
            val x = i % size
            val y = i / size
            when {
                x < 64 -> 0f
                x >= 960 -> 1f
                else -> ((x * 13 + y * 7) % 31) / 30f
            }
        }
        // Pillow 12.0: Image.fromarray((mask * 255).astype(np.uint8)).resize((width, height)).
        val cases = listOf(
            Triple(7, 5, 2030173171L),
            Triple(1537, 1199, 1849947600L),
            Triple(1024, 1024, 181760124L),
            Triple(1, 33, 1437618195L),
        )
        for ((width, height, expected) in cases) {
            val checksum = CRC32()
            ImageMath.alpha(mask, width, height, model = BackgroundModel.RMBG20) { _, row ->
                row.forEach { checksum.update(it) }
            }
            assertEquals("$width × $height", expected, checksum.value)
        }
    }
}
