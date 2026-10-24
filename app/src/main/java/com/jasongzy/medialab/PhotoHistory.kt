package com.jasongzy.medialab

import android.graphics.Bitmap
import android.graphics.Rect
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal object BitmapPixels {
    init { System.loadLibrary("medialab") }

    external fun writeSnapshot(bitmap: Bitmap, path: String)
    external fun readSnapshot(bitmap: Bitmap, path: String)
    external fun readRow(bitmap: Bitmap, y: Int, row: FloatArray)
    external fun writeRow(bitmap: Bitmap, y: Int, row: FloatArray)
}

internal class PhotoFrame(val image: PhotoPixels, val info: PhotoInfo) {
    constructor(source: File?, info: PhotoInfo, pixels: Bitmap? = null) :
        this(PhotoPixels(source, info.config, pixels), info)

    val source = image.source
    val pixels get() = image.pixels
    val snapshot get() = image.snapshot?.file
    val files get() = image.files
}

internal class PhotoPixels(val source: File?, val config: Bitmap.Config, internal var pixels: Bitmap?) {
    internal var snapshot: PixelSnapshot? = null

    val files: List<File>
        get() = listOfNotNull(source) + snapshot?.files.orEmpty()

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
    var drafts = listOf<PhotoDraft?>(null)
        private set
    var actions = listOf<Int?>(null)
        private set
    var position = 0
        private set
    val current: PhotoFrame
        get() = frames[position]
    val residentBytes: Long
        get() = frames.map { it.image }.distinct().sumOf { it.pixels?.let(::bitmapBytes) ?: 0 }

    suspend fun reserve(bytes: Long, keep: PhotoFrame? = current) {
        require(bytes >= 0)
        val retained = retainedBytes()
        for (frame in frames.distinctBy { it.image }) {
            if (residentBytes + retained + bytes <= memoryBudget) break
            if (frame.image !== keep?.image) detach(frame)?.recycle()
        }
        if (residentBytes + retained + bytes >= Runtime.getRuntime().maxMemory() * 0.7)
            throw OutOfMemoryError("Image operation exceeds the memory budget")
    }

    suspend fun bitmap(frame: PhotoFrame = current): Bitmap {
        frame.pixels?.let { return it }
        val bytes = frame.info.width.toLong() * frame.info.height *
            (if (frame.image.config == Bitmap.Config.RGBA_F16) 8 else 4) * if (frame.info.gainmap) 2 else 1
        reserve(bytes, frame)
        val bitmap =
            if (frame.source != null) Photos.decode(frame.source, frame.info, keepHdr = true)
            else checkNotNull(frame.image.snapshot).read()
        frame.image.pixels = bitmap
        return bitmap
    }

    suspend fun copy(crop: Rect? = null, keepHdr: Boolean = false): Bitmap {
        val frame = current
        if (crop != null) require(
            !crop.isEmpty && crop.left >= 0 && crop.top >= 0 &&
                crop.right <= frame.info.width && crop.bottom <= frame.info.height
        )
        val width = crop?.width() ?: frame.info.width
        val height = crop?.height() ?: frame.info.height
        if (frame.image.pixels == null && frame.source != null && !(keepHdr && frame.info.canKeepHdr)) {
            reserve(width.toLong() * height * if (frame.image.config == Bitmap.Config.RGBA_F16) 8 else 4)
            return Photos.decode(frame.source, frame.info, crop, keepHdr)
        }
        val source = bitmap()
        if (width == source.width && height == source.height && source.isMutable &&
            bitmapBytes(source) * 2 + retainedBytes() > memoryBudget
        ) {
            reserve(0)
            return checkNotNull(detach(frame)).also { if (!keepHdr) Photos.clearGainmap(it) }
        }
        reserve(bitmapBytes(source) * width * height / source.width / source.height)
        val result =
            if (crop == null) source
            else Photos.cropBitmap(source, crop, keepHdr)
        return (if (result === source) checkNotNull(source.copy(source.config!!, true)) else result)
            .also { if (!keepHdr) Photos.clearGainmap(it) }
    }

    suspend fun append(frame: PhotoFrame, draft: PhotoDraft? = null, action: Int? = draft?.tool?.label) {
        if (frames.none { it.image === frame.image }) reserve(frame.pixels?.let(::bitmapBytes) ?: 0, keep = null)
        currentCoroutineContext().ensureActive()
        val discarded = frames.drop(position + 1)
        frames = frames.take(position + 1) + frame
        drafts = drafts.take(position + 1) + draft
        actions = actions.take(position + 1) + action
        // Keep large masks bounded; image history remains available after a draft is evicted.
        val maskBudget = Runtime.getRuntime().maxMemory() / 8
        while (drafts.mapNotNull { it?.mask }.distinct().sumOf { it.bytes } > maskBudget) {
            val oldest = drafts.indexOfFirst { it?.mask != null }
            drafts = drafts.toMutableList().also { it[oldest] = null }
        }
        position = frames.lastIndex
        discarded.map { it.image }.distinct().filter { image -> frames.none { it.image === image } }.forEach { it.release() }
    }

    fun move(position: Int) {
        require(position in frames.indices)
        this.position = position
    }

    fun close() {
        frames.map { it.image }.distinct().forEach { it.release() }
        frames = emptyList()
        drafts = emptyList()
        actions = emptyList()
    }

    private suspend fun detach(frame: PhotoFrame): Bitmap? {
        val bitmap = frame.pixels ?: return null
        if (frame.source == null && frame.image.snapshot == null) {
            frame.image.snapshot = PixelSnapshot.write(directory, bitmap)
        }
        currentCoroutineContext().ensureActive()
        frame.image.pixels = null
        return bitmap
    }
}
