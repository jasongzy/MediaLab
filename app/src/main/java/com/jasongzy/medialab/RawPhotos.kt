package com.jasongzy.medialab

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorSpace
import androidx.annotation.Keep
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal object RawPhotos {
    suspend fun decode(context: Context, source: File): PhotoFrame {
        val memory = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
        val budget = minOf(memory.availMem / 4, Runtime.getRuntime().maxMemory() * 2)
        val bitmap =
            RawDecoder(currentCoroutineContext()).decode(source.path, budget)
                ?: throw UnsupportedMediaException(R.string.raw_unsupported)
        try {
            currentCoroutineContext().ensureActive()
            val metadata =
                try {
                    PhotoMetadata.read(ExifInterface(source))
                } catch (_: IOException) {
                    emptyList()
                }
            return PhotoFrame(null, Photos.rasterInfo(bitmap, metadata), bitmap)
        } catch (error: Throwable) {
            bitmap.recycle()
            throw error
        }
    }
}

internal class RawDecoder(private val coroutine: CoroutineContext) {
    companion object {
        init {
            System.loadLibrary("medialab_raw")
        }
    }

    external fun decode(path: String, budget: Long): Bitmap?

    @Keep fun checkActive() = coroutine.ensureActive()

    @Keep
    fun createBitmap(width: Int, height: Int): Bitmap {
        checkActive()
        if (width.toLong() * height * 8 >= Runtime.getRuntime().maxMemory() * 0.7)
            throw OutOfMemoryError("RAW bitmap exceeds the memory budget")
        return Bitmap.createBitmap(
            width,
            height,
            Bitmap.Config.RGBA_F16,
            false,
            ColorSpace.get(ColorSpace.Named.SRGB),
        )
    }
}
