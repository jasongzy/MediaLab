package com.jasongzy.medialab

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.Gainmap
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal fun bitmapBytes(bitmap: Bitmap): Long = bitmap.allocationByteCount.toLong() +
    if (Build.VERSION.SDK_INT >= 34) bitmap.gainmap?.gainmapContents?.allocationByteCount?.toLong() ?: 0 else 0

@RequiresApi(34)
internal class GainmapParameters(source: Gainmap) {
    private val min = source.ratioMin
    private val max = source.ratioMax
    private val gamma = source.gamma
    private val sdr = source.epsilonSdr
    private val hdr = source.epsilonHdr
    private val minRatio = source.minDisplayRatioForHdrTransition
    private val maxRatio = source.displayRatioForFullHdr
    private val primaries = if (Build.VERSION.SDK_INT >= 36) source.alternativeImagePrimaries else null
    private val hdrBase = Build.VERSION.SDK_INT >= 36 && source.gainmapDirection == Gainmap.GAINMAP_DIRECTION_HDR_TO_SDR

    fun create(contents: Bitmap) = Gainmap(contents).apply {
        setRatioMin(min[0], min[1], min[2])
        setRatioMax(max[0], max[1], max[2])
        setGamma(gamma[0], gamma[1], gamma[2])
        setEpsilonSdr(sdr[0], sdr[1], sdr[2])
        setEpsilonHdr(hdr[0], hdr[1], hdr[2])
        setMinDisplayRatioForHdrTransition(minRatio)
        setDisplayRatioForFullHdr(maxRatio)
        if (Build.VERSION.SDK_INT >= 36) {
            alternativeImagePrimaries = primaries
            gainmapDirection = if (hdrBase) Gainmap.GAINMAP_DIRECTION_HDR_TO_SDR else Gainmap.GAINMAP_DIRECTION_SDR_TO_HDR
        }
    }
}

internal class PixelSnapshot private constructor(val file: File, bitmap: Bitmap) {
    private val width = bitmap.width
    private val height = bitmap.height
    private val config = bitmap.config!!
    private val space = bitmap.colorSpace ?: ColorSpace.get(ColorSpace.Named.SRGB)
    private val alpha = bitmap.hasAlpha()
    private val premultiplied = bitmap.isPremultiplied
    private val density = bitmap.density
    private val rowBytes = bitmap.rowBytes
    private var gainmap: PixelSnapshot? = null
    private var parameters: GainmapParameters? = null

    val files: List<File> get() = listOf(file) + gainmap?.files.orEmpty()

    fun read(): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, config, alpha, space)
        try {
            check(bitmap.rowBytes == rowBytes) { "Unexpected snapshot row size" }
            bitmap.density = density
            bitmap.setPremultiplied(premultiplied)
            BitmapPixels.readSnapshot(bitmap, file.path)
            if (Build.VERSION.SDK_INT >= 34) gainmap?.let {
                bitmap.gainmap = checkNotNull(parameters).create(it.read())
            }
            return bitmap
        } catch (error: Throwable) {
            bitmap.recycle()
            throw error
        }
    }

    companion object {
        suspend fun write(directory: File, bitmap: Bitmap): PixelSnapshot {
            val snapshot = PixelSnapshot(MediaFiles.temporary(directory, "pixels"), bitmap)
            try {
                BitmapPixels.writeSnapshot(bitmap, snapshot.file.path)
                if (Build.VERSION.SDK_INT >= 34) bitmap.gainmap?.let {
                    snapshot.parameters = GainmapParameters(it)
                    snapshot.gainmap = write(directory, it.gainmapContents)
                }
                currentCoroutineContext().ensureActive()
                return snapshot
            } catch (error: Throwable) {
                snapshot.files.forEach { it.delete() }
                throw error
            }
        }
    }
}
