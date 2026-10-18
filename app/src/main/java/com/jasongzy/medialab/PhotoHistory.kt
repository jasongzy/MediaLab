package com.jasongzy.medialab

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.Rect
import java.io.File
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal object BitmapSnapshots {
    init { System.loadLibrary("medialab") }

    external fun write(bitmap: Bitmap, path: String)
    external fun read(bitmap: Bitmap, path: String)
}

internal class PhotoFrame(val source: File?, val info: PhotoInfo, internal var pixels: Bitmap? = null) {
    internal var snapshot: File? = null
    internal var config = Bitmap.Config.ARGB_8888
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
) {
    var frames = listOf(first)
        private set
    var position = 0
        private set
    val current: PhotoFrame
        get() = frames[position]
    val residentBytes: Long
        get() = frames.sumOf { it.pixels?.allocationByteCount?.toLong() ?: 0 }

    suspend fun reserve(bytes: Long, keep: PhotoFrame? = current) {
        require(bytes >= 0)
        for (frame in frames) {
            if (residentBytes + bytes <= memoryBudget) break
            if (frame !== keep) spill(frame)
        }
        require(residentBytes + bytes < Runtime.getRuntime().maxMemory() * 0.7) {
            "Image operation exceeds the memory budget"
        }
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
                        BitmapSnapshots.read(it, file.path)
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
            reserve(width.toLong() * height * 4)
            return Photos.decode(frame.source, frame.info, crop)
        }
        val source = bitmap()
        reserve(source.allocationByteCount.toLong() * width * height / source.width / source.height)
        val result =
            if (crop == null) source
            else Bitmap.createBitmap(source, crop.left, crop.top, width, height)
        return if (result === source) checkNotNull(source.copy(source.config!!, true)) else result
    }

    suspend fun append(frame: PhotoFrame) {
        reserve(frame.pixels?.allocationByteCount?.toLong() ?: 0, keep = null)
        currentCoroutineContext().ensureActive()
        val discarded = frames.drop(position + 1)
        frames = frames.take(position + 1) + frame
        position = frames.lastIndex
        discarded.forEach { it.release() }
    }

    fun move(position: Int) {
        require(position in frames.indices)
        this.position = position
    }

    fun close() {
        frames.forEach { it.release() }
        frames = emptyList()
    }

    private suspend fun spill(frame: PhotoFrame) {
        val bitmap = frame.pixels ?: return
        if (frame.source == null && frame.snapshot == null) {
            directory.mkdirs()
            val file = File(directory, "${UUID.randomUUID()}.pixels")
            try {
                BitmapSnapshots.write(bitmap, file.path)
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
        bitmap.recycle()
        frame.pixels = null
    }
}
