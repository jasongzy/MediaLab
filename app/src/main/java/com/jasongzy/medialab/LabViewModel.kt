package com.jasongzy.medialab

import android.app.Application
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.snapshots.Snapshot
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
import kotlinx.coroutines.flow.first
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
    RESTORE_AUDIO(R.string.restore_original_audio, R.string.discard_unapplied_message, R.string.restore_original_audio),
    EXPORT_AUDIO(R.string.export, R.string.audio_export_pending, R.string.continue_action),
    UNDO(R.string.undo, R.string.discard_unapplied_message),
    REDO(R.string.redo, R.string.discard_unapplied_message),
    APPLY(R.string.apply, R.string.discard_other_edits_message, R.string.apply),
    RESTORE(R.string.restore_original, R.string.discard_unapplied_message, R.string.restore_original),
    REINFER(R.string.reinfer, R.string.replace_mask_message, R.string.reinfer),
    DISCARD_MASK(R.string.discard_mask, R.string.discard_mask_message),
    LOAD_MASK(R.string.load_mask, R.string.load_mask_message, R.string.load_mask),
    RESIZE_MASK(R.string.load_mask, R.string.resize_mask_message, R.string.continue_action),
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
    val sourceMime: String = info?.mime ?: "image/*",
    val sourceBytes: Long? = info?.bytes,
) {
    val frame
        get() = history.getOrNull(position)
    val pending
        get() = editor?.pending(checkNotNull(info)).orEmpty()
}

internal data class Notice(val message: Int, val argument: String? = null, val argumentRes: Int? = null)

internal data class LabState(
    val tab: Int = 0,
    val photo: PhotoState = PhotoState(),
    val storedMask: CutoutMask.Stored? = null,
    val batchPage: Boolean = false,
    val audio: AudioDocument? = null,
    val confirmation: Confirmation? = null,
    val colorLosses: List<Int> = emptyList(),
    val busy: Boolean = false,
    val showProgress: Boolean = false,
    val progress: Float? = null,
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
            (photo.history.flatMap { it.files } + audio?.workingFiles.orEmpty() + listOfNotNull(exportFile))
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
    private val playbackPositions = mutableMapOf<File, Long>()
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
        viewModelScope.launch(Dispatchers.IO) {
            try {
                workspace.clearStale()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (BuildConfig.DEBUG) Log.w("WorkspaceCache", "Stale cache cleanup failed", error)
            }
        }
    }

    private fun work(stage: Int, showProgress: Boolean = true, action: suspend () -> Unit) =
        task(stage, showProgress) { workspace.access.withLock { action() } }

    private fun task(stage: Int, showProgress: Boolean = true, action: suspend () -> Unit) {
        if (state.value.busy) return
        mutable.update {
            it.copy(busy = true, showProgress = showProgress, stage = stage, error = null)
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
                mutable.update { it.copy(busy = false, showProgress = false, progress = null) }
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
        playbackPositions[file] ?: 0

    fun rememberPlaybackPosition(file: File, position: Long) {
        val retained = state.value.workingFiles
        playbackPositions.keys.retainAll(retained)
        if (file in retained) playbackPositions[file] = position
    }

    fun clearError() {
        mutable.update { it.copy(error = null) }
    }

    fun clearNotice() {
        mutable.update { it.copy(notice = null) }
    }

    private fun historyNotice(direction: Int, action: Int) {
        mutable.update {
            it.copy(notice = Notice(if (direction < 0) R.string.undone else R.string.redone, argumentRes = action))
        }
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

    private suspend fun confirm(action: Confirmation, colorLosses: List<Int> = emptyList()): Boolean {
        val answer = CompletableDeferred<Boolean>()
        confirmationAnswer = answer
        mutable.update { it.copy(confirmation = action, colorLosses = colorLosses) }
        return try {
            answer.await()
        } finally {
            confirmationAnswer = null
            mutable.update { it.copy(confirmation = null, colorLosses = emptyList()) }
        }
    }

    private suspend fun confirmColor(forceSrgb: Boolean = false, keepHdr: Boolean = false): Boolean {
        val info = checkNotNull(state.value.photo.info)
        val losses = info.color.losses(forceSrgb, keepHdr && info.canKeepHdr)
        return losses.isEmpty() || confirm(Confirmation.CONVERT_COLOR, losses)
    }

    private suspend fun canApply(tool: PhotoTool): Boolean =
        state.value.photo.pending.none { it != tool } || confirm(Confirmation.APPLY)

    fun receive(uri: Uri) {
        viewModelScope.launch {
            state.first { !it.busy }
            open(uri, null)
        }
    }

    fun open(uri: Uri, type: ImportType?) =
        work(
            when (type) {
                ImportType.AUDIO -> R.string.loading_audio
                ImportType.IMAGE -> R.string.loading_photo
                null -> R.string.loading_media
            }
        ) {
            if (
                type == null &&
                    uri.scheme != "http" &&
                    (MediaFiles.name(context, uri).endsWith(".ncm", true) ||
                        context.contentResolver.openInputStream(uri)?.use { Ncm.matches(it) } ==
                            true)
            ) {
                NcmBatch.addFiles(context, listOf(uri), shared = true)
                showBatch(true)
                return@work
            }
            val (file, name) = MediaFiles.import(context, uri, workspace.work)
            acceptFile(file, name, uri, type)
        }

    fun openRaw(uri: Uri) = work(R.string.loading_raw) {
        val (source, name) = MediaFiles.import(context, uri, workspace.work)
        try {
            if (!canReplace(ImportType.IMAGE)) return@work
            history?.reserve(Runtime.getRuntime().maxMemory() / 3, keep = null)
            val frame = RawPhotos.decode(context, source)
            var preview: Bitmap? = null
            try {
                preview = Photos.preview(checkNotNull(frame.pixels))
                acceptPhoto(frame, preview, name, uri,
                    context.contentResolver.getType(uri) ?: "application/octet-stream", source.length())
            } finally {
                if (frame !== state.value.photo.frame) {
                    preview?.recycle()
                    frame.image.release()
                }
            }
        } finally {
            source.delete()
        }
    }

    private suspend fun acceptPhoto(
        frame: PhotoFrame,
        preview: Bitmap,
        name: String,
        uri: Uri?,
        sourceMime: String? = null,
        sourceBytes: Long? = frame.info.bytes,
    ) {
        val info = frame.info
        val next = PhotoHistory(workspace.work, frame, retainedBytes = {
            (history?.drafts.orEmpty().mapNotNull { it?.mask } +
                listOfNotNull(state.value.storedMask, state.value.photo.editor?.cutout?.mask))
                .distinct().sumOf { it.bytes }
        })
        val photo = PhotoState(
            name, next.frames, 0, preview, preview, info, sourceUri = uri,
            sourceMime = sourceMime ?: info.mime, sourceBytes = sourceBytes,
        )
        currentCoroutineContext().ensureActive()
        val old = history
        val oldExport = state.value.exportFile
        discardResize()
        history = next
        mutable.update {
            it.copy(photo = photo, tab = ImportType.IMAGE.ordinal, exportFile = null, exportPreview = null)
        }
        old?.close()
        oldExport?.delete()
    }

    private suspend fun acceptFile(file: File, name: String, uri: Uri?, requested: ImportType?) {
        var preview: Bitmap? = null
        var audio: AudioDocument? = null
        try {
            val bounds =
                android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(file.path, bounds)
            val type = requested ?: if (bounds.outWidth > 0) ImportType.IMAGE else ImportType.AUDIO
            when (type) {
                ImportType.IMAGE ->
                    if (bounds.outWidth <= 0)
                        throw UnsupportedMediaException(R.string.select_image_only)
                ImportType.AUDIO -> {
                    if (bounds.outWidth > 0 || Ncm.matches(file))
                        throw UnsupportedMediaException(R.string.select_audio_only)
                }
            }
            audio = if (type == ImportType.AUDIO)
                AudioFiles.read(file, name).copy(sourceUri = uri, sourceBytes = file.length())
            else null
            val image = if (type == ImportType.IMAGE) {
                preview = Photos.preview(file)
                PhotoFrame(file, Photos.info(file, preview))
            } else null
            if (!canReplace(type)) return
            when (type) {
                ImportType.IMAGE -> acceptPhoto(checkNotNull(image), checkNotNull(preview), name, uri)
                ImportType.AUDIO -> {
                    val document = checkNotNull(audio)
                    currentCoroutineContext().ensureActive()
                    val old = state.value.audio?.files.orEmpty()
                    mutable.update {
                        it.copy(audio = document, batchPage = false, tab = type.ordinal)
                    }
                    old.forEach { it.delete() }
                }
            }
        } catch (error: UnsupportedMediaException) {
            if (requested == null && error.messageRes in listOf(
                    R.string.select_image_only, R.string.select_audio_only,
                )) throw UnsupportedMediaException(R.string.select_media_only)
            throw error
        } catch (_: android.graphics.ImageDecoder.DecodeException) {
            throw UnsupportedMediaException(if (requested == null) R.string.select_media_only else R.string.select_image_only)
        } finally {
            if (file !in state.value.workingFiles) {
                preview?.recycle()
                (audio?.files ?: setOf(file)).forEach { it.delete() }
            }
        }
    }

    fun editPhotoMetadata(changes: Map<String, String?>, done: () -> Unit) = work(R.string.apply, showProgress = false) {
        val photo = state.value.photo
        val frame = checkNotNull(photo.frame)
        require(changes.keys.all { key -> PhotoMetadata.retained(frame.info.metadata).any { it.first == key } })
        val normalized = PhotoMetadata.normalize(workspace.work, changes)
        val metadata = frame.info.metadata.mapNotNull { (key, value) ->
            (if (key in normalized) normalized[key] else value)?.let { key to it }
        }
        if (metadata == frame.info.metadata) {
            withContext(Dispatchers.Main) { done() }
            return@work
        }
        val info = frame.info.copy(metadata = metadata,
            metadataEdits = frame.info.metadataEdits + normalized)
        checkNotNull(history).append(PhotoFrame(frame.image, info), action = R.string.media_metadata)
        publish(checkNotNull(photo.preview), restoredEditor = photo.editor)
        withContext(Dispatchers.Main) { done() }
    }

    private fun discardResize(retained: Bitmap? = null) {
        resizePreview?.bitmap?.takeIf { it !== retained }?.recycle()
        resizePreview = null
    }

    private fun publish(
        preview: Bitmap,
        notice: Int? = R.string.changes_applied,
        restoredEditor: PhotoEditorState? = null,
    ) {
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
                editor = restoredEditor ?: PhotoEditorState(history.current.info, preview.hasAlpha(), old.editor),
                sourceUri = old.sourceUri,
                sourceMime = old.sourceMime,
                sourceBytes = old.sourceBytes,
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

    private fun photoDraft(tool: PhotoTool): PhotoDraft? =
        checkNotNull(state.value.photo.editor).capture(tool, checkNotNull(state.value.photo.info))

    private suspend fun push(file: File, tool: PhotoTool) {
        val preview = Photos.preview(file)
        val frame = PhotoFrame(file, Photos.info(file, preview))
        checkNotNull(history).append(frame, photoDraft(tool), tool.label)
        discardResize()
        publish(preview)
    }

    private suspend fun push(bitmap: Bitmap, tool: PhotoTool, preview: Bitmap? = null, clearMetadata: Boolean = false) {
        val rendered = preview ?: Photos.preview(bitmap)
        val metadata = if (clearMetadata) emptyList() else checkNotNull(state.value.photo.info).metadata
        val frame = PhotoFrame(null, Photos.rasterInfo(bitmap, metadata), bitmap)
        checkNotNull(history).append(frame, photoDraft(tool), tool.label)
        discardResize(bitmap)
        publish(rendered)
    }

    fun undo(direction: Int) =
        work(R.string.preparing_image) {
            val history = checkNotNull(history)
            val position = (history.position + direction).coerceIn(history.frames.indices)
            if (position == history.position) return@work
            val metadataOnly = history.frames[position].image === history.current.image
            if (!metadataOnly && state.value.photo.pending.isNotEmpty() && !confirm(
                    if (direction < 0) Confirmation.UNDO else Confirmation.REDO
                )) return@work
            val frame = history.frames[position]
            val action = history.actions[if (direction < 0) history.position else position]
            if (metadataOnly) {
                history.move(position)
                publish(checkNotNull(state.value.photo.preview), notice = null, restoredEditor = state.value.photo.editor)
                historyNotice(direction, action ?: R.string.media_metadata)
                return@work
            }
            // Redo also restores the next operation's unapplied draft.
            val draft = history.drafts.getOrNull(if (direction < 0) history.position else position + 1)
            val preview =
                if (frame.source != null) Photos.preview(frame.source)
                else Photos.preview(history.bitmap(frame))
            val editor = PhotoEditorState(frame.info, preview.hasAlpha(), state.value.photo.editor)
            if (draft != null) {
                val sourcePreview = if (draft.mask != null && frame.info.color.losses().isNotEmpty())
                    Photos.preview(history.bitmap(frame), keepHdr = false) else preview
                draft.restore(editor, sourcePreview)
                editor.tool = draft.tool
            }
            currentCoroutineContext().ensureActive()
            discardResize()
            history.move(position)
            publish(preview, notice = null, restoredEditor = editor)
            historyNotice(direction, action ?: R.string.image_tab)
        }

    fun restoreOriginal() =
        work(R.string.preparing_image) {
            val history = checkNotNull(history)
            val original = history.frames.first()
            if (history.current === original) return@work
            if (state.value.photo.pending.isNotEmpty() && !confirm(Confirmation.RESTORE)) return@work
            val preview = checkNotNull(state.value.photo.original)
            history.append(original, action = R.string.restore_original)
            discardResize()
            publish(preview, R.string.original_image_restored)
        }

    private suspend fun bitmapEdit(
        tool: PhotoTool,
        crop: Rect? = null,
        clearMetadata: Boolean = false,
        transform: suspend (Bitmap) -> Unit = {},
    ) {
        discardResize()
        val bitmap = checkNotNull(history).copy(crop, keepHdr = tool == PhotoTool.CROP)
        try {
            transform(bitmap)
            currentCoroutineContext().ensureActive()
            push(bitmap, tool, clearMetadata = clearMetadata)
        } catch (error: Throwable) {
            bitmap.recycle()
            throw error
        }
    }

    fun crop(rect: Rect, lossless: Boolean) =
        work(R.string.preparing_image) {
            if (!lossless && !confirmColor(keepHdr = true)) return@work
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
                    PhotoMetadata.write(target, info.metadata, rect.width(), rect.height(), info.metadataEdits)
                    currentCoroutineContext().ensureActive()
                    push(target, PhotoTool.CROP)
                } catch (error: Exception) {
                    target.delete()
                    throw error
                }
            } else bitmapEdit(PhotoTool.CROP, rect)
        }

    fun expand(bounds: Rect, color: Int) =
        work(R.string.preparing_image) {
            if (!confirmColor()) return@work
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
                push(bitmap, PhotoTool.EXPAND)
            } catch (error: Throwable) {
                bitmap.recycle()
                throw error
            }
        }

    fun rotate(selection: RotationSelection, color: Int) =
        work(R.string.preparing_image) {
            if (!confirmColor(keepHdr = selection.keepsHdr)) return@work
            if (!selection.changed || !canApply(PhotoTool.ROTATE)) return@work
            discardResize()
            val history = checkNotNull(history)
            val source = history.bitmap()
            val size = selection.size(source.width, source.height)
            history.reserve(bitmapBytes(source) * size.width * size.height / source.width / source.height)
            val bitmap = Photos.rotate(source, selection, color)
            try {
                currentCoroutineContext().ensureActive()
                push(bitmap, PhotoTool.ROTATE)
            } catch (error: Throwable) {
                bitmap.recycle()
                throw error
            }
        }

    fun resize(width: Int, height: Int, method: Int) =
        work(R.string.preparing_image) {
            if (!confirmColor(keepHdr = true)) return@work
            if (!canApply(PhotoTool.RESIZE)) return@work
            val result = resized(width, height, method)
            push(result.bitmap, PhotoTool.RESIZE, result.preview)
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
                it.source.image === history.current.image &&
                    it.width == width &&
                    it.height == height &&
                    it.method == method
            }
            ?.let {
                return it
            }
        discardResize()
        val source = history.bitmap()
        history.reserve(bitmapBytes(source) * width * height / source.width / source.height)
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
            if (!confirmColor()) return@work
            if (!canApply(PhotoTool.COVER)) return@work
            bitmapEdit(PhotoTool.COVER) {
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
            if (!confirmColor()) return@work
            val started = SystemClock.elapsedRealtime()
            val values = Photos.inferMask(context, bitmap, model) { stage ->
                mutable.update { it.copy(stage = stage) }
            }
            val mask = CutoutMask.Inferred(values, model)
            val preview = if (converts) Photos.preview(bitmap, keepHdr = false) else checkNotNull(photo.preview)
            val maskPreview = Photos.maskPreview(mask, preview.width, preview.height, bitmap.width, bitmap.height)
            currentCoroutineContext().ensureActive()
            val elapsed = SystemClock.elapsedRealtime() - started
            withContext(Dispatchers.Main) {
                editor.cutout = CutoutDraft(mask, preview, maskPreview)
                editor.dropper = false
                mutable.update { it.copy(photo = it.photo.copy(backgroundElapsedMs = elapsed)) }
            }
        }

    fun storeMask() = work(R.string.preparing_image) {
        val photo = state.value.photo
        val draft = photo.editor?.cutout ?: return@work
        val info = checkNotNull(photo.info)
        discardResize()
        checkNotNull(history).reserve(info.width.toLong() * info.height, keep = null)
        val stored = Photos.storeMask(draft, info.width, info.height)
        currentCoroutineContext().ensureActive()
        mutable.update { it.copy(storedMask = stored, notice = Notice(R.string.mask_stored)) }
    }

    fun loadMask() = work(R.string.preparing_image) {
        val mask = state.value.storedMask ?: return@work
        val photo = state.value.photo
        val editor = checkNotNull(photo.editor)
        val info = checkNotNull(photo.info)
        if (editor.cutout != null && !confirm(Confirmation.LOAD_MASK)) return@work
        if ((mask.width != info.width || mask.height != info.height) && !confirm(Confirmation.RESIZE_MASK))
            return@work
        val converts = info.color.losses().isNotEmpty()
        if (!confirmColor()) return@work
        discardResize()
        val preview =
            if (converts) Photos.preview(checkNotNull(history).bitmap(), keepHdr = false) else checkNotNull(photo.preview)
        val maskPreview = Photos.maskPreview(mask, preview.width, preview.height)
        currentCoroutineContext().ensureActive()
        withContext(Dispatchers.Main) {
            editor.cutout = CutoutDraft(mask, preview, maskPreview)
            editor.dropper = false
            mutable.update { it.copy(photo = it.photo.copy(backgroundElapsedMs = null)) }
        }
    }

    fun applyBackground() =
        work(R.string.finishing_image) {
            val photo = state.value.photo
            val editor = checkNotNull(photo.editor)
            val draft = editor.cutout ?: return@work
            if (!canApply(PhotoTool.BACKGROUND)) return@work
            bitmapEdit(PhotoTool.BACKGROUND) { bitmap ->
                Photos.applyMask(bitmap, draft.mask, editor.cutoutBackground, draft.history.value)
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
            val options = ExportOptions(metadata = metadata, static = static, flatten = flatten, keepColor = keepColor)
            if (!canClean(frame.info, options, frame.source != null)) return@work
            if (!options.preservesSource(frame.info) && !confirmColor(forceSrgb = !keepColor, keepHdr = keepColor)) return@work
            val convert = !keepColor && (frame.info.colorSpace?.isSrgb == false || frame.info.gainmap)
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
                        push(bitmap, PhotoTool.CLEAN, clearMetadata = metadata)
                    } catch (error: Throwable) {
                        bitmap.recycle()
                        throw error
                    }
                } else {
                    checkNotNull(history).append(PhotoFrame(frame.image,
                        frame.info.copy(metadata = emptyList(), metadataEdits = emptyMap())), action = PhotoTool.CLEAN.label)
                    publish(checkNotNull(photo.preview))
                }
                return@work
            }
            discardResize()
            val target = Photos.export(workspace.work, frame.source, frame.info, options)
            try {
                push(target, PhotoTool.CLEAN)
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
                if ((options.encodedFormat(frame.info) == ImageFormat.JPEG && bitmap.hasAlpha()) ||
                    (!options.keepColor && (bitmap.colorSpace?.isSrgb == false || frame.info.gainmap))
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
            shareOutput(prepare(options), state.value.photo.name, ready)
        }

    private suspend fun shareOutput(file: File, name: String, ready: (File) -> Unit) {
        val directory = workspace.exports.apply { mkdirs() }
        val target = File(directory, MediaFiles.outputName(name, file.extension))
        try {
            file.inputStream().use { input ->
                target.outputStream().use { MediaFiles.copy(input, it) }
            }
            withContext(Dispatchers.Main) { ready(target) }
        } catch (error: Exception) {
            target.delete()
            throw error
        }
    }

    private fun commitAudio(document: AudioDocument, frame: AudioFrame, action: Int) {
        val editor = document.editor
        val files = editor.files
        val changedMedia = editor.history.value.media != frame.media
        Snapshot.withMutableSnapshot {
            editor.commit(frame, action)
            if (changedMedia) editor.resetPlayback()
        }
        (files - editor.files).forEach { it.delete() }
    }

    fun applyAudioMetadata(tool: AudioTool) =
        work(R.string.apply, showProgress = false) {
            val audio = state.value.audio ?: return@work
            val editor = audio.editor
            if (tool !in editor.pending) return@work
            val frame = editor.history.value
            val metadata = when (tool) {
                AudioTool.TAGS -> frame.metadata.copy(tags = editor.tags.value)
                AudioTool.ARTWORK -> frame.metadata.copy(pictures = editor.pictures.value)
                else -> return@work
            }
            commitAudio(audio, frame.copy(metadata = metadata), tool.label)
            mutable.update { it.copy(notice = Notice(R.string.changes_applied)) }
        }

    fun undoAudio(direction: Int) = work(R.string.loading_audio, showProgress = false) {
        val audio = state.value.audio ?: return@work
        val editor = audio.editor
        val history = editor.history
        if (editor.tag != null || editor.picture != null ||
            (if (direction < 0) !history.canUndo else !history.canRedo)) return@work
        if (editor.pending.isNotEmpty() && !confirm(if (direction < 0) Confirmation.UNDO else Confirmation.REDO)) return@work
        val action = if (direction < 0) history.undoAction else history.redoAction
        val before = history.value.media
        Snapshot.withMutableSnapshot {
            if (direction < 0) history.undo() else history.redo()
            if (before != history.value.media) editor.resetPlayback() else editor.resetTime()
            editor.resetMetadata()
        }
        historyNotice(direction, action ?: R.string.audio_tab)
    }

    fun restoreAudio() = work(R.string.loading_audio, showProgress = false) {
        val audio = state.value.audio ?: return@work
        val editor = audio.editor
        if (editor.tag != null || editor.picture != null ||
            (editor.history.value == editor.original && editor.pending.isEmpty())) return@work
        if (editor.pending.isNotEmpty() && !confirm(Confirmation.RESTORE_AUDIO)) return@work
        commitAudio(audio, editor.original, R.string.restore_original_audio)
        editor.resetPlayback()
        editor.resetMetadata()
        mutable.update { it.copy(notice = Notice(R.string.original_audio_restored)) }
    }

    fun applyAudioTime() = processAudio(null)

    fun editAudioChannels(merge: Boolean) = processAudio(merge)

    private fun processAudio(merge: Boolean?) = work(R.string.processing_audio) {
        val audio = state.value.audio ?: return@work
        val editor = audio.editor
        val frame = editor.history.value
        if (merge == null && (!editor.inputValid || !editor.pendingTime)) return@work
        if (merge != null && editor.pendingTime && !confirm(Confirmation.APPLY)) return@work
        val groups = merge?.let { channelGroups(frame.media.channels, editor.selectedChannels, it) }
        val time = if (merge == null) editor.time.value else MediaTimeEdit(frame.media.durationMs)
        val target = MediaFiles.temporary(workspace.work, "flac")
        try {
            val media = renderAudio(frame.media, target, time, groups) { progress ->
                mutable.update { it.copy(progress = progress) }
            }
            currentCoroutineContext().ensureActive()
            commitAudio(audio, frame.copy(media = media),
                if (merge == null) R.string.video_time else R.string.audio_channels)
            mutable.update { it.copy(notice = Notice(R.string.changes_applied)) }
        } finally {
            if (target !in editor.files) target.delete()
        }
    }

    private suspend fun prepareAudio(): AudioExportPreview? {
        val audio = checkNotNull(state.value.audio)
        val editor = audio.editor
        if (editor.pending.isNotEmpty() && !confirm(Confirmation.EXPORT_AUDIO)) return null
        val frame = editor.history.value
        val options = editor.exportOptions
        editor.preview?.takeIf { it.frame == frame && it.options == options && it.file.isFile }?.let { return it }
        val file = exportAudio(workspace.work, audio, options) { progress ->
            mutable.update { it.copy(progress = progress) }
        }
        val old = editor.preview
        val preview = AudioExportPreview(file, frame, options)
        editor.preview = preview
        old?.files?.forEach { it.delete() }
        return preview
    }

    fun previewAudio() = work(R.string.processing_audio) {
        val preview = prepareAudio() ?: return@work
        val audio = checkNotNull(state.value.audio)
        if (preview.playable == preview.file || !preview.playable.isFile)
            audio.editor.preview = preview.copy(playable = AudioFiles.preparePlayback(
                preview.file, preview.options.encoding(audio.encoding),
            ))
        audio.editor.showPreview = true
    }

    fun shareAudio(ready: (File) -> Unit) = work(R.string.processing_audio) {
        val preview = prepareAudio() ?: return@work
        shareOutput(preview.file, checkNotNull(state.value.audio).name, ready)
    }

    fun saveAudio() = work(R.string.processing_audio) {
        val preview = prepareAudio() ?: return@work
        val audio = checkNotNull(state.value.audio)
        val path = MediaFiles.save(
            context, preview.file, MediaFiles.outputName(audio.name, preview.file.extension), false,
        )
        mutable.update { it.copy(audio = audio.copy(saved = preview.frame), notice = Notice(R.string.file_saved, path)) }
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
