package com.jasongzy.medialab

import android.app.Application
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kyant.taglib.Picture
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal enum class ImportType {
    IMAGE,
    AUDIO,
}

internal enum class Confirmation(val title: Int, val message: Int, val confirm: Int = R.string.discard) {
    IMAGE(R.string.replace_image, R.string.replace_message),
    AUDIO(R.string.replace_audio, R.string.replace_audio_message),
    UNDO(R.string.undo, R.string.discard_unapplied_message),
    REDO(R.string.redo, R.string.discard_unapplied_message),
    APPLY(R.string.apply, R.string.discard_other_edits_message, R.string.apply),
    RESTORE(R.string.restore_original, R.string.discard_unapplied_message, R.string.restore_original),
    REINFER(R.string.reinfer, R.string.replace_mask_message, R.string.reinfer),
    DISCARD_MASK(R.string.discard_mask, R.string.discard_mask_message),
    TRANSPARENT_INPUT(R.string.transparent_input, R.string.transparent_input_message, R.string.continue_action),
    CONVERT_COLOR(R.string.convert_color, R.string.convert_color_message, R.string.continue_action),
}

internal data class PhotoState(
    val name: String = "",
    val history: List<PhotoFrame> = emptyList(),
    val position: Int = 0,
    val preview: Bitmap? = null,
    val original: Bitmap? = null,
    val info: PhotoInfo? = null,
    val colors: List<Int> = emptyList(),
    val picked: Int? = null,
    val backgroundElapsedMs: Long? = null,
    val editor: PhotoEditorState? = info?.let { PhotoEditorState(it, preview?.hasAlpha() == true) },
    val sourceUri: Uri? = null,
) {
    val frame
        get() = history.getOrNull(position)
    val pending
        get() = editor?.pending(checkNotNull(info), frame?.source != null).orEmpty()
}

internal data class Notice(val message: Int, val argument: String? = null)

internal data class LabState(
    val tab: Int = 0,
    val photo: PhotoState = PhotoState(),
    val batchPage: Boolean = false,
    val audio: AudioDocument? = null,
    val confirmation: Confirmation? = null,
    val busy: Boolean = false,
    val refreshingMedia: Boolean = false,
    val stage: Int = R.string.loading_photo,
    val error: String? = null,
    val notice: Notice? = null,
    val exportFile: File? = null,
    val exportPreview: Bitmap? = null,
    val mode: ColorMode = ColorMode.SYSTEM,
) {
    val audioDirty: Boolean
        get() = audio?.dirty == true
    val workingFiles: Set<File>
        get() =
            (photo.history.flatMap { it.files } + listOfNotNull(audio?.file, exportFile))
                .toSet()
}

internal class LabViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application
    private val mutable = MutableStateFlow(LabState())
    val state = mutable.asStateFlow()
    private val workspace = WorkspaceCache(application.cacheDir) { state.value.workingFiles }
    private var job: Job? = null
    private var pickJob: Job? = null
    private var pendingPick: Pair<Int, Int>? = null
    private val settings = context.getSharedPreferences("settings", 0)
    private var exportOptions: ExportOptions? = null
    private var exportedSource: PhotoFrame? = null
    private var playbackPosition: Pair<File, Long>? = null
    private var history: PhotoHistory? = null
    private var resizePreview: ResizePreview? = null
    private var confirmationAnswer: CompletableDeferred<Boolean>? = null

    private data class ResizePreview(
        val source: PhotoFrame,
        val width: Int,
        val height: Int,
        val method: Int,
        val bitmap: Bitmap,
        val preview: Bitmap,
    )

    init {
        viewModelScope.coroutineContext[Job]!!.invokeOnCompletion { workspace.close() }
        mutable.update {
            it.copy(mode = ColorMode.fromStored(settings.getString("colorMode", null)))
        }
        work(R.string.preparing_image) {
            workspace.clearStale()
        }
    }

    private fun work(stage: Int, action: suspend () -> Unit) =
        task(stage) { workspace.access.withLock { action() } }

    private fun task(stage: Int, action: suspend () -> Unit) {
        if (state.value.busy) return
        mutable.update {
            it.copy(busy = true, stage = stage, error = null)
        }
        job = viewModelScope.launch {
            try {
                pickJob?.cancelAndJoin()
                withContext(Dispatchers.IO) { action() }
            } catch (_: CancellationException) {} catch (error: UnsupportedMediaException) {
                mutable.update { it.copy(notice = Notice(error.messageRes)) }
            } catch (error: Exception) {
                mutable.update { it.copy(error = error.stackTraceToString()) }
            } catch (error: OutOfMemoryError) {
                mutable.update { it.copy(error = context.getString(R.string.memory_error)) }
            } finally {
                mutable.update { it.copy(busy = false) }
            }
        }
    }

    fun mode(mode: ColorMode) {
        settings.edit().putString("colorMode", mode.name).apply()
        mutable.update { it.copy(mode = mode) }
    }

    fun tab(tab: Int) {
        mutable.update { it.copy(tab = tab) }
    }

    fun showBatch(show: Boolean) {
        mutable.update { it.copy(batchPage = show, tab = 1) }
    }

    fun playbackPosition(file: File): Long =
        playbackPosition?.takeIf { it.first == file }?.second ?: 0

    fun rememberPlaybackPosition(file: File, position: Long) {
        playbackPosition = file to position
    }

    fun clearError() {
        mutable.update { it.copy(error = null) }
    }

    fun clearNotice() {
        mutable.update { it.copy(notice = null) }
    }

    fun clearCache() =
        task(R.string.clearing_cache) {
            if (NcmBatch.state.value.busy)
                throw UnsupportedMediaException(R.string.batch_cache_busy)
            NcmBatch.cacheAccess.withLock {
                discardResize()
                workspace.clearAll()
            }
            mutable.update { it.copy(notice = Notice(R.string.cache_cleared)) }
        }

    fun refreshMediaIndex() {
        if (state.value.refreshingMedia) return
        mutable.update { it.copy(refreshingMedia = true) }
        viewModelScope.launch {
            try {
                val success = withContext(Dispatchers.IO) { MediaFiles.refreshMediaIndex(context) }
                mutable.update {
                    it.copy(
                        notice = Notice(
                            if (success) R.string.media_index_refreshed
                            else R.string.media_index_failed
                        )
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (BuildConfig.DEBUG) Log.e("MediaIndex", "Media scan failed", error)
                mutable.update { it.copy(notice = Notice(R.string.media_index_failed)) }
            } finally {
                mutable.update { it.copy(refreshingMedia = false) }
            }
        }
    }

    fun cancel() {
        mutable.update { it.copy(stage = R.string.cancelling) }
        job?.cancel()
    }

    fun answerConfirmation(accepted: Boolean) {
        confirmationAnswer?.complete(accepted)
    }

    private suspend fun canReplace(type: ImportType): Boolean {
        val current = state.value
        val edited =
            if (type == ImportType.IMAGE)
                current.photo.history.size > 1 || current.photo.pending.isNotEmpty()
            else current.audioDirty || current.audio?.editor?.hasDraft == true
        return !edited || confirm(
            if (type == ImportType.IMAGE) Confirmation.IMAGE else Confirmation.AUDIO
        )
    }

    private suspend fun confirm(action: Confirmation): Boolean {
        val answer = CompletableDeferred<Boolean>()
        confirmationAnswer = answer
        mutable.update { it.copy(confirmation = action) }
        return try {
            answer.await()
        } finally {
            confirmationAnswer = null
            mutable.update { it.copy(confirmation = null) }
        }
    }

    private suspend fun canApply(tool: PhotoTool): Boolean =
        state.value.photo.pending.none { it != tool } || confirm(Confirmation.APPLY)

    fun open(uri: Uri, type: ImportType?) =
        work(
            if (type == null || type == ImportType.IMAGE) R.string.loading_photo
            else R.string.loading_audio
        ) {
            if (
                type == null &&
                    (MediaFiles.name(context, uri).endsWith(".ncm", true) ||
                        context.contentResolver.openInputStream(uri)?.use { Ncm.matches(it) } == true)
            ) {
                NcmBatch.addFiles(context, listOf(uri), shared = true)
                showBatch(true)
                return@work
            }
            val (file, name) = MediaFiles.import(context, uri, workspace.work)
            try {
                val bounds =
                    android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(file.path, bounds)
                val isImage = if (type == null) bounds.outWidth > 0 else type == ImportType.IMAGE
                val typeError =
                    when {
                        isImage -> R.string.select_image_only
                        else -> R.string.select_audio_only
                    }
                if ((bounds.outWidth > 0) != isImage) throw UnsupportedMediaException(typeError)
                if (!isImage) {
                    if (Ncm.matches(file)) throw UnsupportedMediaException(typeError)
                    AudioFiles.validate(file)
                }
                if (!canReplace(if (isImage) ImportType.IMAGE else ImportType.AUDIO)) return@work
                if (isImage) {
                    val preview = Photos.preview(file)
                    val info = Photos.info(file, preview)
                    val next =
                        PhotoHistory(workspace.work, PhotoFrame(file, info))
                    val photo = PhotoState(name, next.frames, 0, preview, preview, info, sourceUri = uri)
                    currentCoroutineContext().ensureActive()
                    val old = history
                    val oldExport = state.value.exportFile
                    discardResize()
                    history = next
                    mutable.update {
                        it.copy(
                            photo = photo,
                            tab = 0,
                            exportFile = null,
                            exportPreview = null,
                        )
                    }
                    old?.close()
                    oldExport?.delete()
                } else {
                    val document = AudioFiles.read(file, name).copy(sourceUri = uri, sourceBytes = file.length())
                    currentCoroutineContext().ensureActive()
                    val old = state.value.audio?.file
                    mutable.update {
                        it.copy(
                            audio = document,
                            batchPage = false,
                            tab = 1,
                        )
                    }
                    old?.delete()
                }
            } finally {
                if (file !in state.value.workingFiles) file.delete()
            }
        }

    private fun discardResize(retained: Bitmap? = null) {
        resizePreview?.bitmap?.takeIf { it !== retained }?.recycle()
        resizePreview = null
    }

    private fun publish(preview: Bitmap, notice: Int? = R.string.changes_applied) {
        val history = checkNotNull(history)
        val old = state.value.photo
        val oldExport = state.value.exportFile
        val photo =
            PhotoState(
                old.name,
                history.frames,
                history.position,
                preview,
                old.original,
                history.current.info,
                editor = PhotoEditorState(history.current.info, preview.hasAlpha(), old.editor),
                sourceUri = old.sourceUri,
            )
        mutable.update {
            it.copy(
                photo = photo,
                exportFile = null,
                exportPreview = null,
                notice = notice?.let { message -> Notice(message) },
            )
        }
        oldExport?.delete()
    }

    private suspend fun push(file: File) {
        val preview = Photos.preview(file)
        val frame = PhotoFrame(file, Photos.info(file, preview))
        checkNotNull(history).append(frame)
        discardResize()
        publish(preview)
    }

    private suspend fun push(bitmap: Bitmap, preview: Bitmap? = null, clearMetadata: Boolean = false) {
        val rendered = preview ?: Photos.preview(bitmap)
        val metadata = if (clearMetadata) emptyList() else checkNotNull(state.value.photo.info).metadata
        val frame = PhotoFrame(null, Photos.rasterInfo(bitmap, metadata), bitmap)
        checkNotNull(history).append(frame)
        discardResize(bitmap)
        publish(rendered)
    }

    fun undo(direction: Int) =
        work(R.string.preparing_image) {
            val history = checkNotNull(history)
            val position = (history.position + direction).coerceIn(history.frames.indices)
            if (position == history.position) return@work
            if (state.value.photo.pending.isNotEmpty() && !confirm(
                    if (direction < 0) Confirmation.UNDO else Confirmation.REDO
                )) return@work
            val frame = history.frames[position]
            discardResize()
            val preview =
                if (frame.source != null) Photos.preview(frame.source)
                else Photos.preview(history.bitmap(frame))
            currentCoroutineContext().ensureActive()
            history.move(position)
            publish(preview, notice = null)
        }

    fun restoreOriginal() =
        work(R.string.preparing_image) {
            val history = checkNotNull(history)
            val original = history.frames.first()
            if (history.current === original) return@work
            if (state.value.photo.pending.isNotEmpty() && !confirm(Confirmation.RESTORE)) return@work
            val preview = checkNotNull(state.value.photo.original)
            history.append(original)
            discardResize()
            publish(preview, R.string.original_image_restored)
        }

    private suspend fun bitmapEdit(
        crop: Rect? = null,
        clearMetadata: Boolean = false,
        transform: suspend (Bitmap) -> Unit = {},
    ) {
        discardResize()
        val bitmap = checkNotNull(history).copy(crop)
        try {
            transform(bitmap)
            currentCoroutineContext().ensureActive()
            push(bitmap, clearMetadata = clearMetadata)
        } catch (error: Throwable) {
            bitmap.recycle()
            throw error
        }
    }

    fun crop(rect: Rect, lossless: Boolean) =
        work(R.string.preparing_image) {
            if (!canApply(PhotoTool.CROP)) return@work
            if (lossless) {
                val photo = state.value.photo
                val info = checkNotNull(photo.info)
                require(
                    info.mime == "image/jpeg" &&
                        info.orientation == 1 &&
                        info.container?.gainmap == null
                ) {
                    context.getString(R.string.jpeg_crop_requirement)
                }
                val target = MediaFiles.temporary(workspace.work, "jpg")
                try {
                    JpegNative.crop(
                        checkNotNull(photo.frame?.source).path,
                        target.path,
                        rect.left,
                        rect.top,
                        rect.width(),
                        rect.height(),
                    )
                    PhotoMetadata.write(target, info.metadata, rect.width(), rect.height())
                    currentCoroutineContext().ensureActive()
                    push(target)
                } catch (error: Exception) {
                    target.delete()
                    throw error
                }
            } else bitmapEdit(rect)
        }

    fun expand(bounds: Rect, color: Int) =
        work(R.string.preparing_image) {
            if (!canApply(PhotoTool.EXPAND)) return@work
            discardResize()
            val history = checkNotNull(history)
            val source = history.bitmap()
            require(bounds.width() > 0 && bounds.height() > 0)
            history.reserve(
                bounds.width().toLong() * bounds.height() *
                    if (source.config == Bitmap.Config.RGBA_F16) 8 else 4
            )
            val bitmap = Photos.expand(source, bounds, color)
            try {
                currentCoroutineContext().ensureActive()
                push(bitmap)
            } catch (error: Throwable) {
                bitmap.recycle()
                throw error
            }
        }

    fun resize(width: Int, height: Int, method: Int) =
        work(R.string.preparing_image) {
            if (!canApply(PhotoTool.RESIZE)) return@work
            val result = resized(width, height, method)
            push(result.bitmap, result.preview)
        }

    fun previewResize(width: Int, height: Int, method: Int, ready: (Bitmap) -> Unit) =
        work(R.string.preparing_image) {
            val result = resized(width, height, method)
            withContext(Dispatchers.Main) { ready(result.preview) }
        }

    private suspend fun resized(width: Int, height: Int, method: Int): ResizePreview {
        require(width > 0 && height > 0 && method in 0..2)
        val history = checkNotNull(history)
        resizePreview
            ?.takeIf {
                it.source === history.current &&
                    it.width == width &&
                    it.height == height &&
                    it.method == method
            }
            ?.let {
                return it
            }
        discardResize()
        val source = history.bitmap()
        val bytesPerPixel = if (source.config == Bitmap.Config.RGBA_F16) 8 else 4
        history.reserve(width.toLong() * height * bytesPerPixel)
        val scaled = Photos.resize(source, width, height, method)
        val bitmap =
            if (scaled === source) checkNotNull(source.copy(source.config!!, true)) else scaled
        try {
            currentCoroutineContext().ensureActive()
            val result =
                ResizePreview(
                    history.current,
                    width,
                    height,
                    method,
                    bitmap,
                    Photos.preview(bitmap),
                )
            resizePreview = result
            return result
        } catch (error: Throwable) {
            bitmap.recycle()
            throw error
        }
    }

    fun cover(strokes: List<Stroke>) =
        work(R.string.preparing_image) {
            if (!canApply(PhotoTool.COVER)) return@work
            bitmapEdit {
                Photos.cover(it, strokes)
            }
        }

    fun removeBackground(model: BackgroundModel) =
        work(R.string.preparing_image) {
            val photo = state.value.photo
            val editor = checkNotNull(photo.editor)
            if (editor.cutout != null && !confirm(Confirmation.REINFER))
                return@work
            discardResize()
            val bitmap = checkNotNull(history).bitmap()
            if (Photos.hasFullyTransparentPixels(bitmap) && !confirm(Confirmation.TRANSPARENT_INPUT))
                return@work
            val converts = checkNotNull(photo.info).color.losses().isNotEmpty()
            if (converts && !confirm(Confirmation.CONVERT_COLOR)) return@work
            val started = SystemClock.elapsedRealtime()
            val mask = Photos.inferMask(context, bitmap, model) { stage ->
                mutable.update { it.copy(stage = stage) }
            }
            val preview = if (converts) Photos.preview(bitmap) else checkNotNull(photo.preview)
            val maskPreview = Photos.maskPreview(mask, preview.width, preview.height, model, bitmap.width, bitmap.height)
            currentCoroutineContext().ensureActive()
            val elapsed = SystemClock.elapsedRealtime() - started
            withContext(Dispatchers.Main) {
                editor.cutout = CutoutDraft(mask, model, preview, maskPreview)
                editor.dropper = false
                mutable.update { it.copy(photo = it.photo.copy(backgroundElapsedMs = elapsed)) }
            }
        }

    fun applyBackground() =
        work(R.string.finishing_image) {
            val photo = state.value.photo
            val editor = checkNotNull(photo.editor)
            val draft = editor.cutout ?: return@work
            if (!canApply(PhotoTool.BACKGROUND)) return@work
            bitmapEdit { bitmap ->
                Photos.applyMask(bitmap, draft.mask, editor.cutoutBackground, draft.model, draft.history.value)
            }
            mutable.update { it.copy(photo = it.photo.copy(backgroundElapsedMs = photo.backgroundElapsedMs)) }
        }

    fun discardBackground() =
        work(R.string.preparing_image) {
            val editor = checkNotNull(state.value.photo.editor)
            if (editor.cutout == null || !confirm(Confirmation.DISCARD_MASK)) return@work
            withContext(Dispatchers.Main) { editor.cutout = null }
            mutable.update { it.copy(photo = it.photo.copy(backgroundElapsedMs = null)) }
        }

    fun palette() =
        work(R.string.analyzing_colors) {
            val photo = state.value.photo
            val frame = checkNotNull(photo.frame)
            val colors =
                if (frame.pixels != null || frame.source == null)
                    Photos.palette(checkNotNull(history).bitmap(frame))
                else Photos.palette(frame.source, frame.info)
            mutable.update { it.copy(photo = it.photo.copy(colors = colors)) }
        }

    fun pick(x: Int, y: Int) {
        if (state.value.busy) return
        val frame = state.value.photo.frame ?: return
        pendingPick = x to y
        if (pickJob?.isActive == true) return
        pickJob = viewModelScope.launch {
            try {
                while (true) {
                    val (sampleX, sampleY) = pendingPick ?: break
                    pendingPick = null
                    val color =
                        withContext(Dispatchers.IO) {
                            workspace.access.withLock {
                                if (state.value.photo.frame !== frame || state.value.busy) null
                                else if (frame.pixels != null || frame.source == null)
                                    checkNotNull(history)
                                        .bitmap(frame)
                                        .getPixel(
                                            sampleX.coerceIn(0, frame.info.width - 1),
                                            sampleY.coerceIn(0, frame.info.height - 1),
                                        )
                                else Photos.pixel(frame.source, frame.info, sampleX, sampleY)
                            }
                        }
                    if (color != null && state.value.photo.frame === frame && !state.value.busy)
                        mutable.update { it.copy(photo = it.photo.copy(picked = color)) }
                    delay(32)
                }
            } catch (_: CancellationException) {} catch (error: Exception) {
                mutable.update { it.copy(error = error.stackTraceToString()) }
            } finally {
                pendingPick = null
            }
        }
    }

    fun clean(static: Boolean, flatten: Boolean, metadata: Boolean, keepColor: Boolean) =
        work(R.string.preparing_image) {
            val photo = state.value.photo
            val frame = checkNotNull(photo.frame)
            val convert = !keepColor && frame.info.colorSpace?.isSrgb == false
            if (frame.source == null && !convert && (!metadata || frame.info.metadata.isEmpty())) {
                mutable.update { it.copy(notice = Notice(R.string.changes_applied)) }
                return@work
            }
            if (!canApply(PhotoTool.CLEAN)) return@work
            if (frame.source == null) {
                if (convert) {
                    discardResize()
                    val history = checkNotNull(history)
                    val source = history.bitmap()
                    history.reserve(source.width.toLong() * source.height * 4)
                    val bitmap = Photos.convert(source, ColorSpace.get(ColorSpace.Named.SRGB))
                    try {
                        currentCoroutineContext().ensureActive()
                        push(bitmap, clearMetadata = metadata)
                    } catch (error: Throwable) {
                        bitmap.recycle()
                        throw error
                    }
                } else bitmapEdit(clearMetadata = true)
                return@work
            }
            discardResize()
            val options =
                ExportOptions(
                    metadata = metadata,
                    static = static,
                    flatten = flatten,
                    keepColor = keepColor,
                )
            val target = Photos.export(workspace.work, frame.source, frame.info, options)
            try {
                push(target)
            } catch (error: Throwable) {
                target.delete()
                throw error
            }
        }

    private suspend fun prepare(options: ExportOptions): File {
        val photo = state.value.photo
        val frame = checkNotNull(photo.frame)
        if (
            exportOptions == options &&
                exportedSource === frame &&
                state.value.exportFile?.isFile == true
        )
            return state.value.exportFile!!
        discardResize()
        val file =
            if (frame.source != null) Photos.export(workspace.work, frame.source, frame.info, options)
            else {
                val history = checkNotNull(history)
                val bitmap = history.bitmap(frame)
                if ((options.format == ImageFormat.JPEG && bitmap.hasAlpha()) ||
                    (!options.keepColor && bitmap.colorSpace?.isSrgb == false)
                ) history.reserve(
                    if (options.keepColor) bitmap.allocationByteCount.toLong()
                    else bitmap.width.toLong() * bitmap.height * 4
                )
                Photos.export(workspace.work, bitmap, frame.info, options)
            }
        val preview =
            try {
                Photos.preview(file).also { currentCoroutineContext().ensureActive() }
            } catch (error: Throwable) {
                file.delete()
                throw error
            }
        val old = state.value.exportFile
        mutable.update { it.copy(exportFile = file, exportPreview = preview) }
        old?.delete()
        exportOptions = options
        exportedSource = frame
        return file
    }

    fun previewExport(options: ExportOptions, ready: () -> Unit) =
        work(R.string.preparing_image) {
            prepare(options)
            withContext(Dispatchers.Main) { ready() }
        }

    fun saveImage(options: ExportOptions) =
        work(R.string.saving) {
            val file = prepare(options)
            val path = MediaFiles.save(
                context,
                file,
                MediaFiles.outputName(state.value.photo.name, file.extension),
                true,
            )
            mutable.update { it.copy(notice = Notice(R.string.file_saved, path)) }
        }

    fun shareImage(options: ExportOptions, ready: (File) -> Unit) =
        work(R.string.preparing_image) {
            val file = prepare(options)
            val directory = workspace.exports.apply { mkdirs() }
            val target =
                File(directory, MediaFiles.outputName(state.value.photo.name, file.extension))
            file.inputStream().use { input ->
                target.outputStream().use { MediaFiles.copy(input, it) }
            }
            withContext(Dispatchers.Main) { ready(target) }
        }

    fun editAudio(tags: Map<String, List<String>>, pictures: List<Picture>) {
        state.value.audio?.editor?.history?.change(AudioMetadata(tags, pictures))
    }

    fun undoAudio(direction: Int) {
        val audio = state.value.audio ?: return
        if (state.value.busy || audio.editor.hasDraft) return
        if (direction < 0) audio.editor.history.undo() else audio.editor.history.redo()
    }

    fun restoreAudio() {
        val editor = state.value.audio?.editor ?: return
        if (state.value.busy || editor.hasDraft || editor.history.value == editor.original) return
        editor.history.change(editor.original)
        mutable.update { it.copy(notice = Notice(R.string.original_audio_restored)) }
    }

    fun saveAudio() =
        work(R.string.saving) {
            val document = state.value.audio!!
            val metadata = document.editor.history.value
            val file = AudioFiles.save(workspace.work, document, metadata.tags, metadata.pictures)
            try {
                val path = MediaFiles.save(
                    context,
                    file,
                    MediaFiles.outputName(document.name, file.extension),
                    false,
                )
                val audio = AudioFiles.read(file, document.name).copy(
                    saved = metadata, editor = document.editor,
                    sourceUri = document.sourceUri, sourceBytes = document.sourceBytes,
                    sourceDuration = document.sourceDuration,
                )
                mutable.update {
                    it.copy(
                        audio = audio,
                        notice = Notice(R.string.file_saved, path),
                    )
                }
                document.file.delete()
            } catch (error: Exception) {
                file.delete()
                throw error
            }
        }

    fun cover(uri: Uri, ready: (Picture) -> Unit) =
        work(R.string.loading_photo) {
            val (file, _) = MediaFiles.import(context, uri, workspace.work)
            try {
                val info = Photos.info(file)
                val bitmap = Photos.decode(file, info)
                try {
                    val bytes =
                        java.io.ByteArrayOutputStream().use {
                            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                            it.toByteArray()
                        }
                    require(bytes.size <= 32 * 1024 * 1024) { "Cover exceeds 32 MB" }
                    withContext(Dispatchers.Main) {
                        ready(Picture(bytes, "", "Front Cover", "image/png"))
                    }
                } finally {
                    bitmap.recycle()
                }
            } finally {
                file.delete()
            }
        }

    fun saveCover(picture: Picture) =
        work(R.string.saving) {
            val mime = AudioFiles.pictureMime(picture.data)
            val file =
                MediaFiles.temporary(
                    workspace.work,
                    checkNotNull(
                        android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
                    ),
                )
            try {
                file.writeBytes(picture.data)
                val name = MediaFiles.outputName(state.value.audio!!.name, file.extension, "cover")
                val path = MediaFiles.save(context, file, name, true)
                mutable.update { it.copy(notice = Notice(R.string.file_saved, path)) }
            } finally {
                file.delete()
            }
        }
}
