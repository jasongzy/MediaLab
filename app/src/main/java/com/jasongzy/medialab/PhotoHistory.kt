package com.jasongzy.medialab

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.Rect
import java.io.File
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal object BitmapPixels {
    init { System.loadLibrary("medialab") }

    external fun writeSnapshot(bitmap: Bitmap, path: String)
    external fun readSnapshot(bitmap: Bitmap, path: String)
    external fun readRow(bitmap: Bitmap, y: Int, row: FloatArray)
    external fun writeRow(bitmap: Bitmap, y: Int, row: FloatArray)
}

internal class PhotoFrame(val source: File?, val info: PhotoInfo, internal var pixels: Bitmap? = null) {
    internal var snapshot: File? = null
    internal var config = info.config
    internal var colorSpace: ColorSpace? = null
    internal var alpha = true
    internal var premultiplied = true
    internal var density = Bitmap.DENSITY_NONE
    internal var rowBytes = 0

    val files: List<File>
        get() = listOfNotNull(source, snapshot)

    internal fun release() {
        pixels?.recycle()
        pixels = null
        files.forEach { it.delete() }
    }
}

internal class PhotoHistory(
    private val directory: File,
    first: PhotoFrame,
    private val memoryBudget: Long = Runtime.getRuntime().maxMemory() / 3,
    private val retainedBytes: () -> Long = { 0 },
) {
    var frames = listOf(first)
        private set
    var position = 0
        private set
    val current: PhotoFrame
        get() = frames[position]
    val residentBytes: Long
        get() = frames.distinct().sumOf { it.pixels?.allocationByteCount?.toLong() ?: 0 }

    suspend fun reserve(bytes: Long, keep: PhotoFrame? = current) {
        require(bytes >= 0)
        val retained = retainedBytes()
        for (frame in frames.distinct()) {
            if (residentBytes + retained + bytes <= memoryBudget) break
            if (frame !== keep) detach(frame)?.recycle()
        }
        if (residentBytes + retained + bytes >= Runtime.getRuntime().maxMemory() * 0.7)
            throw OutOfMemoryError("Image operation exceeds the memory budget")
    }

    suspend fun bitmap(frame: PhotoFrame = current): Bitmap {
        frame.pixels?.let { return it }
        val bytes = frame.info.width.toLong() * frame.info.height *
            if (frame.config == Bitmap.Config.RGBA_F16) 8 else 4
        reserve(bytes, frame)
        val bitmap =
            if (frame.source != null) Photos.decode(frame.source, frame.info)
            else {
                val file = checkNotNull(frame.snapshot)
                Bitmap.createBitmap(
                    frame.info.width,
                    frame.info.height,
                    frame.config,
                    frame.alpha,
                    checkNotNull(frame.colorSpace),
                ).also {
                    try {
                        check(it.rowBytes == frame.rowBytes) { "Unexpected snapshot row size" }
                        it.density = frame.density
                        it.setPremultiplied(frame.premultiplied)
                        BitmapPixels.readSnapshot(it, file.path)
                    } catch (error: Throwable) {
                        it.recycle()
                        throw error
                    }
                }
            }
        frame.pixels = bitmap
        return bitmap
    }

    suspend fun copy(crop: Rect? = null): Bitmap {
        val frame = current
        if (crop != null) require(
            !crop.isEmpty && crop.left >= 0 && crop.top >= 0 &&
                crop.right <= frame.info.width && crop.bottom <= frame.info.height
        )
        val width = crop?.width() ?: frame.info.width
        val height = crop?.height() ?: frame.info.height
        if (frame.pixels == null && frame.source != null) {
            reserve(width.toLong() * height * if (frame.config == Bitmap.Config.RGBA_F16) 8 else 4)
            return Photos.decode(frame.source, frame.info, crop)
        }
        val source = bitmap()
        if (width == source.width && height == source.height && source.isMutable &&
            source.allocationByteCount.toLong() * 2 + retainedBytes() > memoryBudget
        ) {
            reserve(0)
            return checkNotNull(detach(frame))
        }
        reserve(source.allocationByteCount.toLong() * width * height / source.width / source.height)
        val result =
            if (crop == null) source
            else Bitmap.createBitmap(source, crop.left, crop.top, width, height)
        return if (result === source) checkNotNull(source.copy(source.config!!, true)) else result
    }

    suspend fun append(frame: PhotoFrame) {
        if (frame !in frames) reserve(frame.pixels?.allocationByteCount?.toLong() ?: 0, keep = null)
        currentCoroutineContext().ensureActive()
        val discarded = frames.drop(position + 1)
        frames = frames.take(position + 1) + frame
        position = frames.lastIndex
        discarded.distinct().filter { it !in frames }.forEach { it.release() }
    }

    fun move(position: Int) {
        require(position in frames.indices)
        this.position = position
    }

    fun close() {
        frames.distinct().forEach { it.release() }
        frames = emptyList()
    }

    private suspend fun detach(frame: PhotoFrame): Bitmap? {
        val bitmap = frame.pixels ?: return null
        if (frame.source == null && frame.snapshot == null) {
            directory.mkdirs()
            val file = File(directory, "${UUID.randomUUID()}.pixels")
            try {
                BitmapPixels.writeSnapshot(bitmap, file.path)
                currentCoroutineContext().ensureActive()
                frame.config = checkNotNull(bitmap.config)
                frame.colorSpace = checkNotNull(bitmap.colorSpace)
                frame.alpha = bitmap.hasAlpha()
                frame.premultiplied = bitmap.isPremultiplied
                frame.density = bitmap.density
                frame.rowBytes = bitmap.rowBytes
                frame.snapshot = file
            } catch (error: Throwable) {
                file.delete()
                throw error
            }
        }
        currentCoroutineContext().ensureActive()
        frame.pixels = null
        return bitmap
    }
}
