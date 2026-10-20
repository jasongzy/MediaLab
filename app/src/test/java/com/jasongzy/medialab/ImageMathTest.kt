package com.jasongzy.medialab

import org.junit.Assert.*
import org.junit.Test
import java.util.zip.CRC32

class ImageMathTest {
    @Test
    fun storedMaskPreservesAlphaAndResizesAcrossDimensions() {
        val mask = CutoutMask.Stored(2, 2, byteArrayOf(0, 64, 128.toByte(), 255.toByte()), BackgroundModel.RMBG20)
        val same = mutableListOf<Int>()
        mask.alpha(2, 2) { _, row -> same.addAll(row.toList()) }
        assertEquals(listOf(0, 64, 128, 255), same)
        val scaled = mutableListOf<Int>()
        mask.alpha(3, 3) { _, row -> scaled.addAll(row.toList()) }
        assertEquals(listOf(0, 32, 64, 64, 112, 160, 128, 192, 255), scaled)
        mask.alpha(1, 1) { _, row -> assertArrayEquals(intArrayOf(112), row) }
        val single = CutoutMask.Stored(1, 1, byteArrayOf(93), BackgroundModel.RMBG14)
        single.alpha(3, 5) { _, row -> assertArrayEquals(intArrayOf(93, 93, 93), row) }
        for ((width, height) in listOf(4 to 1, 1 to 4)) {
            val edges = CutoutMask.Stored(width, height, byteArrayOf(-1, 0, 0, -1), BackgroundModel.RMBG20)
            edges.alpha(1, 1) { _, row -> assertArrayEquals(intArrayOf(106), row) }
        }
    }

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
