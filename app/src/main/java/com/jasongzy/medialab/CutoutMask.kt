package com.jasongzy.medialab

internal sealed class CutoutMask(val model: BackgroundModel) {
    class Inferred(val values: FloatArray, model: BackgroundModel) : CutoutMask(model)

    class Stored(val width: Int, val height: Int, val pixels: ByteArray, model: BackgroundModel) : CutoutMask(model) {
        init {
            require(width > 0 && height > 0 && pixels.size.toLong() == width.toLong() * height)
        }
    }

    val bytes: Long
        get() = when (this) {
            is Inferred -> values.size.toLong() * 4
            is Stored -> pixels.size.toLong()
        }

    fun alpha(
        width: Int,
        height: Int,
        checkCancelled: () -> Unit = {},
        normalizationWidth: Int = width,
        normalizationHeight: Int = height,
        writeRow: (Int, IntArray) -> Unit,
    ) {
        require(width > 0 && height > 0)
        when (this) {
            is Inferred -> ImageMath.alpha(
                values, width, height, checkCancelled, model, normalizationWidth, normalizationHeight, writeRow,
            )
            is Stored -> ImageMath.resizeAlpha(
                this.width, this.height, width, height, checkCancelled,
                readRow = { y, row ->
                    for (x in row.indices) row[x] = pixels[y * this.width + x].toInt() and 255
                },
                writeRow = writeRow,
            )
        }
    }
}
